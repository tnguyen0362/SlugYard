package com.sluggyard.tv.core.watchstate

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsWatchPolicyTest {

    @Test
    fun `marking later episode watched also marks earlier episodes in its season`() {
        val result = DetailsWatchPolicy.markEpisodeWatched(
            listOf(episode("a", 2, 1), episode("b", 2, 2), episode("c", 2, 3)),
            "c",
        )

        assertTrue(result.all(DetailsEpisode::watched))
    }

    @Test
    fun `marking a season watched marks prior seasons too`() {
        val result = DetailsWatchPolicy.markSeasonWatched(
            listOf(episode("s1", 1, 1), episode("s2", 2, 1), episode("s3", 3, 1)),
            targetSeason = 2,
        )

        assertEquals(listOf(true, true, false), result.map(DetailsEpisode::watched))
    }

    @Test
    fun `marking an episode watched also marks every earlier season`() {
        val result = DetailsWatchPolicy.markEpisodeWatched(
            listOf(
                episode("s1e1", 1, 1), episode("s1e2", 1, 2),
                episode("s2e1", 2, 1), episode("s2e2", 2, 2), episode("s2e3", 2, 3),
                episode("s3e1", 3, 1),
            ),
            "s2e2",
        )

        assertEquals(
            listOf(true, true, true, true, false, false),
            result.map(DetailsEpisode::watched),
        )
    }

    @Test
    fun `specials are not swept up by a main episode and cascade only among themselves`() {
        val episodes = listOf(
            episode("sp1", 0, 1), episode("sp2", 0, 2),
            episode("s1e1", 1, 1), episode("s1e2", 1, 2),
        )

        val main = DetailsWatchPolicy.markEpisodeWatched(episodes, "s1e2")
        assertEquals(listOf(false, false, true, true), main.map(DetailsEpisode::watched))

        val special = DetailsWatchPolicy.markEpisodeWatched(episodes, "sp2")
        assertEquals(listOf(true, true, false, false), special.map(DetailsEpisode::watched))

        val season = DetailsWatchPolicy.markSeasonWatched(episodes, targetSeason = 1)
        assertEquals(listOf(false, false, true, true), season.map(DetailsEpisode::watched))
    }

    @Test
    fun `newlyWatched returns only the episodes that flipped`() {
        val before = listOf(episode("a", 1, 1, watched = true), episode("b", 1, 2), episode("c", 1, 3))
        val after = DetailsWatchPolicy.markEpisodeWatched(before, "c")

        assertEquals(listOf("b", "c"), DetailsWatchPolicy.newlyWatched(before, after).map { it.id })
    }

    @Test
    fun `future first episode makes season irrelevant to unified complete status`() {
        val today = LocalDate.of(2026, 7, 18)
        val episodes = listOf(
            episode("s1e1", 1, 1, watched = true),
            episode("s1e2", 1, 2, watched = true),
            episode("s2e1", 2, 1, watched = false, releaseDate = today.plusDays(1)),
        )

        assertTrue(DetailsWatchPolicy.isSeasonFullyWatched(episodes, 1, today))
        assertFalse(DetailsWatchPolicy.isSeasonFullyWatched(episodes, 2, today))
    }

    private fun episode(
        id: String,
        season: Int,
        episode: Int,
        watched: Boolean = false,
        releaseDate: LocalDate? = null,
    ) = DetailsEpisode(id, season, episode, watched, releaseDate)
}
