package com.sluggyard.tv.ui.app.home

import com.sluggyard.tv.ui.app.data.PlaybackCheckpoint
import com.sluggyard.tv.ui.app.data.SeriesCompletionSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingTmdbEnrichmentTest {
    @Test
    fun `completion seeds alone produce tt show art targets without midstream`() {
        val targets = buildContinueWatchingArtTargets(
            checkpoints = emptyList(),
            completionSeeds = listOf(
                SeriesCompletionSeed(
                    seriesKey = "tt19064770",
                    showId = "tt19064770",
                    season = 2,
                    episode = 8,
                    title = "Angel",
                    posterUrl = null,
                    watchedAtMs = 1L,
                ),
            ),
        )
        assertEquals(1, targets.size)
        assertEquals("tt19064770", targets.single().imdbId)
        assertTrue(targets.single().tileKeys.contains("tt19064770"))
    }

    @Test
    fun `episode checkpoint keys include parent and imdb so next-up can map`() {
        val cp = PlaybackCheckpoint(
            contentId = "tt19064770:2:9",
            contentType = "series",
            title = "Angel",
            positionMs = 100_000L,
            durationMs = 1_000_000L,
            updatedAtEpochMs = 1L,
            season = 2,
            episode = 9,
            parentId = "tt19064770",
            parentType = "series",
        )
        val targets = buildContinueWatchingArtTargets(
            checkpoints = listOf(cp),
            completionSeeds = emptyList(),
        )
        val t = targets.single()
        assertEquals("tt19064770", t.imdbId)
        assertTrue(t.tileKeys.contains("tt19064770:2:9"))
        assertTrue(t.tileKeys.contains("tt19064770"))
    }

    @Test
    fun `withContinueWatchingPosters fills showId next-up hole from imdb key`() {
        val state = HomeState(
            rows = listOf(
                HomeRow(
                    id = "continue_watching",
                    title = "Continue Watching",
                    posters = listOf(
                        HomePoster(
                            id = "tt19064770",
                            title = "Angel",
                            imageUrl = null,
                            contentType = "series",
                            parentId = "tt19064770",
                            season = 2,
                            episode = 9,
                            nextUp = true,
                        ),
                    ),
                ),
            ),
        )
        val filled = state.withContinueWatchingPosters(
            mapOf("tt19064770" to "https://image.tmdb.org/t/p/w500/x.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/x.jpg",
            filled.rows.single().posters.single().imageUrl,
        )
    }
}
