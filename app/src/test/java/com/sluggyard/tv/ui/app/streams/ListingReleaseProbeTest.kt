package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingReleaseProbeTest {

    @Test
    fun `MediaInfo audio and ass sections beat bare title`() {
        val blob = """
            [Group] Show - 01 [1080p]
            Audio: Japanese / English
            Subtitles: English (ASS), Signs (ASS)
            Episode 01.mkv
            Episode 01.eng.ass
        """.trimIndent()
        val (soft, dual) = ListingReleaseProbe.inferTrackFits(blob, preferredSubtitleLanguage = "en", animeLike = true)
        assertEquals(1, dual)
        assertEquals(5, soft)
    }

    @Test
    fun `companion eng ass path alone is softsub`() {
        val blob = "Show.S01E01.1080p/Subs/English.ass"
        val (soft, dual) = ListingReleaseProbe.inferTrackFits(blob, preferredSubtitleLanguage = "en", animeLike = true)
        assertEquals(0, dual)
        assertTrue(soft >= 3)
    }

    @Test
    fun `does not invent signals on empty marketing fluff`() {
        val (soft, dual) = ListingReleaseProbe.inferTrackFits(
            "Cached on TorBox ⚡ 12 GB",
            preferredSubtitleLanguage = "en",
            animeLike = true,
        )
        assertEquals(1, soft)
        assertEquals(0, dual)
    }

    @Test
    fun `rank prefers mediainfo dual ass over eng-tagged pgs mono when probe fills dual`() {
        val size = 500L * 1024 * 1024
        val pgs = StreamCandidate(
            id = "pgs",
            title = "Show 01 1080p Eng PGS English Softsubs",
            sourceLabel = "Torrentio",
            detailLabel = null,
            cacheState = StreamCacheState.CACHED,
            infoHash = "aaaa",
            seeders = 200,
            videoSizeBytes = size,
        )
        val dual = StreamCandidate(
            id = "dual",
            title = "Show 01 1080p",
            sourceLabel = "AIOStreams",
            detailLabel = "SeaDex",
            cacheState = StreamCacheState.CACHED,
            infoHash = "bbbb",
            seeders = 40,
            videoSizeBytes = size,
            streamDescription = """
                Audio: Japanese / English
                Subtitles: English ASS
            """.trimIndent(),
        )
        val ctx = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Anime"),
            preferredSubtitleLanguage = "en",
        )
        val winner = StreamScoringEngine.choose(listOf(pgs, dual), ctx)
        assertEquals("dual", winner?.id)
    }
}
