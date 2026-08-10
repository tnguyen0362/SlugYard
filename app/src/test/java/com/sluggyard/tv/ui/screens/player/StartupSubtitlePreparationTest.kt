package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSubtitlePreparationTest {
    @Test
    fun `preferred-only visibility retains the complete provider response`() {
        val english = subtitle(id = "english", language = "eng")
        val spanish = subtitle(id = "spanish", language = "spa")

        val preparation = completedStartupSubtitlePreparation(
            fetchedSubtitles = listOf(english, spanish),
            attachedSubtitles = listOf(english),
            showOnlyPreferredLanguages = true,
        )

        assertEquals(listOf(english, spanish), preparation.fetchedSubtitles)
        assertEquals(listOf(english), preparation.visibleSubtitles)
    }

    @Test
    fun `disabled visibility filter exposes the complete provider response`() {
        val english = subtitle(id = "english", language = "eng")
        val spanish = subtitle(id = "spanish", language = "spa")

        val preparation = completedStartupSubtitlePreparation(
            fetchedSubtitles = listOf(english, spanish),
            attachedSubtitles = listOf(english),
            showOnlyPreferredLanguages = false,
        )

        assertEquals(listOf(english, spanish), preparation.visibleSubtitles)
    }

    private fun subtitle(id: String, language: String) = Subtitle(
        id = id,
        url = "https://example.com/$id.srt",
        lang = language,
        addonName = "Test",
        addonLogo = null,
    )
}
