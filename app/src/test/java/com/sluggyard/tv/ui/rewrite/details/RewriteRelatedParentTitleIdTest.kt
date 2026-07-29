package com.sluggyard.tv.ui.rewrite.details

import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteRelatedParentTitleIdTest {
    @Test
    fun stripsEpisodeSuffixFromImdb() {
        assertEquals("tt0903747", rewriteRelatedParentTitleId("tt0903747:1:1"))
    }

    @Test
    fun keepsBareImdb() {
        assertEquals("tt0903747", rewriteRelatedParentTitleId("tt0903747"))
    }

    @Test
    fun keepsTmdbPrefixWithoutExtraSegments() {
        assertEquals("tmdb:1396", rewriteRelatedParentTitleId("tmdb:1396"))
    }

    @Test
    fun stripsTmdbExtraPath() {
        assertEquals("tmdb:1396", rewriteRelatedParentTitleId("tmdb:1396:extra"))
    }

    @Test
    fun keepsTraktPrefix() {
        assertEquals("trakt:42", rewriteRelatedParentTitleId("trakt:42"))
    }
}
