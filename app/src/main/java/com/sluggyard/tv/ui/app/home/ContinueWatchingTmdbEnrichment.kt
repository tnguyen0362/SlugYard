package com.sluggyard.tv.ui.app.home

import com.sluggyard.tv.core.tmdb.TmdbService
import com.sluggyard.tv.ui.app.data.PlaybackCheckpoint

/**
 * Resolves a TMDB poster for Continue Watching tiles.
 * Series episode ids look like `tt123:2:8` — strip to the IMDb base for lookup.
 */
internal fun continueWatchingImdbId(checkpoint: PlaybackCheckpoint): String? {
    val raw = checkpoint.parentId?.takeIf { it.isNotBlank() } ?: checkpoint.contentId
    val base = raw.substringBefore(':').trim()
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

/**
 * Fetches TMDB posters for CW checkpoints when enrichment is enabled.
 * Returns contentId → posterUrl for tiles that got a TMDB image.
 */
internal suspend fun enrichContinueWatchingPosters(
    checkpoints: List<PlaybackCheckpoint>,
    tmdbService: TmdbService,
    limit: Int = 15,
): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (checkpoint in checkpoints.take(limit)) {
        val imdb = continueWatchingImdbId(checkpoint) ?: continue
        val images = runCatching {
            tmdbService.fetchImdbImages(imdb, continueWatchingMediaType(checkpoint))
        }.getOrNull() ?: continue
        val poster = images.posterUrl?.takeIf { it.isNotBlank() } ?: continue
        out[checkpoint.contentId] = poster
    }
    return out
}

fun HomeState.withContinueWatchingPosters(postersByContentId: Map<String, String>): HomeState {
    if (postersByContentId.isEmpty()) return this
    return copy(
        rows = rows.map { row ->
            if (row.id != "continue_watching") row
            else row.copy(
                posters = row.posters.map { poster ->
                    postersByContentId[poster.id]?.let { url -> poster.copy(imageUrl = url) } ?: poster
                },
            )
        },
    )
}
