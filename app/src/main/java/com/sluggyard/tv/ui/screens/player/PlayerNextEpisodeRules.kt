package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.data.local.NextEpisodeThresholdMode
import com.sluggyard.tv.data.repository.SkipInterval
import com.sluggyard.tv.domain.model.Video
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Pure rules for "Up Next" autoplay: picking the next episode in a series and
 * deciding when the "Next episode" card should surface during playback.
 *
 * All functions are side-effect free so they can be unit-tested in isolation.
 */
object PlayerNextEpisodeRules {

    /**
     * Returns the episode that follows [currentEpisode] (within [currentSeason]
     * when non-null), or `null` when there is no successor.
     */
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int?,
        currentEpisode: Int
    ): Video? {
        // Absolute-numbered content (e.g. some anime via Kitsu) carries no season;
        // order by episode number alone and advance to the next one.
        if (currentSeason == null) {
            val ordered = videos
                .filter { it.episode != null }
                .sortedWith(compareBy<Video>({ it.season ?: 0 }, { it.episode ?: 0 }))
            val idx = ordered.indexOfFirst { it.episode == currentEpisode }
            return if (idx < 0) null else ordered.getOrNull(idx + 1)
        }

        val ordered = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        val idx = ordered.indexOfFirst { it.season == currentSeason && it.episode == currentEpisode }
        if (idx < 0) return null
        return ordered.getOrNull(idx + 1)
    }

    /**
     * Decides whether the "Next episode" card should be visible at [positionMs].
     *
     * When outro skip-intervals are available, the card fires at the earliest
     * outro start unless the post-outro tail is long enough to honor the user's
     * configured threshold. Without outro data the user threshold is applied
     * directly against the file duration.
     */
    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        val outros = skipIntervals.filter { it.type in OUTRO_SEGMENT_TYPES }

        if (outros.isNotEmpty()) {
            if (durationMs <= 0L) return false
            val latestOutroEndMs = (outros.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroTailMs = durationMs - latestOutroEndMs
            val userThresholdMs = userThresholdMs(thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd, durationMs)

            return if (postOutroTailMs > userThresholdMs) {
                crossesUserThreshold(thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd, positionMs, durationMs)
            } else {
                // Outro ends close to file end — fire at the earliest outro start.
                positionMs / 1_000.0 >= outros.minOf { it.startTime }
            }
        }

        if (durationMs <= 0L) return false
        return crossesUserThreshold(thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd, positionMs, durationMs)
    }

    /**
     * Parses the release-date strings returned by upstream catalogs into a
     * [LocalDate]. Accepts ISO date, ISO instant, offset date-time, and local
     * date-time; returns `null` when nothing matches.
     */
    fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
            ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
    }

    /**
     * Returns `true` when an episode with the given release date has already
     * aired (or has no parseable date — treated as aired to avoid hiding
     * episodes with malformed metadata).
     */
    fun hasEpisodeAired(raw: String?, clock: Clock = Clock.systemDefaultZone()): Boolean {
        val aired = parseEpisodeReleaseDate(raw) ?: return true
        return !aired.isAfter(LocalDate.now(clock))
    }

    private fun userThresholdMs(
        mode: NextEpisodeThresholdMode,
        percent: Float,
        minutes: Float,
        durationMs: Long
    ): Long = when (mode) {
        NextEpisodeThresholdMode.PERCENTAGE -> {
            val clamped = percent.coerceIn(MIN_THRESHOLD_PERCENT, 100f)
            ((1.0 - clamped / 100.0) * durationMs).toLong()
        }
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
            (minutes.coerceIn(0f, 3.5f) * 60_000f).toLong()
        }
    }

    private fun crossesUserThreshold(
        mode: NextEpisodeThresholdMode,
        percent: Float,
        minutes: Float,
        positionMs: Long,
        durationMs: Long
    ): Boolean = when (mode) {
        NextEpisodeThresholdMode.PERCENTAGE -> {
            val clamped = percent.coerceIn(MIN_THRESHOLD_PERCENT, 100f)
            (positionMs.toDouble() / durationMs.toDouble()) >= (clamped / 100.0)
        }
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
            val remainingMs = durationMs - positionMs
            remainingMs <= (minutes.coerceIn(0f, 3.5f) * 60_000f).toLong()
        }
    }

    /**
     * Earliest point in a file the "Up next" prompt may fire. 97% left no room on short
     * episodes, where the credits routinely start before the last 3%.
     */
    const val MIN_THRESHOLD_PERCENT = 95f

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    const val POST_OUTRO_AUTOPLAY_GAP_MS = 5_000L

    const val END_OF_VIDEO_EPSILON_MS = 1_000L
}