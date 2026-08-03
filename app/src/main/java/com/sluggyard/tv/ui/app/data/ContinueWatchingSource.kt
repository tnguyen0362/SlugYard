package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.data.local.WatchProgressSource
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
