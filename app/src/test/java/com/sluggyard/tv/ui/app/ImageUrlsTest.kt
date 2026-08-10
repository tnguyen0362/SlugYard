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
    fun preferLargePosterUrl_rewritesTmdbPosterFor4kCatalogCards() {
        val input = "https://image.tmdb.org/t/p/w185/abc.jpg"
        assertEquals(
            "https://image.tmdb.org/t/p/w780/abc.jpg",
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
    fun preferCardBackdropUrl_uses1280ForFocused4kCards() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/back.jpg",
            preferCardBackdropUrl("https://image.tmdb.org/t/p/w300/back.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/back.jpg",
            preferCardBackdropUrl("https://image.tmdb.org/t/p/w1280/back.jpg"),
        )
    }

    @Test
    fun preferCardBackdropUrl_keepsMetahubFullHdMedium_notSoftSmall() {
        // Metahub medium/large/original are the same ~1080p file; small is soft 480px bandaid.
        // Cards keep medium quality; Coil decode size handles TV layout width.
        assertEquals(
            "https://images.metahub.space/background/medium/tt0111161/img",
            preferCardBackdropUrl("https://images.metahub.space/background/medium/tt0111161/img"),
        )
        assertEquals(
            "https://images.metahub.space/background/medium/tt0111161/img",
            preferCardBackdropUrl("https://images.metahub.space/background/large/tt0111161/img"),
        )
        assertEquals(
            "https://images.metahub.space/background/medium/tt0111161/img",
            preferCardBackdropUrl("https://images.metahub.space/background/original/tt0111161/img"),
        )
        assertEquals(
            "https://images.metahub.space/background/medium/tt0111161/img",
            preferCardBackdropUrl("https://images.metahub.space/background/small/tt0111161/img"),
        )
        assertEquals(
            "https://images.metahub.space/fanart/medium/tt0111161/img",
            preferCardBackdropUrl("https://images.metahub.space/fanart/small/tt0111161/img"),
        )
    }

    @Test
    fun preferCardBackdropUrl_traktUsesFullForFocused4kCards() {
        assertEquals(
            "https://media.trakt.tv/images/movies/000/000/001/fanarts/full/file.jpg.webp",
            preferCardBackdropUrl(
                "https://media.trakt.tv/images/movies/000/000/001/fanarts/full/file.jpg.webp",
            ),
        )
    }
}
