package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAutoPickerTest {
    @Test
    fun directStreamsWinOverCachedAndTorrentCandidates() {
        val direct = candidate("direct", directUrl = "https://stream.test/video")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")

        assertEquals(direct, selectAutoPlayCandidate(groups(direct, cached)))
    }

    @Test
    fun highQualityCachedTorrentBeatsLowQualityDirectUrl() {
        val direct = candidate("direct", directUrl = "https://stream.test/video")
            .copy(title = "480p CAM")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")
            .copy(title = "2160p HDR10")

        assertEquals(cached, selectAutoPlayCandidate(groups(direct, cached)))
    }

    @Test
    fun candidatesAt720pAreRemovedFromAutoPickAndDisplayRanking() {
        val low = candidate("720", directUrl = "https://stream.test/720")
            .copy(title = "Show 720p")
        val preferred = candidate("1080", directUrl = "https://stream.test/1080")
            .copy(title = "Show 1080p")
        val context = StreamScoringEngine.Context(title = "Show", contentType = "movie")

        assertEquals(preferred, selectAutoPlayCandidate(groups(low, preferred), context))
        assertEquals(listOf(preferred), StreamScoringEngine.rankedCandidates(listOf(low, preferred), context))
        assertNull(selectAutoPlayCandidate(groups(low), context))
    }

    @Test
    fun nonDolbyVisionDirectStreamWinsOverHigherQualityDolbyVisionStream() {
        val dolbyVision = candidate("dolby", directUrl = "https://stream.test/dv")
            .copy(title = "2160p Dolby Vision Profile 8.6")
        val hdr10 = candidate("hdr10", directUrl = "https://stream.test/hdr10")
            .copy(title = "1080p HDR10")

        assertEquals(hdr10, selectAutoPlayCandidate(groups(dolbyVision, hdr10)))
    }

    @Test
    fun cachedTorrentWinsOverUnknownFallback() {
        val unknown = candidate("unknown", cacheState = StreamCacheState.UNKNOWN, infoHash = "unknown-hash")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")

        assertEquals(cached, selectAutoPlayCandidate(groups(unknown, cached)))
    }

    @Test
    fun unknownTorrentIsNotAutoSelected() {
        // Uncached / probe-unknown torrents stay in Sources for manual pick only.
        val unknown = candidate("unknown", cacheState = StreamCacheState.UNKNOWN, infoHash = "unknown-hash")
        assertNull(selectAutoPlayCandidate(groups(unknown)))
    }

    @Test
    fun kDramaPrefersSoftsubsAndIgnoresDualAudio() {
        val dualOnly = candidate("dual", cacheState = StreamCacheState.CACHED, infoHash = "dual-hash")
            .copy(title = "Show.S01E01.1080p.WEB-DL.Dual-Audio", seeders = 80)
        val soft = candidate("soft", cacheState = StreamCacheState.CACHED, infoHash = "soft-hash")
            .copy(title = "Show.S01E01.1080p.WEB-DL.English.Softsubs", seeders = 20)
        val context = StreamScoringEngine.Context(
            title = "K Drama Show",
            contentType = "series",
            genres = listOf("K-Drama", "Romance"),
            preferredSubtitleLanguage = "en",
        )

        assertEquals(
            StreamScoringEngine.ContentKind.K_DRAMA,
            StreamScoringEngine.contentKind(context),
        )
        assertEquals(soft, selectAutoPlayCandidate(groups(dualOnly, soft), context))
    }

    @Test
    fun jDramaPrefersSoftsubsOverDualAudio() {
        val dualOnly = candidate("dual", cacheState = StreamCacheState.CACHED, infoHash = "dual-hash")
            .copy(title = "Show.S01E01.1080p.Dual-Audio", seeders = 90)
        val soft = candidate("soft", cacheState = StreamCacheState.CACHED, infoHash = "soft-hash")
            .copy(title = "Show.S01E01.1080p.Multiple Subtitle", seeders = 15)
        val context = StreamScoringEngine.Context(
            title = "J Drama Show",
            contentType = "series",
            genres = listOf("J-Drama"),
            preferredSubtitleLanguage = "en",
        )

        assertEquals(
            StreamScoringEngine.ContentKind.J_DRAMA,
            StreamScoringEngine.contentKind(context),
        )
        assertEquals(soft, selectAutoPlayCandidate(groups(dualOnly, soft), context))
    }

    @Test
    fun aiUpscaledCachedLosesToCleanCached() {
        val ai = candidate("ai", cacheState = StreamCacheState.CACHED, infoHash = "ai-hash")
            .copy(title = "2160p AI Upscale")
        val clean = candidate("clean", cacheState = StreamCacheState.CACHED, infoHash = "clean-hash")
            .copy(title = "1080p BluRay")
        assertEquals(clean, selectAutoPlayCandidate(groups(ai, clean)))
    }

    @Test
    fun checkingAndNotCachedCandidatesAreNotAutoSelected() {
        val checking = candidate("checking", cacheState = StreamCacheState.CHECKING, infoHash = "checking-hash")
        val notCached = candidate("not-cached", cacheState = StreamCacheState.NOT_CACHED, infoHash = "not-cached-hash")

        assertNull(selectAutoPlayCandidate(groups(checking, notCached)))
        assertTrue(hasPendingCacheChecks(groups(checking, notCached)))
    }

    @Test
    fun terminalCacheStatesDoNotKeepAutoPickWaiting() {
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")
        val notCached = candidate("not-cached", cacheState = StreamCacheState.NOT_CACHED, infoHash = "not-cached-hash")

        assertFalse(hasPendingCacheChecks(groups(cached, notCached)))
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
        val context = StreamScoringEngine.Context(
            title = "Anime Show",
            contentType = "series",
            genres = listOf("Animation", "Anime"),
            language = "ja",
        )

        assertTrue(
            StreamScoringEngine.score(normal, context) >
                StreamScoringEngine.score(huge, context),
        )
        assertEquals(normal, selectAutoPlayCandidate(groups(huge, normal), context))
    }

    @Test
    fun seriesAnimationWithoutJaLanguageStillUsesAnimeWeights() {
        // Cinemeta often tags Akame/JJK-style titles as Animation only.
        val raw4k = candidate("raw4k", cacheState = StreamCacheState.CACHED, infoHash = "raw-hash")
            .copy(title = "Jujutsu.Kaisen.S01E01.2160p.BluRay.REMUX.RAW", seeders = 40)
        val softAss = candidate("softAss", cacheState = StreamCacheState.CACHED, infoHash = "ass-hash")
            .copy(title = "Jujutsu.Kaisen.S01E01.1080p.WEB-DL.SoftSubs.ASS", seeders = 35)
        val context = StreamScoringEngine.Context(
            title = "Jujutsu Kaisen",
            contentType = "series",
            genres = listOf("Action", "Animation"),
            preferredSubtitleLanguage = "es",
        )

        assertEquals(softAss, selectAutoPlayCandidate(groups(raw4k, softAss), context))
        assertTrue(
            StreamScoringEngine.score(softAss, context) >
                StreamScoringEngine.score(raw4k, context),
        )
    }

    @Test
    fun preferredLanguageBeatsOtherLanguageSoftsub() {
        val spanish = candidate("es", cacheState = StreamCacheState.CACHED, infoHash = "es-hash")
            .copy(title = "Show.S01E01.1080p.WEB-DL.Spanish.Softsubs", seeders = 40)
        val english = candidate("en", cacheState = StreamCacheState.CACHED, infoHash = "en-hash")
            .copy(title = "Show.S01E01.1080p.WEB-DL.English.Softsubs", seeders = 40)
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "es",
        )

        assertEquals(spanish, selectAutoPlayCandidate(groups(english, spanish), context))
    }

    @Test
    fun softsubAssBeatsHardsubWhenPreferredLanguageAbsentFromName() {
        val hard = candidate("hard", cacheState = StreamCacheState.CACHED, infoHash = "hard-hash")
            .copy(title = "Show.S01E01.1080p.BluRay.Hardsub", seeders = 80)
        val soft = candidate("soft", cacheState = StreamCacheState.CACHED, infoHash = "soft-hash")
            .copy(title = "Show.S01E01.1080p.BluRay.SoftSub.ASS", seeders = 50)
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "pt-br",
        )

        assertEquals(soft, selectAutoPlayCandidate(groups(hard, soft), context))
    }

    @Test
    fun preferredLanguageAssBeatsPreferredLanguageSrtForAnime() {
        // Repro: auto-play matched Eng preference via SRT while AnimeTosho had Eng ASS
        // (no dub title — ASS styling/signs matter).
        val srt = candidate("srt", cacheState = StreamCacheState.CACHED, infoHash = "srt-hash")
            .copy(
                title = "AnimeTosho | Show S01E01 1080p English SRT",
                filename = "Show.S01E01.1080p.WEB-DL.English.SRT.mkv",
                seeders = 80,
            )
        val ass = candidate("ass", cacheState = StreamCacheState.CACHED, infoHash = "ass-hash")
            .copy(
                title = "AnimeTosho | Show S01E01 1080p English ASS",
                filename = "Show.S01E01.1080p.BluRay.English.ASS.mkv",
                seeders = 40,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            language = "ja",
            preferredSubtitleLanguage = "en",
        )

        assertEquals(4, StreamScoringEngine.rank(ass, context).softsubFit)
        assertEquals(3, StreamScoringEngine.rank(srt, context).softsubFit)
        assertEquals(ass, selectAutoPlayCandidate(groups(srt, ass), context))
    }

    @Test
    fun preDigitalMovieBlocksAllAutoPlayNotJustCam() {
        val cam = candidate("cam", cacheState = StreamCacheState.CACHED, infoHash = "cam-hash")
            .copy(title = "Blockbuster.2026.CAM.x264", seeders = 200)
        val web = candidate("web", cacheState = StreamCacheState.CACHED, infoHash = "web-hash")
            .copy(title = "Blockbuster.2026.1080p.WEB-DL", seeders = 20)
        val context = StreamScoringEngine.Context(
            title = "Blockbuster",
            contentType = "movie",
            digitalReleaseStatus = DigitalReleasePolicy.Status.NOT_YET,
        )

        assertEquals(null, selectAutoPlayCandidate(groups(cam, web), context))
        assertEquals(null, selectAutoPlayCandidate(groups(cam), context))
        assertEquals(null, selectAutoPlayCandidate(groups(web), context))
    }

    @Test
    fun knownSoftsubGroupBeatsRawWithoutSayingAss() {
        // Real anime releases almost never put "ASS" in the title — SubsPlease is softsub.
        val raw = candidate("raw", cacheState = StreamCacheState.CACHED, infoHash = "raw-hash")
            .copy(title = "[SomeGroup] Jujutsu Kaisen - 01 (1080p) RAW", seeders = 60)
        val soft = candidate("soft", cacheState = StreamCacheState.CACHED, infoHash = "soft-hash")
            .copy(title = "[SubsPlease] Jujutsu Kaisen - 01 (1080p) [A1B2C3D4]", seeders = 55)
        val context = StreamScoringEngine.Context(
            title = "Jujutsu Kaisen",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )

        assertEquals(soft, selectAutoPlayCandidate(groups(raw, soft), context))
        assertEquals(4, StreamScoringEngine.rank(soft, context).softsubFit)
    }

    @Test
    fun animeRejectsHugeCachedSeasonPackEvenIfSoftsubTagged() {
        // Onn repro: MediaFusion CACHED ~17GB with a single-episode filename (not a named
        // multi-file pack) won softsub ranking and MPV hung on TorBox CDN.
        val pack = candidate("pack", cacheState = StreamCacheState.CACHED, infoHash = "pack-hash")
            .copy(
                title = "MediaFusion | Midnight cached pack",
                filename = "[SubsPlease] Jujutsu Kaisen - 04v2 (1080p) [E77C9F8C].mkv",
                videoSizeBytes = 17_826_048_926L,
                seeders = 45,
            )
        val sane = candidate("sane", cacheState = StreamCacheState.CACHED, infoHash = "sane-hash")
            .copy(
                title = "[SubsPlease] Jujutsu Kaisen - 04v2 (1080p) [E77C9F8C].mkv",
                filename = "[SubsPlease] Jujutsu Kaisen - 04v2 (1080p) [E77C9F8C].mkv",
                videoSizeBytes = (350L * 1024 * 1024),
                seeders = 40,
            )
        val context = StreamScoringEngine.Context(
            title = "Jujutsu Kaisen",
            contentType = "series",
            genres = listOf("Animation", "Action", "Adventure"),
            preferredSubtitleLanguage = "en",
        )
        assertEquals(sane, selectAutoPlayCandidate(groups(pack, sane), context))
        assertNull(selectAutoPlayCandidate(groups(pack), context))
    }

    @Test
    fun animeAllowsNamedDualSeasonPackOverSizeGate() {
        // Judas/Anime Time Dual+Multi-Subs packs report ~8GB season totals but are the
        // releases we want — TorBox episode file-select picks SxxExx out of the pack.
        val pack = candidate("pack", cacheState = StreamCacheState.CACHED, infoHash = "pack-hash")
            .copy(
                title = "MediaFusion | Midnight 1080p",
                filename = "[Judas] Akame ga Kill! (Season 1) [BD 1080p][HEVC x265 10bit][Dual-Audio][Multi-Subs]",
                videoSizeBytes = 8_269_959_528L,
                seeders = 30,
            )
        val spanish = candidate("es", cacheState = StreamCacheState.CACHED, infoHash = "es-hash")
            .copy(
                title = "MediaFusion | Midnight 1080p",
                filename = "[Moozzi2] Akame ga Kill! Spanish ASS Softsub (BD 1080p)",
                videoSizeBytes = 1_395_864_405L,
                seeders = 40,
            )
        val context = StreamScoringEngine.Context(
            title = "Akame ga Kill!",
            contentType = "series",
            genres = listOf("Animation", "Action"),
            preferredSubtitleLanguage = "en",
        )
        assertEquals(pack, selectAutoPlayCandidate(groups(spanish, pack), context))
        assertEquals(pack, selectAutoPlayCandidate(groups(pack), context))
    }

    @Test
    fun animeRefusesSpanishAcMultiSubWhenPreferredEnglish() {
        // Onn repro: only cached Eng-looking hit was A&C Multi-Sub — actually Spanish Latam
        // audio+ASS. Auto-play must open Sources instead of starting it.
        val ac = candidate("ac", cacheState = StreamCacheState.CACHED, infoHash = "ac-hash")
            .copy(
                title = "MediaFusion | Midnight 1080p",
                filename = "[A&C] Akame ga Kill! [BDRip 1080p] [Multi-Audio] [Multi-Sub] S01",
                videoSizeBytes = 3_771_518_293L,
                seeders = 45,
            )
        val context = StreamScoringEngine.Context(
            title = "Akame ga Kill!",
            contentType = "series",
            genres = listOf("Animation", "Action"),
            preferredSubtitleLanguage = "en",
        )
        assertEquals(1, StreamScoringEngine.rank(ac, context).softsubFit)
        assertNull(selectAutoPlayCandidate(groups(ac), context))
    }

    @Test
    fun animeRejectsOpaqueHugeSingleEpisodeLabel() {
        val opaque = candidate("opaque", cacheState = StreamCacheState.CACHED, infoHash = "opaque-hash")
            .copy(
                filename = "Akame ga Kill! (2014) - S01E01 - Kill the Darkness (1080p BluRay x265 SAMPA).mkv",
                videoSizeBytes = 15_784_036_608L,
                seeders = 50,
            )
        val context = StreamScoringEngine.Context(
            title = "Akame ga Kill!",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )
        assertNull(selectAutoPlayCandidate(groups(opaque), context))
    }

    @Test
    fun preferredEnglishDemotesSpanishAssSoftsub() {
        val spanish = candidate("es", cacheState = StreamCacheState.CACHED, infoHash = "es-hash")
            .copy(
                title = "Show.S01E01.1080p.Spanish.Softsub.ASS",
                videoSizeBytes = 400L * 1024 * 1024,
                seeders = 80,
            )
        val english = candidate("en", cacheState = StreamCacheState.CACHED, infoHash = "en-hash")
            .copy(
                title = "[Anime Time] Show [Dual Audio][BD][1080p][HEVC][Eng Sub]",
                videoSizeBytes = 400L * 1024 * 1024,
                seeders = 20,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )
        assertEquals(english, selectAutoPlayCandidate(groups(spanish, english), context))
        assertTrue(
            StreamScoringEngine.rank(english, context).softsubFit >
                StreamScoringEngine.rank(spanish, context).softsubFit,
        )
    }

    @Test
    fun shortLanguageAliasInTitleDoesNotFalseConflict() {
        // "It" / "Hi" must not demote softsubs as Italian / Hindi conflicts.
        val soft = candidate("soft", cacheState = StreamCacheState.CACHED, infoHash = "soft-hash")
            .copy(
                title = "[SubsPlease] Make It Right - 01 (1080p) SoftSub",
                videoSizeBytes = 400L * 1024 * 1024,
                seeders = 40,
            )
        val spanish = candidate("es", cacheState = StreamCacheState.CACHED, infoHash = "es-hash")
            .copy(
                title = "Make It Right.S01E01.1080p.Spanish.ASS",
                videoSizeBytes = 400L * 1024 * 1024,
                seeders = 90,
            )
        val context = StreamScoringEngine.Context(
            title = "Make It Right",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )
        assertEquals(soft, selectAutoPlayCandidate(groups(spanish, soft), context))
        assertTrue(StreamScoringEngine.rank(soft, context).softsubFit >= 4)
    }

    @Test
    fun mediaFusionChromeTitleDoesNotCountAsSoftsubByItself() {
        val chrome = candidate("chrome", cacheState = StreamCacheState.CACHED, infoHash = "chrome-hash")
            .copy(
                title = "MediaFusion | Midnight cached direct",
                videoSizeBytes = (400L * 1024 * 1024),
                seeders = 45,
            )
        val real = candidate("real", cacheState = StreamCacheState.CACHED, infoHash = "real-hash")
            .copy(
                title = "[SubsPlease] Show - 01 (1080p)",
                videoSizeBytes = (400L * 1024 * 1024),
                seeders = 40,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )
        assertEquals(real, selectAutoPlayCandidate(groups(chrome, real), context))
        assertTrue(
            StreamScoringEngine.rank(real, context).softsubFit >
                StreamScoringEngine.rank(chrome, context).softsubFit,
        )
    }

    @Test
    fun animeRanksSoftsubsThenDualThenSeeders() {
        val softLowSeed = candidate("soft", cacheState = StreamCacheState.CACHED, infoHash = "soft-hash")
            .copy(title = "[SubsPlease] Show - 01 (1080p)", seeders = 5)
        val dualHighSeed = candidate("dual", cacheState = StreamCacheState.CACHED, infoHash = "dual-hash")
            .copy(title = "[Kametsu] Show (BD 1080p) [Dual-Audio]", seeders = 900)
        val dualSoftMid = candidate("both", cacheState = StreamCacheState.CACHED, infoHash = "both-hash")
            .copy(title = "[Judas] Show - 01 [HEVC][Dual-Audio][Multi-Subs]", seeders = 20)
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )

        // Softsubs beat Dual-Audio-only even with way fewer seeders.
        assertEquals(softLowSeed, selectAutoPlayCandidate(groups(dualHighSeed, softLowSeed), context))
        // Dual+softsubs beat softsubs-only.
        assertEquals(dualSoftMid, selectAutoPlayCandidate(groups(softLowSeed, dualSoftMid), context))
    }

    @Test
    fun movieRanksSeedersThenQuality() {
        val popularHd = candidate("pop", cacheState = StreamCacheState.CACHED, infoHash = "pop-hash")
            .copy(title = "Movie 1080p WEB-DL", seeders = 500)
        val rare4k = candidate("rare", cacheState = StreamCacheState.CACHED, infoHash = "rare-hash")
            .copy(title = "Movie 2160p UHD BluRay REMUX HDR", seeders = 5)
        val context = StreamScoringEngine.Context(title = "Movie", contentType = "movie")

        assertEquals(popularHd, selectAutoPlayCandidate(groups(rare4k, popularHd), context))
    }

    @Test
    fun preferredLangSoftsubBeatsDualAudioOnlyPack() {
        // Kametsu-style Dual-Audio Hi10 with no Eng Sub / Multi-Subs must lose to softsub packs.
        val dualOnly = candidate("dual", cacheState = StreamCacheState.CACHED, infoHash = "dual-hash")
            .copy(
                title = "[Kametsu] Show (BD 1080p Hi10 FLAC) [Dual-Audio]\n👤 43 💾 1.76 GB",
                seeders = 43,
            )
        val engSub = candidate("eng", cacheState = StreamCacheState.CACHED, infoHash = "eng-hash")
            .copy(
                title = "[Anime Time] Show [Dual Audio][BD][1080p][HEVC][Eng Sub]\n👤 19 💾 347.2 MB",
                seeders = 19,
            )
        val multi = candidate("multi", cacheState = StreamCacheState.CACHED, infoHash = "multi-hash")
            .copy(
                title = "[Erai-raws] Show - 01 [1080p][Multiple Subtitle]\n👤 26 💾 949.89 MB",
                seeders = 26,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )

        val pick = selectAutoPlayCandidate(groups(dualOnly, engSub, multi), context)
        assertTrue(pick == engSub || pick == multi, "expected softsub winner, got ${pick?.title}")
        assertTrue(
            StreamScoringEngine.score(engSub, context) >
                StreamScoringEngine.score(dualOnly, context),
        )
        assertTrue(
            StreamScoringEngine.score(multi, context) >
                StreamScoringEngine.score(dualOnly, context),
        )
    }

    @Test
    fun softsubScoringMustNotPickUnknownOverCachedDualAudio() {
        // Softsub + preferred-lang bonuses can outrank reliability enough that UNKNOWN softsubs
        // used to beat CACHED dual-audio — Play then hung resolving an uncached torrent.
        val cachedDual = candidate("dual", cacheState = StreamCacheState.CACHED, infoHash = "dual-hash")
            .copy(
                title = "[Kametsu] Show (BD 1080p Hi10 FLAC) [Dual-Audio]\n👤 43 💾 1.76 GB",
                seeders = 43,
            )
        val unknownSoft = candidate("soft", cacheState = StreamCacheState.UNKNOWN, infoHash = "soft-hash")
            .copy(
                title = "[Anime Time] Show [Dual Audio][BD][1080p][HEVC][Eng Sub]\n👤 19 💾 347.2 MB",
                seeders = 19,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )

        assertTrue(
            StreamScoringEngine.rank(unknownSoft, context) >
                StreamScoringEngine.rank(cachedDual, context),
            "precondition: softsub rank alone still beats dual-audio",
        )
        assertEquals(
            cachedDual,
            selectAutoPlayCandidate(groups(cachedDual, unknownSoft), context),
        )
    }

    @Test
    fun animeHevcSoftsubBeatsHi10EvenWhenHi10IsKnownSoftsubGroup() {
        // Doki is a softsub group but Hi10P (H.264 10-bit) → SW decode lag on leanback MPV.
        val hi10 = candidate("hi10", cacheState = StreamCacheState.CACHED, infoHash = "hi10-hash")
            .copy(
                title = "[Doki] Show - 01 (1920x1080 Hi10P BD FLAC)\n👤 40 💾 684.14 MB",
                seeders = 40,
            )
        val hevcDualSoft = candidate("hevc", cacheState = StreamCacheState.CACHED, infoHash = "hevc-hash")
            .copy(
                title = "[Judas] Show - 01 [BD 1080p][HEVC x265 10bit][Dual-Audio][Multi-Subs]\n👤 20 💾 300 MB",
                seeders = 20,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )

        assertEquals(hevcDualSoft, selectAutoPlayCandidate(groups(hi10, hevcDualSoft), context))
        assertTrue(
            StreamScoringEngine.score(hevcDualSoft, context) >
                StreamScoringEngine.score(hi10, context),
        )
    }

    @Test
    fun animeHevc10BitIsNotPenalizedLikeHi10() {
        val hevc10 = candidate("hevc10", cacheState = StreamCacheState.CACHED, infoHash = "hevc10-hash")
            .copy(
                title = "[Anime Time] Show [Dual Audio][BD][1080p][HEVC 10bit x265][Eng Sub]",
                seeders = 25,
            )
        val hevc8 = candidate("hevc8", cacheState = StreamCacheState.CACHED, infoHash = "hevc8-hash")
            .copy(
                title = "[Anime Time] Show [Dual Audio][BD][1080p][HEVC x265][Eng Sub]",
                seeders = 25,
            )
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "en",
        )

        // HEVC 10-bit is often HW-decodable; do not crush it relative to HEVC without 10bit tag.
        val r10 = StreamScoringEngine.rank(hevc10, context)
        val r8 = StreamScoringEngine.rank(hevc8, context)
        assertEquals(r10.decodeFit, r8.decodeFit)
        assertEquals(r10.softsubFit, r8.softsubFit)
        assertEquals(r10.dualFit, r8.dualFit)
        assertTrue(
            kotlin.math.abs(r10.quality - r8.quality) <= 3,
            "HEVC 10bit vs HEVC quality delta too large",
        )
    }

    @Test
    fun bracketLanguageTagCountsAsPreferredLanguage() {
        val other = candidate("other", cacheState = StreamCacheState.CACHED, infoHash = "other-hash")
            .copy(title = "[SubsPlease] Show - 01 (1080p)", seeders = 40)
        val preferred = candidate("pref", cacheState = StreamCacheState.CACHED, infoHash = "pref-hash")
            .copy(title = "[Erai-raws] Show - 01 [1080p][Multiple Subtitle][ES]", seeders = 40)
        val context = StreamScoringEngine.Context(
            title = "Show",
            contentType = "series",
            genres = listOf("Animation"),
            preferredSubtitleLanguage = "es",
        )

        assertEquals(preferred, selectAutoPlayCandidate(groups(other, preferred), context))
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
        val context = StreamScoringEngine.Context(title = "Show", contentType = "series")

        assertEquals(
            StreamScoringEngine.score(structured, context),
            StreamScoringEngine.score(fromTitle, context),
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
        val context = StreamScoringEngine.Context(title = "Movie", contentType = "movie")
        val remuxRank = StreamScoringEngine.rank(remux, context)
        val hugeRank = StreamScoringEngine.rank(
            remux.copy(title = "Movie.2024.2160p 💾 102 GB"),
            context,
        )
        val webRank = StreamScoringEngine.rank(web, context)

        // Movies are not size-penalized for being large — 102GB remux keeps full sizeFit.
        assertEquals(100, remuxRank.sizeFit)
        assertEquals(100, hugeRank.sizeFit)
        // 4K remux still beats 1080p web on quality; size is not the decider.
        assertTrue(remuxRank.quality > webRank.quality)
        assertEquals(
            remux,
            selectAutoPlayCandidate(groups(remux, web), context),
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
            selectAutoPlayCandidate(
                groups(highResolution, reliableSubtitled),
                StreamScoringEngine.Context(
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
        assertEquals(hdr10, selectAutoPlayCandidate(groups(dv86, hdr10)))

        val dv7 = candidate("dv7", directUrl = "https://stream.test/dv7")
            .copy(title = "2160p Dolby Vision Profile 7")
        assertEquals(dv7, selectAutoPlayCandidate(groups(dv7, hdr10)))
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
        assertNull(selectAutoPlayCandidate(groups(uncachedWithUrl, checkingWithUrl)))
    }

    @Test
    fun cachedTorrentWithDirectUrlIsAutoSelected() {
        val cached = candidate(
            "cached-url",
            directUrl = "https://torrentio.strem.fun/resolve/cached",
            infoHash = "cached-hash",
            cacheState = StreamCacheState.CACHED,
        )
        assertEquals(cached, selectAutoPlayCandidate(groups(cached)))
    }

    @Test
    fun magnetOnlyUrlIsNotAutoSelected() {
        val magnet = candidate(
            "magnet",
            directUrl = "magnet:?xt=urn:btih:abcdef",
            cacheState = StreamCacheState.NOT_APPLICABLE,
        )
        assertNull(selectAutoPlayCandidate(groups(magnet)))
    }

    @Test
    fun preferLastPlayedCachedHashWinsEvenOverHigherQualityDirect() {
        val last = candidate("last", cacheState = StreamCacheState.CACHED, infoHash = "abc123")
            .copy(title = "1080p WEB")
        val better = candidate("better", directUrl = "https://stream.test/4k")
            .copy(title = "2160p HDR10")
        val prefer = com.sluggyard.tv.data.local.CachedStreamLink(
            url = "",
            streamName = "1080p WEB",
            headers = emptyMap(),
            cachedAtMs = System.currentTimeMillis(),
            infoHash = "ABC123",
        )

        assertEquals(
            last,
            selectAutoPlayCandidate(
                groups(better, last),
                preferLastPlayed = prefer,
            ),
        )
    }

    @Test
    fun preferLastPlayedMissFallsThroughToNormalRanking() {
        val a = candidate("a", cacheState = StreamCacheState.CACHED, infoHash = "aaa")
            .copy(title = "720p WEB")
        val b = candidate("b", cacheState = StreamCacheState.CACHED, infoHash = "bbb")
            .copy(title = "1080p WEB")
        val prefer = com.sluggyard.tv.data.local.CachedStreamLink(
            url = "",
            streamName = "gone",
            headers = emptyMap(),
            cachedAtMs = System.currentTimeMillis(),
            infoHash = "zzzz",
        )

        assertEquals(
            b,
            selectAutoPlayCandidate(
                groups(a, b),
                preferLastPlayed = prefer,
            ),
        )
    }

    @Test
    fun matchesLastPlayedByInfoHashAndFilename() {
        val c = candidate("c", cacheState = StreamCacheState.CACHED, infoHash = "hash1")
            .copy(filename = "Movie.1080p.mkv", fileIndex = 2)
        assertTrue(
            c.matchesLastPlayed(
                com.sluggyard.tv.data.local.CachedStreamLink(
                    url = "",
                    streamName = "Movie",
                    headers = emptyMap(),
                    cachedAtMs = 1L,
                    infoHash = "HASH1",
                    fileIdx = 2,
                ),
            ),
        )
        assertTrue(
            candidate("file-only", cacheState = StreamCacheState.CACHED)
                .copy(filename = "Movie.1080p.mkv")
                .matchesLastPlayed(
                    com.sluggyard.tv.data.local.CachedStreamLink(
                        url = "",
                        streamName = "other",
                        headers = emptyMap(),
                        cachedAtMs = 1L,
                        filename = "Movie.1080p.mkv",
                    ),
                ),
        )
        // Same torrent, wrong file index must not fall through to shared filename.
        assertFalse(
            c.matchesLastPlayed(
                com.sluggyard.tv.data.local.CachedStreamLink(
                    url = "",
                    streamName = "other",
                    headers = emptyMap(),
                    cachedAtMs = 1L,
                    infoHash = "HASH1",
                    fileIdx = 9,
                    filename = "Movie.1080p.mkv",
                ),
            ),
        )
        // Generic stream titles must not count as identity.
        assertFalse(
            c.copy(title = "1080p WEB-DL")
                .matchesLastPlayed(
                    com.sluggyard.tv.data.local.CachedStreamLink(
                        url = "",
                        streamName = "1080p WEB-DL",
                        headers = emptyMap(),
                        cachedAtMs = 1L,
                    ),
                ),
        )
    }

    @Test
    fun softsubEarlyExitAcceptsUnmarkedSoftsubsWhenPreferredLanguageIsSet() {
        // Preferred=en but release only says Softsubs (no Eng tag) → softsubFit=2.
        // Requiring >=3 used to keep anime Play waiting on AIOStreams for the hard ceiling.
        val soft = candidate("soft", cacheState = StreamCacheState.CACHED, infoHash = "soft-hash")
            .copy(title = "Show.S01E01.1080p.WEB-DL.Softsubs")
        val context = StreamScoringEngine.Context(
            title = "Anime Show",
            contentType = "series",
            genres = listOf("Anime"),
            preferredSubtitleLanguage = "en",
        )
        assertTrue(hasEligibleSoftsubAutoPlay(groups(soft), context))
        assertEquals(2, StreamScoringEngine.rank(soft, context).softsubFit)
    }

    @Test
    fun softsubEarlyExitRejectsRawWhenPreferredLanguageIsSet() {
        val raw = candidate("raw", cacheState = StreamCacheState.CACHED, infoHash = "raw-hash")
            .copy(title = "Show.S01E01.1080p.WEB-DL.RAW")
        val context = StreamScoringEngine.Context(
            title = "Anime Show",
            contentType = "series",
            genres = listOf("Anime"),
            preferredSubtitleLanguage = "en",
        )
        assertFalse(hasEligibleSoftsubAutoPlay(groups(raw), context))
    }

    @Test
    fun cachedReadyIsTrueForAnyCachedTorrentWithoutFullRanking() {
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")
            .copy(title = "Movie.1080p.WEB-DL")
        val context = StreamScoringEngine.Context(title = "Movie", contentType = "movie")
        assertTrue(hasEligibleCachedAutoPlay(groups(cached), context))
        assertFalse(
            hasEligibleCachedAutoPlay(
                groups(candidate("uncached", cacheState = StreamCacheState.NOT_CACHED, infoHash = "x")),
                context,
            ),
        )
    }

    @Test
    fun autoPickScoresOnlyCachedPoolEvenWhenHundredsOfUncachedExist() {
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")
            .copy(title = "Movie.2160p.WEB-DL", seeders = 10)
        val uncached = (1..200).map { i ->
            candidate("u$i", cacheState = StreamCacheState.NOT_CACHED, infoHash = "hash-$i")
                .copy(title = "Movie.2160p.BluRay", seeders = 9_000)
        }
        val context = StreamScoringEngine.Context(title = "Movie", contentType = "movie")
        assertEquals(cached, selectAutoPlayCandidate(groups(*(listOf(cached) + uncached).toTypedArray()), context))
    }

    private fun groups(vararg candidates: StreamCandidate) = listOf(
        StreamGroup("source", "Source", StreamGroupState.Content(candidates.toList())),
    )

    private fun candidate(
        id: String,
        directUrl: String? = null,
        infoHash: String? = null,
        cacheState: StreamCacheState = StreamCacheState.NOT_APPLICABLE,
    ) = StreamCandidate(id, id, "Source", null, cacheState, directUrl, infoHash)
}
