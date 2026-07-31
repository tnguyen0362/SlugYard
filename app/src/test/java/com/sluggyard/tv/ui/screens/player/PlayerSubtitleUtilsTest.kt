package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSubtitleUtilsTest {
    @Test
    fun dialogueBeatsSignsAndSongsForSameLanguage() {
        val tracks = listOf(
            TrackInfo(
                index = 0,
                name = "English Signs and Songs",
                language = "en",
                isSignsAndSongs = true,
            ),
            TrackInfo(
                index = 1,
                name = "English Dialogue",
                language = "en",
            ),
        )

        assertEquals(
            1,
            PlayerSubtitleUtils.rankSameLanguageSubtitleCandidates(tracks, listOf(0, 1)),
        )
    }

    @Test
    fun explicitNonForcedPreferenceDoesNotRestoreForcedTrack() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English Forced", language = "en", isForced = true),
            TrackInfo(index = 1, name = "English Dialogue", language = "en", isForced = false),
        )

        assertEquals(
            1,
            PlayerSubtitleUtils.rankSameLanguageSubtitleCandidates(
                tracks = tracks,
                candidates = listOf(0, 1),
                preferForced = false,
            ),
        )
    }

    @Test
    fun formatClassificationUsesMimeExtensionAndTitleSignals() {
        assertEquals(
            "ASS",
            PlayerSubtitleUtils.classifySubtitleFormat(sampleMimeType = "application/x-ass"),
        )
        assertEquals(
            "SRT",
            PlayerSubtitleUtils.classifySubtitleFormat(url = "https://example.test/subtitles?file=episode.srt"),
        )
        assertEquals(
            "VTT",
            PlayerSubtitleUtils.classifySubtitleFormat(trackTitle = "English WebVTT"),
        )
        assertEquals(
            null,
            PlayerSubtitleUtils.classifySubtitleFormat(trackTitle = "English Text"),
        )
    }
}
