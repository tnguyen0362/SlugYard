package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.debrid.StreamTextSeedersParser
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Real-world regression from the Twilight (2008) auto-pick on 2026-11-06: 236 cached TorBox
 * rows, and auto-play chose a Spanish 4-seed "4Kreescalado" upload (which TorBox then failed
 * to prepare), then an Italian 5-film pack, over native English releases.
 */
class StreamAutoPickNativeReleaseTest {

    private val gb = 1024L * 1024 * 1024

    private val englishFilm = StreamScoringEngine.Context(
        title = "Twilight",
        contentType = "movie",
        language = "English",
        preferredSubtitleLanguage = "en",
    )

    private val crepusculoUpscale = cached(
        "crepusculo",
        "Crepusculo [4Kreescalado][2160p][HDR10][AC3 5.1-DTS-HD 5.1 Castellano-DTS-HD 5.1-Ingles+Subs][ES-EN]\n" +
            "👤 4 💾 26.46 GB ⚙️ MejorTorrent",
        seeders = 4,
        sizeGb = 26.46,
    )

    private val italianPack = cached(
        "ita-pack",
        "Twilight Saga (2008-2012) 2160p H265 HDR10 Dolby Vision 1.0 AC3 5.1 ITA.ENG sub ita.eng Sp33dy94 MIR\n" +
            "Twilight 1 (2008) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv\n" +
            "👤 21 💾 18.2 GB ⚙️ ilCorSaRoNeRo",
        seeders = 21,
        sizeGb = 18.2,
    )

    private val native4k = cached(
        "native-4k",
        "Twilight.2008.2160p.UHD.BluRay.x265.10bit.HDR.DTS-HD.MA.5.1-SWTYBLZ\n" +
            "👤 150 💾 21.4 GB ⚙️ TorrentGalaxy",
        seeders = 150,
        sizeGb = 21.4,
    )

    private val popular1080 = cached(
        "popular-1080",
        "Twilight.2008.1080p.BluRay.x264.DTS-HD.MA.5.1-FGT\n👤 800 💾 10.9 GB ⚙️ RARBG",
        seeders = 800,
        sizeGb = 10.9,
    )

    @Test
    fun nativeEnglish4kBeatsUpscaleForeignPackAndPopular1080p() {
        val pick = autoPick(
            groups(crepusculoUpscale, italianPack, native4k, popular1080),
            englishFilm,
        )
        assertEquals("native-4k", pick?.id)
    }

    @Test
    fun tmdbOriginalLanguageCodeWorksLikeCinemetaLanguageName() {
        // On the TV the language comes from TMDB original_language ("en"), not Cinemeta.
        val pick = autoPick(
            groups(crepusculoUpscale, italianPack, native4k, popular1080),
            englishFilm.copy(language = "en"),
        )
        assertEquals("native-4k", pick?.id)
    }

    @Test
    fun withOnlyForeignChoicesTheMultiLanguagePackStillPlays() {
        // No regression to "nothing found": a pack that carries English still auto-plays.
        val pick = autoPick(groups(crepusculoUpscale, italianPack), englishFilm)
        assertEquals("ita-pack", pick?.id)
    }

    @Test
    fun opaqueNativeReleaseAutoPlaysForEnglishFilmWithEnglishSubtitlePreference() {
        // Used to return null during Finding: softsubFit 1 ("no subtitle signal") was refused.
        val pick = autoPick(groups(popular1080), englishFilm)
        assertEquals("popular-1080", pick?.id)
    }

    @Test
    fun foreignLanguageFilmStillRequiresAnEnglishSubtitleSignal() {
        val korean = StreamScoringEngine.Context(
            title = "Parasite",
            contentType = "movie",
            language = "Korean",
            preferredSubtitleLanguage = "en",
        )
        val opaque = cached("opaque", "Parasite.2019.1080p.BluRay.x264-REGRET\n👤 300", 300, 9.0)
        val engSubs = cached("eng", "Parasite.2019.1080p.BluRay.x264.Eng.Subs-GRP\n👤 40", 40, 9.0)

        assertEquals("eng", autoPick(groups(opaque, engSubs), korean)?.id)
        assertNull(autoPick(groups(opaque), korean))
    }

    @Test
    fun rescaledUpscaleLosesToNative1080pOnQuality() {
        val noPreference = StreamScoringEngine.Context(title = "Twilight", contentType = "movie")
        val upscale = cached("up", "Crepusculo [4Kreescalado][2160p][HDR10]", 60, 26.0)
        val native = cached("native", "Twilight.2008.1080p.BluRay.x264-FGT", 60, 10.0)

        assertEquals("native", autoPick(groups(upscale, native), noPreference)?.id)
    }

    @Test
    fun parsesTorrentioSeedersWithoutMistakingResolutionsYearsOrSizes() {
        assertEquals(21, StreamTextSeedersParser.seedersFromText("Twilight 2160p\n👤 21 💾 3.69 GB ⚙️ TPB"))
        assertEquals(4, StreamTextSeedersParser.seedersFromText("👤 4 💾 26.46 GB"))
        assertEquals(12, StreamTextSeedersParser.seedersFromText("Seeders: 12 | 1080p"))
        assertNull(StreamTextSeedersParser.seedersFromText("Ocean's 2160p"))
        assertNull(StreamTextSeedersParser.seedersFromText("Twilight 2009 1080p 26.46 GB"))
        assertNull(StreamTextSeedersParser.seedersFromText("seeds 1080p"))
        assertEquals(
            33,
            StreamTextSeedersParser.seedersFromTexts(null, "Movie.1080p.WEB", "👥 33"),
        )
    }

    private fun cached(id: String, title: String, seeders: Int, sizeGb: Double) =
        StreamCandidate(
            id,
            title,
            "Torrentio",
            null,
            StreamCacheState.CACHED,
            null,
            "$id-hash",
        ).copy(seeders = seeders, videoSizeBytes = (sizeGb * gb).toLong())

    // The TV runs with no size cap (the 26 GB Crepusculo was eligible there); the 15 GB
    // default would hide every 4K row in this fixture.
    private fun autoPick(groups: List<StreamGroup>, context: StreamScoringEngine.Context) =
        selectAutoPlayCandidate(
            groups,
            context,
            linkFilters = StreamLinkFilterSettings(maxSizeGb = null),
        )

    private fun groups(vararg candidates: StreamCandidate) = listOf(
        StreamGroup("source", "Source", StreamGroupState.Content(candidates.toList())),
    )
}
