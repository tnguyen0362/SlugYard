package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline repro using live Torrentio titles captured for Akame ga Kill! S01E01
 * (tt3742982:1:1) on 2026-07-29. No TV / emulator required.
 */
class AkameAutoPickDebugTest {

    private val akameContextOldBroken = StreamScoringEngine.Context(
        title = "Akame ga Kill!",
        contentType = "series",
        genres = listOf("Animation", "Action", "Comedy"),
        // Old AppShell never passed language → classify required ja OR anime keyword.
        language = null,
        preferredSubtitleLanguage = "en",
    )

    private val akameContextFixed = akameContextOldBroken.copy(language = "ja")

    /**
     * Pre-fix AppShell: Animation+series without ja/anime keyword scored as MAINSTREAM
     * (subtitle weight 10). Simulate that by dropping the Animation genre.
     */
    private val akameContextMainstreamSim = StreamScoringEngine.Context(
        title = "Akame ga Kill!",
        contentType = "series",
        genres = listOf("Action", "Comedy"),
        preferredSubtitleLanguage = "en",
    )

    @Test
    fun dumpAkameRanking_animationOnly_vs_withLanguage() {
        val candidates = realAkameCandidates()
        fun dump(label: String, ctx: StreamScoringEngine.Context) {
            val kind = StreamScoringEngine.contentKind(ctx)
            val ranked = StreamScoringEngine.rankedCandidates(candidates, ctx)
            val winner = StreamScoringEngine.choose(candidates, ctx)
            println("==== $label kind=$kind winner=${winner?.title?.line()} ====")
            ranked.take(8).forEachIndexed { i, c ->
                println(
                    "#$i score=${StreamScoringEngine.score(c, ctx)} " +
                        "title=${c.title.line()}",
                )
            }
        }
        dump("pre-fix MAINSTREAM sim (no Animation genre)", akameContextMainstreamSim)
        dump("post-fix Animation+series no lang", akameContextOldBroken)
        dump("post-fix + language=ja", akameContextFixed)
        assertEquals(
            StreamScoringEngine.ContentKind.MAINSTREAM,
            StreamScoringEngine.contentKind(akameContextMainstreamSim),
        )
        // series+Animation must classify as ANIME even without language (current fix)
        assertEquals(
            StreamScoringEngine.ContentKind.ANIME,
            StreamScoringEngine.contentKind(akameContextOldBroken),
        )
    }

    @Test
    fun akameWinnerIsNot4kRemux() {
        val winner = StreamScoringEngine.choose(realAkameCandidates(), akameContextOldBroken)
        requireNotNull(winner)
        val text = winner.title.lowercase()
        assertTrue("winner looked like 4K remux: ${winner.title}", !text.contains("2160") && !text.contains("remux"))
        // Dual-Audio alone is not enough — need real softsub evidence when preferredSub=en.
        assertTrue(
            "winner missing softsub signal: ${winner.title}",
            Regex(
                "multi.?subs?|eng.?sub|multiple.?subtitle|softsub|\\bass\\b|\\bjudas\\b|erai|anime time|rigav1",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(text),
        )
        assertTrue(
            "Dual-Audio-only Kametsu must not win when preferredSub=en: ${winner.title}",
            !text.contains("kametsu"),
        )
        println("Akame winner: ${winner.title.line()}")
    }

    @Test
    fun preferredEnglishSoftsubBeatsDualAudioOnlyKametsu() {
        val candidates = realAkameCandidates()
        val ranked = StreamScoringEngine.rankedCandidates(candidates, akameContextOldBroken)
        val kametsu = candidates.first { it.id == "kametsu" }
        val softsubIds = setOf("animetime", "erai", "judas", "rigav1")
        val bestSoft = ranked.first { it.id in softsubIds }
        assertTrue(
            "expected softsub pack ahead of Kametsu, got #1=${ranked.first().title.line()}",
            StreamScoringEngine.score(bestSoft, akameContextOldBroken) >
                StreamScoringEngine.score(kametsu, akameContextOldBroken),
        )
        assertTrue(
            "auto-pick must choose a softsub pack, got ${ranked.first().id}",
            ranked.first().id in softsubIds,
        )
    }

    @Test
    fun hi10PacksRankBelowHevcSoftsubPacks() {
        val candidates = realAkameCandidates()
        val ranked = StreamScoringEngine.rankedCandidates(candidates, akameContextOldBroken)
        val hevcSoftIds = setOf("judas", "animetime", "erai", "rigav1")
        val hi10Ids = setOf("kametsu", "doki")
        val bestHevcSoft = ranked.first { it.id in hevcSoftIds }
        hi10Ids.forEach { id ->
            val hi10 = candidates.first { it.id == id }
            assertTrue(
                "$id Hi10 should score below ${bestHevcSoft.id}: ${hi10.title.line()}",
                StreamScoringEngine.score(bestHevcSoft, akameContextOldBroken) >
                    StreamScoringEngine.score(hi10, akameContextOldBroken),
            )
        }
        assertTrue(
            "winner must not be Hi10 pack, got ${ranked.first().id}",
            ranked.first().id !in hi10Ids,
        )
    }

    @Test
    fun theaterOnaLosesToMainEpisodeWhenBothCached() {
        val theater = candidate(
            "theater",
            "[FFF] Akame ga Kill Theater - 01 [BD][1080p-FLAC]",
        )
        val episode = candidate(
            "ep",
            "[Judas] Akame ga Kill! - 01.mkv [BD 1080p][HEVC x265 10bit][Dual-Audio][Multi-Subs]",
        )
        val pick = selectAutoPlayCandidate(
            groups(theater, episode),
            akameContextOldBroken,
        )
        assertEquals(episode, pick)
    }

    private fun realAkameCandidates(): List<StreamCandidate> = listOf(
        candidate("judas", "[Judas] Akame ga Kill! (Season 1) [BD 1080p][HEVC x265 10bit][Dual-Audio][Multi-Subs]\n[Judas] Akame ga Kill! - 01.mkv\n👤 96 💾 298.77 MB"),
        candidate("rigav1", "[RigAV1] Akame ga Kill! S01+Specials (COMPLETE) (BD 1080p AV1 Opus) [Dual-Audio] [Multi-Subs]\n[RigAV1] Akame ga Kill! - S01E01.mkv\n👤 55 💾 300.13 MB"),
        candidate("kametsu", "[Kametsu] Akame ga Kill! (BD 1080p Hi10 FLAC) [Dual-Audio]\n[Kametsu] Akame ga Kill! - 01 (BD 1080p Hi10 FLAC).mkv\n👤 43 💾 1.76 GB"),
        candidate("erai", "[Erai-raws] Akame ga Kill! - 01 ~ 24 [1080p][Multiple Subtitle]\n[Erai-raws] Akame ga Kill! - 01 [1080p][Multiple Subtitle].mkv\n👤 26 💾 949.89 MB"),
        candidate("animetime", "[Anime Time] Akame ga Kill (Season 1) [Dual Audio][BD][1080p][HEVC 10bit x265][AAC][Eng Sub]\nAkame ga Kill! - 01.mkv\n👤 19 💾 347.2 MB"),
        candidate("vostfr", "Akame ga Kill! S01E01 VOSTFR 1080p 10bits BluRay x265 AAC -Punisher694\n👤 16 💾 400 MB"),
        candidate("cleo", "[Cleo] Akame ga Kill! [Dual Audio 10bit BD1080p][HEVC-x265]\n[Cleo]Akame_ga_Kill!_-_01_(Dual Audio_10bit_BD1080p_x265).mkv\n👤 12 💾 341.69 MB"),
        candidate("fff-theater", "[FFF] Akame ga Kill Theater - 01 [BD][1080p-FLAC]\n👤 6 💾 28.26 MB"),
        candidate("fff-ep", "[FFF] Akame ga Kill - 01 [BD][1080p-FLAC]\n👤 6 💾 1.34 GB"),
        candidate("doki", "[Doki] Akame ga Kill! - 01 (1920x1080 Hi10P BD FLAC)\n👤 2 💾 684.14 MB"),
        candidate("horrible", "[HorribleRips] Akame ga Kill! - 01 [1080p]\n👤 1 💾 949.8 MB"),
        candidate("720dual", "[AnimeCreed] Akame ga Kill! - 01 [720p][Dual Audio][BD]\n👤 4 💾 97.75 MB"),
    )

    private fun candidate(id: String, title: String) = StreamCandidate(
        id = id,
        title = title,
        sourceLabel = "Torrentio",
        detailLabel = null,
        cacheState = StreamCacheState.CACHED,
        infoHash = "$id-hash",
        metadataText = title,
    )

    private fun groups(vararg streams: StreamCandidate) = listOf(
        StreamGroup(
            addonId = "torrentio",
            addonName = "Torrentio",
            state = StreamGroupState.Content(streams.toList()),
        ),
    )

    private fun String.line(): String = replace('\n', ' ').take(140)
}
