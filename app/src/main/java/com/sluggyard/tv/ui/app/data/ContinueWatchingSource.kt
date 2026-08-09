package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.watchstate.CwNextUpSeed
import com.sluggyard.tv.core.watchstate.CwResumeInput
import com.sluggyard.tv.core.watchstate.ProgressOrigin
import com.sluggyard.tv.data.local.WatchProgressSource
import com.sluggyard.tv.data.repository.StreamMergeUtils
import com.sluggyard.tv.domain.model.WatchProgress

/**
 * Home Continue Watching source selection. Trakt is only used when the setting asks for it
 * and Trakt is authenticated; otherwise the rewrite/SlugYard sync checkpoints drive the rail.
 */
fun useTraktContinueWatching(
    source: WatchProgressSource,
    traktAuthenticated: Boolean,
): Boolean = source == WatchProgressSource.TRAKT && traktAuthenticated

/**
 * Maps repository/Trakt [WatchProgress] into rewrite Home checkpoints.
 *
 * Keep true absolute ms when present. For Trakt percent-only rows, do not freeze
 * percent×Trakt-duration into [positionMs] (that length often differs from the stream
 * and would bypass [WatchProgress.resolveResumePosition]). Stash
 * [remoteProgressFraction] so the rail paints while the player re-resolves percent
 * against the real duration; [AppShell] resume also falls back to local rewrite ms.
 */
fun WatchProgress.toContinueWatchingCheckpoint(): PlaybackCheckpoint {
    val isSeries = season != null || episode != null ||
        contentType.equals("series", ignoreCase = true) ||
        contentType.equals("tv", ignoreCase = true) ||
        contentType.equals("show", ignoreCase = true)
    val parentId = contentId.takeIf { isSeries && it.isNotBlank() }
    val episodeContentId = videoId.trim().takeIf { isSeries && it.isNotBlank() && it != contentId }
    val absolutePositionMs = position.coerceAtLeast(0L)
    return PlaybackCheckpoint(
        contentId = episodeContentId ?: contentId,
        contentType = if (isSeries) "series" else contentType.ifBlank { "movie" },
        title = name.ifBlank { contentId },
        posterUrl = poster,
        addonId = null,
        parentId = parentId,
        parentType = "series".takeIf { isSeries },
        positionMs = absolutePositionMs,
        durationMs = duration.coerceAtLeast(0L),
        updatedAtEpochMs = lastWatched.coerceAtLeast(0L),
        season = season,
        episode = episode,
        // Only stash an explicit Trakt percent when absolute ms cannot paint the rail.
        // Never force 0.0 from a missing percent — that hides real position-only progress.
        remoteProgressFraction = progressPercent
            ?.let { (it / 100.0).coerceIn(0.0, 1.0) }
            ?.takeIf { absolutePositionMs <= 0L || duration <= 0L },
    )
}

/** Normalizes a mid-stream [WatchProgress] (Trakt in-progress / local) for membership. */
fun WatchProgress.toCwResumeInput(): CwResumeInput {
    val isSeries = season != null || episode != null ||
        contentType.equals("series", ignoreCase = true) ||
        contentType.equals("tv", ignoreCase = true) ||
        contentType.equals("show", ignoreCase = true)
    val parentId = contentId.takeIf { isSeries && it.isNotBlank() }
    val episodeContentId = videoId.trim().takeIf { isSeries && it.isNotBlank() && it != contentId }
    val absolutePositionMs = position.coerceAtLeast(0L)
    return CwResumeInput(
        seriesKey = parentId ?: contentId,
        contentId = episodeContentId ?: contentId,
        contentType = if (isSeries) "series" else contentType.ifBlank { "movie" },
        title = name.ifBlank { contentId },
        posterUrl = poster,
        season = season,
        episode = episode,
        positionMs = absolutePositionMs,
        durationMs = duration.coerceAtLeast(0L),
        updatedAtEpochMs = lastWatched.coerceAtLeast(0L),
        remoteProgressFraction = progressPercent
            ?.let { (it / 100.0).coerceIn(0.0, 1.0) }
            ?.takeIf { absolutePositionMs <= 0L || duration <= 0L },
    )
}

/** Normalizes a rewrite checkpoint for membership. */
fun PlaybackCheckpoint.toCwResumeInput(): CwResumeInput {
    val isEpisode = season != null || episode != null ||
        parentType.equals("series", ignoreCase = true)
    val seriesKey = if (isEpisode) {
        parentId?.takeIf { it.isNotBlank() }
            ?: StreamMergeUtils.deriveInlineMetaId(contentId)
    } else {
        contentId
    }
    return CwResumeInput(
        seriesKey = seriesKey,
        contentId = contentId,
        contentType = contentType,
        title = title,
        posterUrl = posterUrl,
        addonId = addonId,
        season = season,
        episode = episode,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAtEpochMs = updatedAtEpochMs,
        remoteProgressFraction = remoteProgressFraction,
        contentGenres = contentGenres,
        contentLanguage = contentLanguage,
    )
}

/** A completed [WatchProgress] episode becomes a next-up seed (series only). */
fun WatchProgress.toCwNextUpSeed(): CwNextUpSeed? {
    val seedSeason = season ?: return null
    val seedEpisode = episode ?: return null
    if (seedSeason <= 0 || seedEpisode <= 0) return null
    if (contentId.isBlank()) return null
    return CwNextUpSeed(
        seriesKey = contentId,
        showId = contentId,
        season = seedSeason,
        episode = seedEpisode,
        watchedAtMs = lastWatched.coerceAtLeast(0L),
        progressFraction = progressPercentage.toDouble(),
        origin = when (source) {
            WatchProgress.SOURCE_TRAKT_HISTORY -> ProgressOrigin.PASSIVE_TRAKT
            WatchProgress.SOURCE_LOCAL -> ProgressOrigin.LOCAL
            else -> ProgressOrigin.TRAKT
        },
        title = name.takeIf { it.isNotBlank() && it != contentId },
    )
}

/**
 * Trakt-mode source merge (plan A4): Trakt in-progress wins for representable series; local
 * rewrite mid-stream rows are kept for non-representable ids (kitsu/mal/…) and for the same
 * imdb id when Trakt has no in-progress entry for that key (avoids a silent empty rail after
 * watching only on this device). When both sources cover the same series, an entry with a real
 * absolute position beats a percent-only Trakt row.
 */
fun mergeTraktCwResumeInputs(
    traktInProgress: List<CwResumeInput>,
    local: List<CwResumeInput>,
): List<CwResumeInput> {
    if (traktInProgress.isEmpty()) return local
    val traktByKey = traktInProgress.associateBy(CwResumeInput::seriesKey)
    val merged = linkedMapOf<String, CwResumeInput>()
    traktByKey.values.forEach { merged[it.seriesKey] = it }
    local.forEach { input ->
        val existing = merged[input.seriesKey]
        val keepLocal = when {
            existing == null -> true
            // Non-representable local ids never collide with Trakt rows.
            !isTraktCompatibleId(input.seriesKey) -> false
            // Same series: prefer the entry with absolute position over percent-only.
            existing.positionMs <= 0L && input.positionMs > 0L -> {
                merged[input.seriesKey] = input
                false
            }
            else -> false
        }
        if (keepLocal) merged[input.seriesKey] = input
    }
    return merged.values.toList()
}

private fun isTraktCompatibleId(contentId: String): Boolean {
    val raw = contentId.trim()
    if (raw.startsWith("tt")) return true
    if (raw.startsWith("tmdb:", ignoreCase = true)) return true
    if (raw.startsWith("trakt:", ignoreCase = true)) return true
    return raw.substringBefore(':').toIntOrNull() != null
}
