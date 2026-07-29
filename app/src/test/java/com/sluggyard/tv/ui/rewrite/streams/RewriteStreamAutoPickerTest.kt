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
    fun highQualityCachedTorrentBeatsLowQualityDirectUrl() {
        val direct = candidate("direct", directUrl = "https://stream.test/video")
            .copy(title = "480p CAM")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")
            .copy(title = "2160p HDR10")

        assertEquals(cached, selectRewriteAutoPlayCandidate(groups(direct, cached)))
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
    fun unknownTorrentAloneIsAutoSelectedAfterCacheProbeTimeout() {
        // AppShell remaps lingering CHECKING → UNKNOWN after the cache wait; UNKNOWN must remain
        // eligible so a hung TorBox probe cannot permanently block Play.
        val unknown = candidate("unknown", cacheState = StreamCacheState.UNKNOWN, infoHash = "unknown-hash")
        assertEquals(unknown, selectRewriteAutoPlayCandidate(groups(unknown)))
    }

    @Test
    fun aiUpscaledCachedLosesToCleanCached() {
        val ai = candidate("ai", cacheState = StreamCacheState.CACHED, infoHash = "ai-hash")
            .copy(title = "2160p AI Upscale")
        val clean = candidate("clean", cacheState = StreamCacheState.CACHED, infoHash = "clean-hash")
            .copy(title = "1080p BluRay")
        assertEquals(clean, selectRewriteAutoPlayCandidate(groups(ai, clean)))
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
    fun absurdlyLargeAnimeEpisodeLosesToNormalEncode() {
        val huge = candidate("huge", cacheState = StreamCacheState.CACHED, infoHash = "huge-hash")
            .copy(
                title = "Show.S01E01.1080p.BluRay.x265\n👤 80 💾 102GB ⚙️ RARBG",
                metadataText = "Show.S01E01.1080p.BluRay.x265 💾 102GB",
                seeders = 80,
            )
        val normal = candidate("normal", cacheState = StreamCacheState.CACHED, infoHash = "normal-hash")
            .copy(
                title = "Show.S01E01.1080p.WEB-DL.x265\n👤 70 💾 1.6 GB ⚙️ EZTV",
                metadataText = "Show.S01E01.1080p.WEB-DL.x265 💾 1.6 GB",
                seeders = 70,
            )
        val context = RewriteStreamScoringEngine.Context(
            title = "Anime Show",
            contentType = "series",
            genres = listOf("Animation", "Anime"),
            language = "ja",
        )

        assertTrue(
            RewriteStreamScoringEngine.score(normal, context) >
                RewriteStreamScoringEngine.score(huge, context),
        )
        assertEquals(normal, selectRewriteAutoPlayCandidate(groups(huge, normal), context))
    }

    @Test
    fun sizeParsedFromTitleIsUsedWhenVideoSizeBytesMissing() {
        val fromTitle = candidate("title-size", directUrl = "https://stream.test/title")
            .copy(title = "Episode 1080p 💾 1.8 GB")
        val structured = candidate("structured", directUrl = "https://stream.test/structured")
            .copy(
                title = "Episode 1080p",
                videoSizeBytes = (1.8 * 1024 * 1024 * 1024).toLong(),
            )
        val context = RewriteStreamScoringEngine.Context(title = "Show", contentType = "series")

        assertEquals(
            RewriteStreamScoringEngine.score(structured, context),
            RewriteStreamScoringEngine.score(fromTitle, context),
        )
    }

    @Test
    fun legitimate4kMovieRemuxIsNotCrushedBySizeAlone() {
        val remux = candidate("remux", cacheState = StreamCacheState.CACHED, infoHash = "remux-hash")
            .copy(
                title = "Movie.2024.2160p.UHD.BluRay.REMUX.HDR 💾 42 GB",
                seeders = 50,
            )
        val web = candidate("web", cacheState = StreamCacheState.CACHED, infoHash = "web-hash")
            .copy(
                title = "Movie.2024.1080p.WEB-DL 💾 8 GB",
                seeders = 50,
            )
        val context = RewriteStreamScoringEngine.Context(title = "Movie", contentType = "movie")

        // Remux may or may not win overall, but its size score must stay meaningful.
        assertTrue(RewriteStreamScoringEngine.score(remux, context) > 40)
        // Movies are not size-penalized for being large — a 102GB 4K remux stays competitive
        // with a 42GB remux (episode scoring still crushes absurd packs).
        assertTrue(
            kotlin.math.abs(
                RewriteStreamScoringEngine.score(remux, context) -
                    RewriteStreamScoringEngine.score(
                        remux.copy(title = "Movie.2024.2160p 💾 102 GB"),
                        context,
                    ),
            ) < 15,
        )
        // Sanity: remux remains competitive with a mid-size 1080p encode.
        assertTrue(
            kotlin.math.abs(
                RewriteStreamScoringEngine.score(remux, context) -
                    RewriteStreamScoringEngine.score(web, context),
            ) < 40,
        )
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

    @Test
    fun torrentWithDirectUrlButNotCachedIsNeverAutoSelected() {
        val uncachedWithUrl = candidate(
            "uncached-url",
            directUrl = "https://torrentio.strem.fun/resolve/abc",
            infoHash = "uncached-hash",
            cacheState = StreamCacheState.NOT_CACHED,
        )
        val checkingWithUrl = candidate(
            "checking-url",
            directUrl = "https://torrentio.strem.fun/resolve/def",
            infoHash = "checking-hash",
            cacheState = StreamCacheState.CHECKING,
        )
        assertNull(selectRewriteAutoPlayCandidate(groups(uncachedWithUrl, checkingWithUrl)))
    }

    @Test
    fun cachedTorrentWithDirectUrlIsAutoSelected() {
        val cached = candidate(
            "cached-url",
            directUrl = "https://torrentio.strem.fun/resolve/cached",
            infoHash = "cached-hash",
            cacheState = StreamCacheState.CACHED,
        )
        assertEquals(cached, selectRewriteAutoPlayCandidate(groups(cached)))
    }

    @Test
    fun magnetOnlyUrlIsNotAutoSelected() {
        val magnet = candidate(
            "magnet",
            directUrl = "magnet:?xt=urn:btih:abcdef",
            cacheState = StreamCacheState.NOT_APPLICABLE,
        )
        assertNull(selectRewriteAutoPlayCandidate(groups(magnet)))
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
