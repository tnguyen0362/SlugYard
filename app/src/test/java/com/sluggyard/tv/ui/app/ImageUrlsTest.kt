package com.sluggyard.tv.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageUrlsTest {
    @Test
    fun preferLargePosterUrl_upgradesTraktMediumToFull_notLarge() {
        val input = "https://media.trakt.tv/images/shows/000/000/008/posters/medium/poster.jpg.webp"
        val out = preferLargePosterUrl(input)
        assertEquals(
            "https://media.trakt.tv/images/shows/000/000/008/posters/full/poster.jpg.webp",
            out,
        )
        assertFalse(out!!.contains("/large/"))
    }

    @Test
    fun preferLargePosterUrl_rewritesTmdbPosterWidth() {
        val input = "https://image.tmdb.org/t/p/w185/abc.jpg"
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            preferLargePosterUrl(input),
        )
    }

    @Test
    fun preferTvBackdropUrl_upgradesSmallTmdbTokensTo1280() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/poster.jpg",
            preferTvBackdropUrl("https://image.tmdb.org/t/p/w185/poster.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/back.jpg",
            preferTvBackdropUrl("https://image.tmdb.org/t/p/w300/back.jpg"),
        )
    }

    @Test
    fun preferCardBackdropUrl_uses780Not1280() {
        assertEquals(
            "https://image.tmdb.org/t/p/w780/back.jpg",
            preferCardBackdropUrl("https://image.tmdb.org/t/p/w300/back.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/back.jpg",
            preferCardBackdropUrl("https://image.tmdb.org/t/p/w1280/back.jpg"),
        )
    }
}
