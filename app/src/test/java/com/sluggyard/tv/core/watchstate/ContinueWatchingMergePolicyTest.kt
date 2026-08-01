package com.sluggyard.tv.core.watchstate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMergePolicyTest {
    @Test
    fun `active trakt wins representable content while local-only ids survive`() {
        val local = listOf(entry("movie", ProgressOrigin.LOCAL, 10L), entry("anime", ProgressOrigin.LOCAL, 30L, representable = false))
        val remote = listOf(entry("movie", ProgressOrigin.TRAKT, 20L))

        assertEquals(listOf("anime", "movie"), ContinueWatchingMergePolicy.mergeEntries(local, remote, traktActive = true).map { it.contentId })
    }

    @Test
    fun `furthest option changes episode seed tie break`() {
        val recent = seed(1, 8, 100L)
        val furthest = seed(2, 1, 50L)

        assertEquals(recent, ContinueWatchingMergePolicy.selectEpisodeSeed(listOf(recent, furthest), resumeFromFurthest = false))
        assertEquals(furthest, ContinueWatchingMergePolicy.selectEpisodeSeed(listOf(recent, furthest), resumeFromFurthest = true))
    }

    @Test
    fun `passive seed needs 95 percent completion`() {
        assertEquals(null, ContinueWatchingMergePolicy.selectEpisodeSeed(listOf(seed(1, 2, 3L, 0.94, ProgressOrigin.PASSIVE_TRAKT)), false))
        assertTrue(ContinueWatchingMergePolicy.selectEpisodeSeed(listOf(seed(1, 2, 3L, 0.95, ProgressOrigin.PASSIVE_TRAKT)), false) != null)
    }

    private fun entry(id: String, origin: ProgressOrigin, time: Long, representable: Boolean = true) = ContinueWatchingEntry(
        contentId = id, contentType = "movie", progressFraction = 0.5, lastActivityMs = time, origin = origin, traktRepresentable = representable,
    )

    private fun seed(season: Int, episode: Int, time: Long, progress: Double = 1.0, origin: ProgressOrigin = ProgressOrigin.TRAKT) = EpisodeResumeSeed(
        seriesId = "series", season = season, episode = episode, watchedAtMs = time, progressFraction = progress, origin = origin,
    )
}
