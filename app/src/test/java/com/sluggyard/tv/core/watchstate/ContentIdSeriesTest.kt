package com.sluggyard.tv.core.watchstate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentIdSeriesTest {
    @Test
    fun `strips imdb episode suffix to show id`() {
        assertEquals("tt39304754", seriesContentId("tt39304754:1:3"))
        assertEquals("tt39304754", seriesContentId("tt39304754:2:9"))
        assertEquals("tt39304754", seriesContentId("tt39304754"))
    }

    @Test
    fun `strips kitsu episode suffix`() {
        assertEquals("kitsu:45823", seriesContentId("kitsu:45823:1:12"))
    }

    @Test
    fun `leaves non episode shaped ids alone`() {
        assertEquals("tmdb:123", seriesContentId("tmdb:123"))
        assertEquals("movie-1", seriesContentId("movie-1"))
    }

    @Test
    fun `parses season episode from video id`() {
        assertEquals(1 to 3, parseSeasonEpisodeFromVideoId("tt39304754:1:3"))
        assertEquals(2 to 9, parseSeasonEpisodeFromVideoId("kitsu:1:2:9"))
        assertNull(parseSeasonEpisodeFromVideoId("tt39304754"))
    }
}
