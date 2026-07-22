package com.sluggyard.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaRepositoryImplRulesTest {

    // --- splitAddonBaseUrl ---

    @Test
    fun `splitAddonBaseUrl trims trailing slash and separates query`() {
        val (path, query) = splitAddonBaseUrl("https://example.com/addon/?foo=bar")
        assertEquals("https://example.com/addon", path)
        assertEquals("?foo=bar", query)
    }

    @Test
    fun `splitAddonBaseUrl returns empty query when none present`() {
        val (path, query) = splitAddonBaseUrl("https://example.com/addon/")
        assertEquals("https://example.com/addon", path)
        assertEquals("", query)
    }

    // --- addonMetaCacheKey ---

    @Test
    fun `addonMetaCacheKey combines normalized base url with type and id`() {
        assertEquals(
            "https://example.com/addon|movie:tt1",
            addonMetaCacheKey("https://example.com/addon/", "movie", "tt1")
        )
    }

    // --- buildMetaUrl ---

    @Test
    fun `buildMetaUrl builds a meta endpoint path with url-encoded segments`() {
        assertEquals(
            "https://example.com/addon/meta/movie/tt%201.json",
            buildMetaUrl("https://example.com/addon/", "movie", "tt 1")
        )
    }

    @Test
    fun `buildMetaUrl preserves query string after the json suffix`() {
        assertEquals(
            "https://example.com/addon/meta/movie/tt1.json?foo=bar",
            buildMetaUrl("https://example.com/addon?foo=bar", "movie", "tt1")
        )
    }

    // --- encodePathSegment ---

    @Test
    fun `encodePathSegment encodes spaces as percent-20`() {
        assertEquals("a%20b", encodePathSegment("a b"))
    }
}
