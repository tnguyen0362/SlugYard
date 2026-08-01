package com.sluggyard.tv.ui.app.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DigitalReleaseLookupIdTest {
    @Test
    fun parsesTmdbPrefixedIds() {
        assertEquals(969681, DigitalReleaseLookup.parseTmdbId("tmdb:969681"))
        assertEquals(969681, DigitalReleaseLookup.parseTmdbId("TMDB:969681:extra"))
        assertNull(DigitalReleaseLookup.parseTmdbId("tt1234567"))
        assertNull(DigitalReleaseLookup.parseTmdbId("969681"))
    }

    @Test
    fun normalizesImdbIds() {
        assertEquals("tt1234567", DigitalReleaseLookup.normalizeImdbId("tt1234567"))
        assertEquals("tt1234567", DigitalReleaseLookup.normalizeImdbId("tt1234567:1:2"))
        assertNull(DigitalReleaseLookup.normalizeImdbId("tmdb:969681"))
    }

    @Test
    fun cacheKeyPrefersTmdbThenImdb() {
        assertEquals("tmdb:969681", DigitalReleaseLookup.cacheKey("tmdb:969681"))
        assertEquals("tt1234567", DigitalReleaseLookup.cacheKey("tt1234567"))
        assertNull(DigitalReleaseLookup.cacheKey("movie/foo"))
    }
}
