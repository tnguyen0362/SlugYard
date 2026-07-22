package com.sluggyard.tv.ui.rewrite.details

import org.junit.Assert.assertEquals
import org.junit.Test

class RewritePlaybackTargetTest {

    @Test
    fun `series play targets the first unwatched episode rather than the parent show`() {
        val state = seriesState(
            RewriteDetailsEpisode("show:1:1", 1, "One", null, null, watched = true),
            RewriteDetailsEpisode("show:1:2", 2, "Two", null, null, watched = false),
        )

        assertEquals(RewritePlaybackTarget("series", "show:1:2"), state.playbackTarget("series", "show"))
    }

    @Test
    fun `fully watched series falls back deterministically to its first episode`() {
        val state = seriesState(
            RewriteDetailsEpisode("show:1:1", 1, "One", null, null, watched = true),
            RewriteDetailsEpisode("show:1:2", 2, "Two", null, null, watched = true),
        )

        assertEquals(RewritePlaybackTarget("series", "show:1:1"), state.playbackTarget("series", "show"))
    }

    @Test
    fun `movie play preserves the parent type and id`() {
        val movie = RewriteDetailsState(
            id = "movie",
            title = "Movie",
            backdropUrl = null,
            posterUrl = null,
            metadata = "Movie",
            description = "",
            genres = emptyList(),
            inLibrary = false,
            isSeries = false,
        )

        assertEquals(RewritePlaybackTarget("movie", "movie"), movie.playbackTarget("movie", "movie"))
    }

    private fun seriesState(vararg episodes: RewriteDetailsEpisode) = RewriteDetailsState(
        id = "show",
        title = "Show",
        backdropUrl = null,
        posterUrl = null,
        metadata = "Series",
        description = "",
        genres = emptyList(),
        inLibrary = false,
        isSeries = true,
        seasons = listOf(RewriteDetailsSeason(1, episodes.toList(), fullyWatched = episodes.all { it.watched })),
    )
}
