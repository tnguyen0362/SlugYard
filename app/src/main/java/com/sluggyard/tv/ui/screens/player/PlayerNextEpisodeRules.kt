package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.core.watchstate.parseSeasonEpisodeFromVideoId
import com.sluggyard.tv.core.watchstate.seasonEpisodeLooksSeriesAbsolute
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
     * Returns the episode that follows the current one, or `null` when there is no successor.
     *
     * Prefer [currentVideoId] (Stremio `show:S:E`) over meta `episode` numbers so season-local
     * player numbering still finds absolute-numbered anime metas (S2E9 vs meta 21).
     */
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int?,
        currentEpisode: Int,
        currentVideoId: String? = null,
    ): Video? {
        val ordered = orderedVideos(videos)
        if (ordered.isEmpty()) return null

        indexOfCurrentEpisode(ordered, currentSeason, currentEpisode, currentVideoId)
            ?.let { idx -> return ordered.getOrNull(idx + 1) }

        return null
    }

    /**
     * Decides whether the "Next episode" card should be visible at [positionMs].
     *
     * When outro / ending / credits skip-intervals are available, the card fires at
     * the earliest credits start so it arrives with (and then replaces) Skip outro —
     * Netflix-style up-next, not a late percentage-only card while ED is already up.
     * Without outro data the user threshold is applied, plus a remaining-time floor so
     * titles missing IntroDB/AniSkip still surface Up next before the file ends.
     */
    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        val outros = skipIntervals.filter { isOutroLikeType(it.type) }

        if (outros.isNotEmpty()) {
            // Duration is not required for the outro-start path: skip intervals are already
            // absolute seconds into the playback.
            if (positionMs / 1_000.0 >= outros.minOf { it.startTime }) return true
            // Still allow an earlier user threshold if the viewer set a low one and there
            // is known duration (edge case for very late outros with a low %).
            if (durationMs > 0L &&
                crossesUserThreshold(
                    thresholdMode,
                    thresholdPercent,
                    thresholdMinutesBeforeEnd,
                    positionMs,
                    durationMs,
                )
            ) {
                return true
            }
            return false
        }

        if (durationMs <= 0L) return false
        if (crossesUserThreshold(thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd, positionMs, durationMs)) {
            return true
        }
        // No IntroDB/AniSkip outro: still guarantee Up next before the file ends.
        // Use the larger of a fixed floor and a short percentage of runtime so both
        // short anime and long dramas get a visible window.
        val remainingMs = durationMs - positionMs
        val dynamicFloorMs = (durationMs * NO_OUTRO_REMAINING_FRACTION)
            .toLong()
            .coerceIn(NO_OUTRO_REMAINING_FALLBACK_MS, NO_OUTRO_REMAINING_FALLBACK_MAX_MS)
        return remainingMs in 0L..dynamicFloorMs
    }

    /** True for ending/credits segment labels from IntroDB / AniSkip / libass chapters. */
    fun isOutroLikeType(type: String): Boolean {
        val normalized = type.trim().lowercase()
        if (normalized in OUTRO_SEGMENT_TYPES) return true
        return "outro" in normalized ||
            "ending" in normalized ||
            "credits" in normalized ||
            normalized == "ed" ||
            "mixed-ed" in normalized
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

    private fun orderedVideos(videos: List<Video>): List<Video> =
        videos
            .filter { it.episode != null }
            .sortedWith(
                compareBy<Video> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE },
            )

    private fun indexOfCurrentEpisode(
        ordered: List<Video>,
        currentSeason: Int?,
        currentEpisode: Int,
        currentVideoId: String?,
    ): Int? {
        val videoId = currentVideoId?.trim()?.takeIf { it.isNotEmpty() }
        if (videoId != null) {
            val byId = ordered.indexOfFirst { it.id == videoId }
            if (byId >= 0) return byId
        }

        if (currentSeason == null) {
            val byEpisode = ordered.indexOfFirst { it.episode == currentEpisode }
            return byEpisode.takeIf { it >= 0 }
        }

        val byMeta = ordered.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (byMeta >= 0) return byMeta

        // Player often carries season-local numbers from `:S:E` while meta.episode is absolute.
        val byIdEpisode = ordered.indexOfFirst { video ->
            val parsed = parseSeasonEpisodeFromVideoId(video.id) ?: return@indexOfFirst false
            parsed.first == currentSeason && parsed.second == currentEpisode
        }
        if (byIdEpisode >= 0) return byIdEpisode

        val seasonEps = ordered.filter { it.season == currentSeason }
        if (seasonEpisodeLooksSeriesAbsolute(seasonEps.mapNotNull { it.episode }) &&
            currentEpisode in 1..seasonEps.size
        ) {
            val target = seasonEps[currentEpisode - 1]
            return ordered.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }
        }

        return null
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
     * Earliest selectable percent threshold. Credits on short eps often start before 97%.
     */
    const val MIN_THRESHOLD_PERCENT = 90f

    /**
     * When IntroDB/AniSkip has no outro, open Up next at least this far from the end.
     */
    const val NO_OUTRO_REMAINING_FALLBACK_MS = 120_000L

    /** Cap for the duration-relative no-outro floor (long movies / episodes). */
    const val NO_OUTRO_REMAINING_FALLBACK_MAX_MS = 180_000L

    /** Share of runtime used for the no-outro floor (clamped to min/max above). */
    const val NO_OUTRO_REMAINING_FRACTION = 0.08

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed", "credits", "ending")

    const val POST_OUTRO_AUTOPLAY_GAP_MS = 5_000L

    const val END_OF_VIDEO_EPSILON_MS = 1_000L
}
