package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservedReleaseTracksTest {
    @Test
    fun dualAudioAssObservationBeatsTitleOnlyEnglishPgs() {
        val observed = ObservedReleaseTracks(
            audioLangBases = setOf("eng", "jpn"),
            subtitleLangBases = setOf("eng"),
            hasAss = true,
            hasSoftsubTrack = true,
            observedAtMs = 1L,
        )
        val pgsMono = StreamCandidate(
            id = "pgs",
            title = "Show.S01E01.1080p.BluRay.English.PGS",
            sourceLabel = "Torrentio",
            detailLabel = null,
            cacheState = StreamCacheState.CACHED,
            infoHash = "pgs-hash",
            seeders = 500,
            videoSizeBytes = 900L * 1024 * 1024,
        )
        val bareSeaDex = StreamCandidate(
            id = "sd",
            title = "[SomeGroup] Show - 01 (1080p)",
            sourceLabel = "SeaDex",
            detailLabel = null,
            cacheState = StreamCacheState.CACHED,
            infoHash = "seadex-hash",
            seeders = 40,
            videoSizeBytes = 600L * 1024 * 1024,
        )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
            observedTracksByHash = mapOf("seadex-hash" to observed),
        )
        assertTrue(bareSeaDex.isCuratedSeaDexSource())
        assertEquals(
            bareSeaDex,
            selectAutoPlayCandidate(groups(pgsMono, bareSeaDex), context),
        )
        val seaDexRank = StreamScoringEngine.rank(bareSeaDex, context)
        assertTrue(seaDexRank.usedObservedTracks)
        assertEquals(1, seaDexRank.dualFit)
        assertTrue(seaDexRank.softsubFit >= 5)
        assertEquals(1, seaDexRank.curatorFit)
    }

    @Test
    fun withoutMemoryBareTitleLosesToEnglishPgsOnCacheTieUnlessDualNamed() {
        val pgsMono = StreamCandidate(
            id = "pgs",
            title = "Show.S01E01.1080p.BluRay.English.PGS",
            sourceLabel = "Torrentio",
            detailLabel = null,
            cacheState = StreamCacheState.CACHED,
            infoHash = "pgs-hash",
            seeders = 500,
            videoSizeBytes = 900L * 1024 * 1024,
        )
        val bare = StreamCandidate(
            id = "bare",
            title = "[SomeGroup] Show - 01 (1080p)",
            sourceLabel = "Other",
            detailLabel = null,
            cacheState = StreamCacheState.CACHED,
            infoHash = "bare-hash",
            seeders = 40,
            videoSizeBytes = 600L * 1024 * 1024,
        )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )
        // Instant-only Finding refuses Eng-PGS mono; with fallback + no dual title, PGS can win.
        assertEquals(
            pgsMono,
            selectAutoPlayCandidate(groups(pgsMono, bare), context, allowUncachedFallback = true),
        )
    }

    @Test
    fun observedFromPlayerMarksDualAss() {
        val obs = observedReleaseTracksFromPlayer(
            audioLanguages = listOf("jpn", "eng"),
            subtitleTracks = listOf(
                PlayerSubtitleObservation(language = "eng", looksAss = true),
            ),
        )
        assertTrue(obs.dualAudio)
        assertTrue(obs.hasAss)
        val (soft, dual) = obs.toTitleScaleSignals("en", animeLike = true)
        assertEquals(5, soft)
        assertEquals(1, dual)
    }

    @Test
    fun seadexSourceDetectsAddonLabel() {
        val c = StreamCandidate(
            id = "x",
            title = "Show",
            sourceLabel = "AIOStreams | SeaDex",
            detailLabel = null,
            cacheState = StreamCacheState.CACHED,
            infoHash = "h",
        )
        assertTrue(c.isCuratedSeaDexSource())
        assertFalse(
            c.copy(sourceLabel = "Torrentio").isCuratedSeaDexSource(),
        )
    }

    @Test
    fun animeInstantAssWithNoPreferredLanguageStartsDuringFinding() {
        // softsubFit==4 for no-pref ASS must not use the Instant PGS wait bar.
        val ass = candidate("ass", cacheState = StreamCacheState.CACHED, infoHash = "ass-hash")
            .copy(
                title = "[SubsPlease] Show - 01 (1080p) SoftSub ASS",
                videoSizeBytes = 400L * 1024 * 1024,
                seeders = 30,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = null,
        )
        assertEquals(ass, selectAutoPlayCandidate(groups(ass), context, allowUncachedFallback = false))
    }

    @Test
    fun animeScorePoolKeepsLowSeederDualOverHighSeederEnglishPgs() {
        // >40 fake Instant Eng rows by seeders must not crowd dual out of the score cap.
        val dual = candidate("dual", cacheState = StreamCacheState.NOT_CACHED, infoHash = "dual-hash")
            .copy(
                title = "[Judas] Show - 01 [Dual-Audio][Multi-Subs][HEVC]",
                videoSizeBytes = 350L * 1024 * 1024,
                seeders = 3,
            )
        val pgsCrowd = (1..45).map { i ->
            candidate("pgs$i", cacheState = StreamCacheState.CACHED, infoHash = "pgs-hash-$i")
                .copy(
                    title = "Show.S01E01.1080p.BluRay.English.PGS.Crowd$i",
                    videoSizeBytes = 900L * 1024 * 1024,
                    seeders = 100 + i,
                )
        }
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )
        assertEquals(
            dual,
            selectAutoPlayCandidate(
                groupsOf(pgsCrowd + dual),
                context,
                allowUncachedFallback = true,
            ),
        )
    }

    private fun candidate(
        id: String,
        cacheState: StreamCacheState = StreamCacheState.NOT_APPLICABLE,
        infoHash: String? = null,
    ) = StreamCandidate(
        id = id,
        title = id,
        sourceLabel = "Source",
        detailLabel = null,
        cacheState = cacheState,
        infoHash = infoHash,
    )

    private fun groups(vararg candidates: StreamCandidate): List<StreamGroup> =
        groupsOf(candidates.toList())

    private fun groupsOf(candidates: List<StreamCandidate>): List<StreamGroup> =
        listOf(
            StreamGroup(
                addonId = "test",
                addonName = "test",
                state = StreamGroupState.Content(candidates),
            ),
        )
}
