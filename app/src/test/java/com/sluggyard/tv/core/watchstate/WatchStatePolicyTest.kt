package com.sluggyard.tv.core.watchstate

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStatePolicyTest {

    @Test
    fun `two and ninety percent are the inclusive global boundaries`() {
        assertFalse(WatchStatePolicy.isStartedButIncomplete(0.019))
        assertTrue(WatchStatePolicy.isStartedButIncomplete(0.02))
        assertTrue(WatchStatePolicy.isStartedButIncomplete(0.899))
        assertFalse(WatchStatePolicy.isStartedButIncomplete(0.90))
        assertTrue(WatchStatePolicy.isCompleted(0.90))
    }

    @Test
    fun `an opaque but direct saved position can resume while passive history cannot`() {
        assertTrue(
            WatchStatePolicy.shouldResumeMovie(
                MovieResumeEvidence(savedPositionMs = 1_000, durationMs = null),
            ),
        )
        assertFalse(
            WatchStatePolicy.shouldResumeMovie(
                MovieResumeEvidence(savedPositionMs = 1_000, passiveRemoteHistoryOnly = true),
            ),
        )
    }

    @Test
    fun `manual watched action beats available resume evidence`() {
        assertFalse(
            WatchStatePolicy.shouldResumeMovie(
                MovieResumeEvidence(
                    savedPositionMs = 5_000,
                    durationMs = 10_000,
                    manuallyMarkedWatched = true,
                ),
            ),
        )
    }

    @Test
    fun `future first episode removes that entire season but not another season`() {
        val today = LocalDate.of(2026, 7, 18)
        val episodes = listOf(
            EpisodeAvailability(1, 1, releaseDate = today.minusDays(1)),
            EpisodeAvailability(1, 2, releaseDate = today.plusDays(5)),
            EpisodeAvailability(2, 1, releaseDate = today.plusDays(1)),
            EpisodeAvailability(2, 2, releaseDate = today.minusDays(1)),
        )

        val watchable = WatchStatePolicy.watchableEpisodes(episodes, today)

        assertTrue(watchable.all { it.season == 1 })
    }

    @Test
    fun `missing release date stays available`() {
        val episodes = listOf(EpisodeAvailability(1, 1, releaseDate = null))

        assertTrue(WatchStatePolicy.watchableEpisodes(episodes, LocalDate.now()).isNotEmpty())
    }
}
