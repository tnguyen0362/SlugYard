package com.sluggyard.tv.ui.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewriteContentTypeLabelsTest {
    @Test
    fun detailsTarget_prefersParentIdForEpisodes() {
        val (id, type) = rewriteDetailsTarget(
            contentId = "tt0944947:1:1",
            contentType = "series",
            parentId = "tt0944947",
            parentType = "series",
            season = 1,
            episode = 1,
        )
        assertEquals("tt0944947", id)
        assertEquals("series", type)
    }

    @Test
    fun detailsTarget_infersSeriesIdFromStremioEpisodeId() {
        val (id, type) = rewriteDetailsTarget(
            contentId = "tt0944947:2:3",
            contentType = "series",
            parentId = null,
            parentType = null,
            season = 2,
            episode = 3,
        )
        assertEquals("tt0944947", id)
        assertEquals("series", type)
    }

    @Test
    fun detailsTarget_keepsMovieId() {
        val (id, type) = rewriteDetailsTarget(
            contentId = "tt0133093",
            contentType = "movie",
            parentId = null,
            parentType = null,
            season = null,
            episode = null,
        )
        assertEquals("tt0133093", id)
        assertEquals("movie", type)
    }

    @Test
    fun inferSeriesId_requiresMatchingSeasonEpisodeSuffix() {
        assertNull(inferSeriesIdFromEpisodeContentId("tt0944947:1:1", season = 2, episode = 1))
        assertEquals("tt0944947", inferSeriesIdFromEpisodeContentId("tt0944947:1:1", season = 1, episode = 1))
    }

    @Test
    fun posterDedupeKey_separatesMovieAndSeries() {
        assertEquals("movie:tt123", rewritePosterDedupeKey("movie", "tt123"))
        assertEquals("series:tt123", rewritePosterDedupeKey("series", "tt123"))
    }
}
