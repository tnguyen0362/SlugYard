package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.data.local.NextEpisodeThresholdMode
import com.sluggyard.tv.data.repository.SkipInterval
import com.sluggyard.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextEpisodeRulesTest {

    private fun ep(season: Int?, episode: Int?, id: String = "s${season}e${episode}") =
        Video(
            id = id,
            title = id,
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null
        )

    private fun outro(startSec: Double, endSec: Double, type: String = "outro") =
        SkipInterval(startTime = startSec, endTime = endSec, type = type, provider = "test")

    @Test
    fun `advances to next episode in same season`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s1e3", next?.id)
    }

    @Test
    fun `crosses into next season after the season finale`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(2, 1))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s2e1", next?.id)
    }

    @Test
    fun `returns null after the very last episode`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertNull(next)
    }

    @Test
    fun `returns null when the current episode is not in the list`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 3, currentEpisode = 9)
        assertNull(next)
    }

    @Test
    fun `absolute numbering advances by episode when the caller has no season`() {
        val videos = listOf(ep(1, 5), ep(1, 6), ep(1, 7))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("s1e7", next?.id)
    }

    @Test
    fun `absolute numbering advances when the meta videos also lack a season`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"), ep(null, 7, "e7"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("e7", next?.id)
    }

    @Test
    fun `absolute numbering returns null after the last episode`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertNull(next)
    }

    @Test
    fun `video id match wins when player season-local number disagrees with absolute meta`() {
        // Meta S2 uses absolute 13/21; player was launched with :2:9 / episode=9.
        val videos = listOf(
            ep(2, 13, "tt1:2:1"),
            ep(2, 21, "tt1:2:9"),
            ep(2, 22, "tt1:2:10"),
        )
        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 2,
            currentEpisode = 9,
            currentVideoId = "tt1:2:9",
        )
        assertEquals("tt1:2:10", next?.id)
    }

    @Test
    fun `id-encoded season episode finds current without currentVideoId exact match`() {
        val videos = listOf(
            ep(2, 13, "tt1:2:1"),
            ep(2, 21, "tt1:2:9"),
            ep(2, 22, "tt1:2:10"),
        )
        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 2,
            currentEpisode = 9,
        )
        assertEquals("tt1:2:10", next?.id)
    }

    @Test
    fun `up next surfaces at outro start even when a long post-credits tail remains`() {
        val durationMs = 24 * 60_000L
        val outroStartMs = 20 * 60_000L
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = outroStartMs,
                durationMs = durationMs,
                skipIntervals = listOf(outro(startSec = 20 * 60.0, endSec = 21.5 * 60.0)),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 95f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = outroStartMs - 5_000L,
                durationMs = durationMs,
                skipIntervals = listOf(outro(startSec = 20 * 60.0, endSec = 21.5 * 60.0)),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 95f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
    }

    @Test
    fun `credits label is treated as an outro trigger`() {
        val durationMs = 30 * 60_000L
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 28 * 60_000L,
                durationMs = durationMs,
                skipIntervals = listOf(outro(startSec = 27 * 60.0, endSec = 30 * 60.0, type = "credits")),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 95f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
    }

    @Test
    fun `without outro data percent threshold still applies`() {
        val durationMs = 20 * 60_000L
        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = (durationMs * 0.85).toLong(),
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 90f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = (durationMs * 0.91).toLong(),
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 90f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
    }

    @Test
    fun `without outro data remaining-time floor surfaces up next before late percent`() {
        // 60 min file → 8% floor clamps to 180s max; 95% is at 57:00.
        val durationMs = 60 * 60_000L
        val nearEndMs = durationMs - 150_000L
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = nearEndMs,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 95f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = durationMs - 200_000L,
                durationMs = durationMs,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                // Keep percent gate above this playhead so only the remaining-time floor is tested.
                thresholdPercent = 99f,
                thresholdMinutesBeforeEnd = 2f,
            )
        )
    }
}
