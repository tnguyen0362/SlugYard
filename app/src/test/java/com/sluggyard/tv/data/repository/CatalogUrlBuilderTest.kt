package com.sluggyard.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogUrlBuilderTest {

    @Test
    fun `skip=0 with no extra args produces the base catalog path`() {
        val url = CatalogUrlBuilder.build(
            baseUrl = "https://torrentio.strem.fun",
            type = "movie",
            catalogId = "top",
            skip = 0,
            extraArgs = emptyMap()
        )

        assertEquals("https://torrentio.strem.fun/catalog/movie/top.json", url)
    }

    @Test
    fun `skip greater than zero appends a skip segment`() {
        val url = CatalogUrlBuilder.build(
            baseUrl = "https://torrentio.strem.fun",
            type = "movie",
            catalogId = "top",
            skip = 100,
            extraArgs = emptyMap()
        )

        assertEquals("https://torrentio.strem.fun/catalog/movie/top/skip=100.json", url)
    }

    @Test
    fun `extra args are encoded and skip is folded in when missing`() {
        val url = CatalogUrlBuilder.build(
            baseUrl = "https://mediafusion.example",
            type = "movie",
            catalogId = "top",
            skip = 50,
            extraArgs = mapOf("genre" to "Sci-Fi & Fantasy")
        )

        assertEquals(
            "https://mediafusion.example/catalog/movie/top/genre=Sci-Fi%20%26%20Fantasy&skip=50.json",
            url
        )
    }

    @Test
    fun `an explicit skip in extraArgs is not overridden by the skip parameter`() {
        val url = CatalogUrlBuilder.build(
            baseUrl = "https://addon.example",
            type = "movie",
            catalogId = "top",
            skip = 50,
            extraArgs = mapOf("skip" to "999")
        )

        assertEquals("https://addon.example/catalog/movie/top/skip=999.json", url)
    }

    @Test
    fun `existing query string on the base url is preserved after the catalog path`() {
        val url = CatalogUrlBuilder.build(
            baseUrl = "https://addon.example/cfg/?token=abc",
            type = "movie",
            catalogId = "top",
            skip = 0,
            extraArgs = emptyMap()
        )

        assertEquals("https://addon.example/cfg/catalog/movie/top.json?token=abc", url)
    }
}
