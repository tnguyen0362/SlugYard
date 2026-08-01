package com.sluggyard.tv.ui.app.details

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTargetTest {

    @Test
    fun `series play targets the first unwatched episode rather than the parent show`() {
        val state = seriesState(
            DetailsEpisode("show:1:1", 1, "One", null, null, watched = true),
            DetailsEpisode("show:1:2", 2, "Two", null, null, watched = false),
        )

        assertEquals(PlaybackTarget("series", "show:1:2", 1, 2), state.playbackTarget("series", "show"))
    }

    @Test
    fun `fully watched series falls back deterministically to its first episode`() {
        val state = seriesState(
            DetailsEpisode("show:1:1", 1, "One", null, null, watched = true),
            DetailsEpisode("show:1:2", 2, "Two", null, null, watched = true),
        )

        assertEquals(PlaybackTarget("series", "show:1:1", 1, 1), state.playbackTarget("series", "show"))
    }

    @Test
    fun `movie play preserves the parent type and id`() {
        val movie = DetailsState(
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

        assertEquals(PlaybackTarget("movie", "movie"), movie.playbackTarget("movie", "movie"))
    }

    @Test
    fun `series without an episode does not query the parent show id`() {
        val state = seriesState().copy(seasons = emptyList())

        assertEquals(null, state.playbackTarget("series", "show"))
    }

    @Test
    fun `series play skips specials when a regular season exists`() {
        val special = DetailsEpisode("show:0:1", 1, "Special", null, null, watched = false)
        val regular = DetailsEpisode("show:1:1", 1, "Pilot", null, null, watched = false)
        val state = seriesState(regular).copy(
            seasons = listOf(
                DetailsSeason(0, listOf(special), fullyWatched = false),
                DetailsSeason(1, listOf(regular), fullyWatched = false),
            ),
            selectedSeason = 0,
        )

        assertEquals(PlaybackTarget("series", "show:1:1", 1, 1), state.playbackTarget("series", "show"))
    }

    @Test
    fun `series play skips watched episodes across seasons for next unwatched`() {
        val seasonOne = DetailsEpisode("show:1:1", 1, "Pilot", null, null, watched = true)
        val seasonTwo = DetailsEpisode("show:2:1", 1, "Return", null, null, watched = false)
        val state = seriesState(seasonOne).copy(
            seasons = listOf(
                DetailsSeason(1, listOf(seasonOne), fullyWatched = true),
                DetailsSeason(2, listOf(seasonTwo), fullyWatched = false),
            ),
            // Even if UI is parked on a fully-watched season, Play advances to next unwatched.
            selectedSeason = 1,
        )

        assertEquals(PlaybackTarget("series", "show:2:1", 2, 1), state.playbackTarget("series", "show"))
    }

    @Test
    fun `series play prefers earlier-season unwatched over selected later season`() {
        val seasonOne = DetailsEpisode("show:1:1", 1, "Pilot", null, null, watched = false)
        val seasonTwo = DetailsEpisode("show:2:1", 1, "Return", null, null, watched = false)
        val state = seriesState(seasonOne).copy(
            seasons = listOf(
                DetailsSeason(1, listOf(seasonOne), fullyWatched = false),
                DetailsSeason(2, listOf(seasonTwo), fullyWatched = false),
            ),
            selectedSeason = 2,
        )

        assertEquals(PlaybackTarget("series", "show:1:1", 1, 1), state.playbackTarget("series", "show"))
    }

    private fun seriesState(vararg episodes: DetailsEpisode) = DetailsState(
        id = "show",
        title = "Show",
        backdropUrl = null,
        posterUrl = null,
        metadata = "Series",
        description = "",
        genres = emptyList(),
        inLibrary = false,
        isSeries = true,
        seasons = listOf(DetailsSeason(1, episodes.toList(), fullyWatched = episodes.all { it.watched })),
    )
}
