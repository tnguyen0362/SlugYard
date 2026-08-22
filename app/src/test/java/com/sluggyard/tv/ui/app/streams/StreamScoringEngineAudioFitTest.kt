package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.data.local.PreferredAudioLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamScoringEngineAudioFitTest {

    private fun groups(vararg candidates: StreamCandidate) = listOf(
        StreamGroup("source", "Source", StreamGroupState.Content(candidates.toList())),
    )

    private fun candidate(
        id: String,
        directUrl: String? = null,
        infoHash: String? = null,
        cacheState: StreamCacheState = StreamCacheState.NOT_APPLICABLE,
    ) = StreamCandidate(id, id, "Source", null, cacheState, directUrl, infoHash)

    private fun context(pref: PreferredAudioLayout) =
        StreamScoringEngine.Context(title = "Show", contentType = "movie", preferredAudioLayout = pref)

    @Test
    fun `preferred 5_1 breaks quality tie in favor of 5_1 title`() {
        val fiveOne = candidate("a", directUrl = "https://t/a").copy(title = "Show 2024 1080p BluRay 5.1 x264")
        val plain = candidate("b", directUrl = "https://t/b").copy(title = "Show 2024 1080p BluRay x264")
        assertEquals(
            fiveOne,
            selectAutoPlayCandidate(groups(fiveOne, plain), context(PreferredAudioLayout.SURROUND_5_1)),
        )
    }

    @Test
    fun `preferred 7_1 picks Atmos over compatible 5_1`() {
        val atmos = candidate("a", directUrl = "https://t/a").copy(title = "Show 1080p Atmos")
        val fiveOne = candidate("b", directUrl = "https://t/b").copy(title = "Show 1080p 5.1")
        assertEquals(
            atmos,
            selectAutoPlayCandidate(groups(atmos, fiveOne), context(PreferredAudioLayout.SURROUND_7_1)),
        )
    }

    @Test
    fun `compatible surround breaks tie over unknown layout`() {
        val sevenOne = candidate("a", directUrl = "https://t/a").copy(title = "Show 1080p 7.1")
        val plain = candidate("b", directUrl = "https://t/b").copy(title = "Show 1080p")
        assertEquals(
            sevenOne,
            selectAutoPlayCandidate(groups(sevenOne, plain), context(PreferredAudioLayout.SURROUND_5_1)),
        )
    }

    @Test
    fun `no preference keeps quality dominance`() {
        val uhd = candidate("u", directUrl = "https://t/u").copy(title = "Show 2160p x265")
        val fiveOne = candidate("f", directUrl = "https://t/f").copy(title = "Show 1080p 5.1 x264")
        // audioFit is 0 for every candidate when the preference is NONE — pure quality decides.
        assertEquals(
            uhd,
            selectAutoPlayCandidate(groups(uhd, fiveOne), StreamScoringEngine.Context(title = "Show", contentType = "movie")),
        )
    }

    @Test
    fun `observed channels override release title`() {
        val titleSays51 = candidate("a", directUrl = "https://t/a", infoHash = "hash-a")
            .copy(title = "Show 1080p 5.1")
        val plain = candidate("b", directUrl = "https://t/b")
            .copy(title = "Show 1080p")
        val observed = observedReleaseTracksFromPlayer(
            audioLanguages = listOf("eng"),
            subtitleTracks = emptyList(),
        ).copy(audioCodecs = setOf("A_AC3"), audioChannels = setOf(2))
        val withMemory = context(PreferredAudioLayout.SURROUND_5_1)
            .copy(observedTracksByHash = mapOf("hash-a" to observed))
        // Same quality, so audioFit decides: title claims 5.1 but probed memory says stereo →
        // mismatch (0) vs unknown (1) → plain wins.
        assertEquals(plain, selectAutoPlayCandidate(groups(titleSays51, plain), withMemory))
    }

    @Test
    fun `stereo preference downgrades surround to mismatch`() {
        val stereo = candidate("a", directUrl = "https://t/a").copy(title = "Show 1080p 2.0 Stereo")
        val fiveOne = candidate("b", directUrl = "https://t/b").copy(title = "Show 1080p 5.1")
        assertEquals(
            stereo,
            selectAutoPlayCandidate(groups(stereo, fiveOne), context(PreferredAudioLayout.STEREO)),
        )
    }
}