package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewriteStreamAutoPickerTest {
    @Test
    fun directStreamsWinOverCachedAndTorrentCandidates() {
        val direct = candidate("direct", directUrl = "https://stream.test/video")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")

        assertEquals(direct, selectRewriteAutoPlayCandidate(groups(direct, cached)))
    }

    @Test
    fun nonDolbyVisionDirectStreamWinsOverHigherQualityDolbyVisionStream() {
        val dolbyVision = candidate("dolby", directUrl = "https://stream.test/dv")
            .copy(title = "2160p Dolby Vision Profile 8.6")
        val hdr10 = candidate("hdr10", directUrl = "https://stream.test/hdr10")
            .copy(title = "1080p HDR10")

        assertEquals(hdr10, selectRewriteAutoPlayCandidate(groups(dolbyVision, hdr10)))
    }

    @Test
    fun cachedTorrentWinsOverUnknownFallback() {
        val unknown = candidate("unknown", cacheState = StreamCacheState.UNKNOWN, infoHash = "unknown-hash")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")

        assertEquals(cached, selectRewriteAutoPlayCandidate(groups(unknown, cached)))
    }

    @Test
    fun checkingAndNotCachedCandidatesAreNotAutoSelected() {
        val checking = candidate("checking", cacheState = StreamCacheState.CHECKING, infoHash = "checking-hash")
        val notCached = candidate("not-cached", cacheState = StreamCacheState.NOT_CACHED, infoHash = "not-cached-hash")

        assertNull(selectRewriteAutoPlayCandidate(groups(checking, notCached)))
        assertTrue(hasRewritePendingCacheChecks(groups(checking, notCached)))
    }

    @Test
    fun terminalCacheStatesDoNotKeepAutoPickWaiting() {
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")
        val notCached = candidate("not-cached", cacheState = StreamCacheState.NOT_CACHED, infoHash = "not-cached-hash")

        assertFalse(hasRewritePendingCacheChecks(groups(cached, notCached)))
    }

    @Test
    fun animeReliabilityAndSubtitleAvailabilityCanBeatRawResolution() {
        val highResolution = candidate("4k", directUrl = "https://stream.test/4k")
            .copy(title = "2160p Anime", seeders = 1, metadataText = "2160p")
        val reliableSubtitled = candidate("1080", directUrl = "https://stream.test/1080")
            .copy(title = "1080p Anime English Subs", seeders = 100, metadataText = "1080p English subtitles")

        assertEquals(
            reliableSubtitled,
            selectRewriteAutoPlayCandidate(
                groups(highResolution, reliableSubtitled),
                RewriteStreamScoringEngine.Context(
                    title = "Anime Show",
                    contentType = "series",
                    genres = listOf("Animation"),
                    language = "ja",
                ),
            ),
        )
    }

    @Test
    fun knownDolbyVision86IsAvoidedButOtherDolbyVisionIsNotBlanketRejected() {
        val dv86 = candidate("dv86", directUrl = "https://stream.test/dv86")
            .copy(title = "2160p Dolby Vision Profile 8.6")
        val hdr10 = candidate("hdr10", directUrl = "https://stream.test/hdr10")
            .copy(title = "1080p HDR10")
        assertEquals(hdr10, selectRewriteAutoPlayCandidate(groups(dv86, hdr10)))

        val dv7 = candidate("dv7", directUrl = "https://stream.test/dv7")
            .copy(title = "2160p Dolby Vision Profile 7")
        assertEquals(dv7, selectRewriteAutoPlayCandidate(groups(dv7, hdr10)))
    }

    private fun groups(vararg candidates: RewriteStreamCandidate) = listOf(
        RewriteStreamGroup("source", "Source", RewriteStreamGroupState.Content(candidates.toList())),
    )

    private fun candidate(
        id: String,
        directUrl: String? = null,
        infoHash: String? = null,
        cacheState: StreamCacheState = StreamCacheState.NOT_APPLICABLE,
    ) = RewriteStreamCandidate(id, id, "Source", null, cacheState, directUrl, infoHash)
}
