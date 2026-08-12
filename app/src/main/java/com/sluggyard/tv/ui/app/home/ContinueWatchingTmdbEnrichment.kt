package com.sluggyard.tv.ui.app.home

import android.util.Log
import com.sluggyard.tv.core.tmdb.TmdbService
import com.sluggyard.tv.data.repository.StreamMergeUtils
import com.sluggyard.tv.ui.app.data.PlaybackCheckpoint
import com.sluggyard.tv.ui.app.data.SeriesCompletionSeed
import com.sluggyard.tv.ui.app.data.groupPlaybackCheckpoints

private const val TAG = "SlugYardCwArt"

/**
 * One identity that should receive a CW poster. [tileKeys] are every id that Home tiles may use
 * for this show (episode contentId, show/parent id, IMDb base) so resume + next-up both map.
 */
data class CwArtTarget(
    val tileKeys: Set<String>,
    val imdbId: String?,
    val mediaType: String,
    val existingPosterUrl: String? = null,
    val label: String = "",
)

/**
 * Resolves a TMDB poster for Continue Watching tiles.
 * Series episode ids look like `tt123:2:8` — strip to the IMDb base for lookup.
 */
internal fun continueWatchingImdbId(checkpoint: PlaybackCheckpoint): String? {
    val raw = checkpoint.parentId?.takeIf { it.isNotBlank() } ?: checkpoint.contentId
    return imdbBaseFromContentId(raw)
}

internal fun imdbBaseFromContentId(raw: String?): String? {
    val base = raw?.trim()?.substringBefore(':')?.trim().orEmpty()
    return base.takeIf { it.startsWith("tt", ignoreCase = true) }
}

internal fun continueWatchingMediaType(checkpoint: PlaybackCheckpoint): String {
    val series = checkpoint.season != null ||
        checkpoint.episode != null ||
        checkpoint.parentType.equals("series", ignoreCase = true) ||
        checkpoint.contentType.equals("series", ignoreCase = true) ||
        checkpoint.contentType.equals("tv", ignoreCase = true)
    return if (series) "series" else "movie"
}

fun PlaybackCheckpoint.toCwArtTarget(): CwArtTarget {
    val derived = StreamMergeUtils.deriveInlineMetaId(contentId)
    val imdb = continueWatchingImdbId(this)
    val keys = buildSet {
        if (contentId.isNotBlank()) add(contentId)
        parentId?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (derived.isNotBlank()) add(derived)
        imdb?.let { add(it) }
    }
    return CwArtTarget(
        tileKeys = keys,
        imdbId = imdb,
        mediaType = continueWatchingMediaType(this),
        existingPosterUrl = posterUrl,
        label = title,
    )
}

fun SeriesCompletionSeed.toCwArtTarget(): CwArtTarget {
    val imdb = imdbBaseFromContentId(showId) ?: imdbBaseFromContentId(seriesKey)
    val keys = buildSet {
        if (showId.isNotBlank()) add(showId)
        if (seriesKey.isNotBlank()) add(seriesKey)
        imdb?.let { add(it) }
        StreamMergeUtils.deriveInlineMetaId(showId).takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return CwArtTarget(
        tileKeys = keys,
        imdbId = imdb,
        mediaType = "series",
        existingPosterUrl = posterUrl,
        label = title,
    )
}

/**
 * Builds art targets for the full CW rail — mid-stream **and** next-up / completion seeds.
 *
 * Root cause of silent blank next-up tiles: enrichment used only [PlaybackCheckpoint] rows, which
 * drop at ≥98%. Rail then shows NextUp with null poster and empty catalog match.
 */
fun buildContinueWatchingArtTargets(
    checkpoints: List<PlaybackCheckpoint>,
    completionSeeds: List<SeriesCompletionSeed>,
    extraShowIds: Collection<String> = emptyList(),
    limit: Int = 24,
): List<CwArtTarget> {
    val bySeries = linkedMapOf<String, CwArtTarget>()
    fun put(target: CwArtTarget) {
        val key = target.imdbId
            ?: target.tileKeys.firstOrNull()
            ?: return
        val existing = bySeries[key]
        if (existing == null) {
            bySeries[key] = target
            return
        }
        // Prefer a target that already has art; else merge keys.
        bySeries[key] = CwArtTarget(
            tileKeys = existing.tileKeys + target.tileKeys,
            imdbId = existing.imdbId ?: target.imdbId,
            mediaType = existing.mediaType,
            existingPosterUrl = existing.existingPosterUrl?.takeIf { it.isNotBlank() }
                ?: target.existingPosterUrl,
            label = existing.label.ifBlank { target.label },
        )
    }
    groupPlaybackCheckpoints(checkpoints).forEach { put(it.toCwArtTarget()) }
    completionSeeds.forEach { put(it.toCwArtTarget()) }
    extraShowIds.forEach { id ->
        if (id.isBlank()) return@forEach
        put(
            CwArtTarget(
                tileKeys = setOf(id, StreamMergeUtils.deriveInlineMetaId(id)).filter { it.isNotBlank() }.toSet(),
                imdbId = imdbBaseFromContentId(id),
                mediaType = "series",
                label = id,
            ),
        )
    }
    return bySeries.values.take(limit)
}

/**
 * Fetches TMDB posters for CW targets when enrichment is enabled.
 * Returns a map of **every** tile key → poster URL so resume and next-up both resolve.
 */
internal suspend fun enrichContinueWatchingPosters(
    targets: List<CwArtTarget>,
    tmdbService: TmdbService,
): Map<String, String> {
    val out = linkedMapOf<String, String>()
    if (targets.isEmpty()) {
        Log.i(TAG, "enrich skip empty targets")
        return out
    }
    Log.i(TAG, "enrich start targets=${targets.size}")
    for (target in targets) {
        // Already have a real URL permanently on the seed/checkpoint — mirror to all keys.
        val kept = target.existingPosterUrl?.takeIf { it.isNotBlank() && it.startsWith("http") }
        if (kept != null) {
            target.tileKeys.forEach { out[it] = kept }
            Log.i(TAG, "keep existing poster keys=${target.tileKeys.size} label='${target.label.take(40)}'")
            continue
        }
        val imdb = target.imdbId
        if (imdb.isNullOrBlank()) {
            Log.i(
                TAG,
                "skip no-imdb keys=${target.tileKeys.joinToString(",").take(80)} label='${target.label.take(40)}'",
            )
            continue
        }
        val images = runCatching {
            tmdbService.fetchImdbImages(imdb, target.mediaType)
        }.getOrNull()
        val poster = images?.posterUrl?.takeIf { it.isNotBlank() }
            ?: images?.backdropUrl?.takeIf { it.isNotBlank() }
        if (poster == null) {
            Log.i(TAG, "skip tmdb-empty imdb=$imdb media=${target.mediaType}")
            continue
        }
        target.tileKeys.forEach { out[it] = poster }
        out[imdb] = poster
        Log.i(
            TAG,
            "hit imdb=$imdb keys=${target.tileKeys.size} posterHost=${runCatching { java.net.URI(poster).host }.getOrNull()}",
        )
    }
    Log.i(TAG, "enrich done mappedKeys=${out.size}")
    return out
}

/** @deprecated Prefer [enrichContinueWatchingPosters] with [buildContinueWatchingArtTargets]. */
internal suspend fun enrichContinueWatchingPosters(
    checkpoints: List<PlaybackCheckpoint>,
    tmdbService: TmdbService,
    limit: Int = 15,
): Map<String, String> = enrichContinueWatchingPosters(
    targets = buildContinueWatchingArtTargets(
        checkpoints = checkpoints,
        completionSeeds = emptyList(),
        limit = limit,
    ),
    tmdbService = tmdbService,
)

fun HomeState.withContinueWatchingPosters(postersByContentId: Map<String, String>): HomeState {
    if (postersByContentId.isEmpty()) return this
    return copy(
        rows = rows.map { row ->
            if (row.id != "continue_watching") row
            else row.copy(
                posters = row.posters.map { poster ->
                    val enriched = postersByContentId[poster.id]
                        ?: poster.parentId?.let { postersByContentId[it] }
                        ?: postersByContentId[StreamMergeUtils.deriveInlineMetaId(poster.id)]
                        ?: imdbBaseFromContentId(poster.id)?.let { postersByContentId[it] }
                        ?: poster.parentId?.let { imdbBaseFromContentId(it) }?.let { postersByContentId[it] }
                    when {
                        enriched.isNullOrBlank() -> poster
                        // Fill holes **and** replace non-http junk so Coil never gets stuck.
                        poster.imageUrl.isNullOrBlank() || !poster.imageUrl.startsWith("http") ->
                            poster.copy(imageUrl = enriched)
                        else -> poster
                    }
                },
            )
        },
    )
}
