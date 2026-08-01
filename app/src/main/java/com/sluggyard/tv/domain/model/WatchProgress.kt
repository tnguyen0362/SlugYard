package com.sluggyard.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents the watch progress for a content item (movie or episode).
 */
@Immutable
data class WatchProgress(
    val contentId: String,           // IMDB ID of the movie/series
    val contentType: String,         // "movie" or "series"
    val name: String,                // Movie or series name
    val poster: String?,             // Poster URL
    val backdrop: String?,           // Backdrop URL
    val logo: String?,               // Logo URL
    val videoId: String,             // Specific video/episode ID being watched
    val season: Int?,                // Season number (null for movies)
    val episode: Int?,               // Episode number (null for movies)
    val episodeTitle: String?,       // Episode title (null for movies)
    val position: Long,              // Current playback position in ms
    val duration: Long,              // Total duration in ms
    val lastWatched: Long,           // Timestamp when last watched
    val addonBaseUrl: String? = null, // Addon that was used to play
    val progressPercent: Float? = null, // 0..100 from remote sources like Trakt playback
    val source: String = SOURCE_LOCAL,
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null
) {
    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_TRAKT_PLAYBACK = "trakt_playback"
        const val SOURCE_TRAKT_HISTORY = "trakt_history"
        const val SOURCE_TRAKT_SHOW_PROGRESS = "trakt_show_progress"
        const val STARTED_THRESHOLD = 0.02f
        const val COMPLETED_THRESHOLD = 0.90f
    }

    /**
     * Progress percentage (0.0 to 1.0)
     */
    val progressPercentage: Float
        get() {
            progressPercent?.let { explicitPercent ->
                return (explicitPercent / 100f).coerceIn(0f, 1f)
            }
            return if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        }

    /**
     * Returns true if the content has been watched past the threshold (default 90%)
     */
    fun isCompleted(threshold: Float = COMPLETED_THRESHOLD): Boolean = progressPercentage >= threshold

    /**
     * Started but not completed — aligns with SlugYard Continue Watching:
     * ~10s absolute floor or 2%+, and under the completion threshold.
     */
    fun isInProgress(startThreshold: Float = STARTED_THRESHOLD, endThreshold: Float = COMPLETED_THRESHOLD): Boolean {
        if (duration > 0L && position > 0L) {
            val fraction = (position.toDouble() / duration.toDouble()).toFloat()
            if (fraction >= endThreshold) return false
            if (position >= 10_000L || fraction >= startThreshold) return true
        }
        return progressPercentage >= startThreshold && progressPercentage < endThreshold
    }

    /**
     * Returns the remaining time in milliseconds
     */
    val remainingTime: Long
        get() = (duration - position).coerceAtLeast(0)

    fun resolveResumePosition(actualDuration: Long): Long {
        if (actualDuration <= 0) return position.coerceAtLeast(0L)
        if (duration > 0 && position > 0) {
            return position.coerceIn(0L, actualDuration)
        }
        progressPercent?.let { explicitPercent ->
            val fraction = (explicitPercent / 100f).coerceIn(0f, 1f)
            return (actualDuration * fraction).toLong()
        }
        return position.coerceAtLeast(0L)
    }
}
