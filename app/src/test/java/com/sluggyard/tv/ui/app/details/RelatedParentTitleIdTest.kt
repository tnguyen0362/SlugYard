package com.sluggyard.tv.ui.app.details

import org.junit.Assert.assertEquals
import org.junit.Test

class RelatedParentTitleIdTest {
    @Test
    fun stripsEpisodeSuffixFromImdb() {
        assertEquals("tt0903747", relatedParentTitleId("tt0903747:1:1"))
    }

    @Test
    fun keepsBareImdb() {
        assertEquals("tt0903747", relatedParentTitleId("tt0903747"))
    }

    @Test
    fun keepsTmdbPrefixWithoutExtraSegments() {
        assertEquals("tmdb:1396", relatedParentTitleId("tmdb:1396"))
    }

    @Test
    fun stripsTmdbExtraPath() {
        assertEquals("tmdb:1396", relatedParentTitleId("tmdb:1396:extra"))
    }

    @Test
    fun keepsTraktPrefix() {
        assertEquals("trakt:42", relatedParentTitleId("trakt:42"))
    }
}
