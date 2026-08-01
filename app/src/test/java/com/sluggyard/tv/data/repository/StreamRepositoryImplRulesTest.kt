package com.sluggyard.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRepositoryImplRulesTest {

    // --- normalizeTmdbPluginType ---

    @Test
    fun `normalizeTmdbPluginType maps series-like aliases to tv`() {
        assertEquals("tv", normalizeTmdbPluginType("series"))
        assertEquals("tv", normalizeTmdbPluginType("TV"))
        assertEquals("tv", normalizeTmdbPluginType("Show"))
    }

    @Test
    fun `normalizeTmdbPluginType lowercases other types unchanged`() {
        assertEquals("movie", normalizeTmdbPluginType("Movie"))
    }

    // --- cleanKitsuPluginId ---

    @Test
    fun `cleanKitsuPluginId strips trailing numeric episode segment`() {
        assertEquals("kitsu:12345", cleanKitsuPluginId("kitsu:12345:7"))
    }

    @Test
    fun `cleanKitsuPluginId leaves id unchanged when no trailing numeric segment`() {
        assertEquals("kitsu:12345", cleanKitsuPluginId("kitsu:12345"))
    }

    @Test
    fun `cleanKitsuPluginId leaves id unchanged when trailing segment is not numeric`() {
        assertEquals("kitsu:12345:abc", cleanKitsuPluginId("kitsu:12345:abc"))
    }

    // --- canRunLocalPlugins ---

    @Test
    fun `canRunLocalPlugins recognizes kitsu anilist and mal prefixes case-insensitively`() {
        assertTrue("kitsu:123".canRunLocalPlugins())
        assertTrue("ANILIST:123".canRunLocalPlugins())
        assertTrue("mal:123".canRunLocalPlugins())
    }

    @Test
    fun `canRunLocalPlugins rejects other id schemes`() {
        assertFalse("tt1234567".canRunLocalPlugins())
    }

    // --- parseQualityValue ---

    @Test
    fun `parseQualityValue maps known quality labels to resolution values`() {
        assertEquals(2160, parseQualityValue("4K"))
        assertEquals(2160, parseQualityValue("2160p"))
        assertEquals(1080, parseQualityValue("1080p"))
        assertEquals(720, parseQualityValue("720p HDR"))
        assertEquals(480, parseQualityValue("480p"))
        assertEquals(360, parseQualityValue("360p"))
    }

    @Test
    fun `parseQualityValue returns -1 for null or unrecognized quality`() {
        assertEquals(-1, parseQualityValue(null))
        assertEquals(-1, parseQualityValue("unknown"))
    }
}
