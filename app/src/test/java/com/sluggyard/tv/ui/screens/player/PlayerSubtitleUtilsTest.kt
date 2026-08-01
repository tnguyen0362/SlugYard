package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSubtitleUtilsTest {
    @Test
    fun assDialoguePreferenceScoreRanksDialogueAboveSigns() {
        assertEquals(
            true,
            PlayerSubtitleUtils.assDialoguePreferenceScore("English Dialogue") <
                PlayerSubtitleUtils.assDialoguePreferenceScore("English Signs and Songs"),
        )
        assertEquals(
            true,
            PlayerSubtitleUtils.assDialoguePreferenceScore("eng") <
                PlayerSubtitleUtils.assDialoguePreferenceScore("Signs"),
        )
    }

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

    @Test
    fun subtitleTrackLabelKeepsForcedSdhAndDialogueRoles() {
        val (title, detail) = PlayerSubtitleUtils.formatSubtitleTrackLabel(
            languageDisplay = "English",
            formatTag = "ASS",
            name = "English SDH Dialogue",
            isForced = false,
            isSignsAndSongs = false,
        )
        assertEquals("English ASS", title)
        assertEquals("Dialogue · SDH", detail)
    }

    @Test
    fun subtitleTrackLabelKeepsSignsAndSongsAndForced() {
        val (_, detail) = PlayerSubtitleUtils.formatSubtitleTrackLabel(
            languageDisplay = "English",
            formatTag = "ASS",
            name = "English Signs & Songs Forced",
            isForced = true,
            isSignsAndSongs = true,
        )
        assertEquals("Forced · Signs & Songs", detail)
    }
}
