package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesSearchPolicyTest {
    @Test
    fun `forced search works without a preferred language`() {
        assertTrue(shouldSearch(preferredLanguage = "", forceSearch = true))
        assertTrue(shouldSearch(preferredLanguage = "none", forceSearch = true))
    }

    @Test
    fun `ordinary search requires an uncovered preferred language`() {
        assertFalse(shouldSearch(preferredLanguage = "", forceSearch = false))
        assertFalse(shouldSearch(preferredLanguage = "none", forceSearch = false))
        assertFalse(shouldSearch(preferredLanguage = "eng", preferredLanguageCovered = true))
        assertTrue(shouldSearch(preferredLanguage = "eng"))
    }

    @Test
    fun `search still requires configuration and an API key`() {
        assertFalse(shouldSearch(preferredLanguage = "eng", isConfigured = false))
        assertFalse(shouldSearch(preferredLanguage = "eng", hasApiKey = false))
        assertFalse(shouldSearch(preferredLanguage = "", forceSearch = true, hasApiKey = false))
    }

    private fun shouldSearch(
        preferredLanguage: String,
        preferredLanguageCovered: Boolean = false,
        forceSearch: Boolean = false,
        isConfigured: Boolean = true,
        hasApiKey: Boolean = true,
    ): Boolean = shouldSearchOpenSubtitles(
        isConfigured = isConfigured,
        hasApiKey = hasApiKey,
        preferredLanguage = preferredLanguage,
        preferredLanguageCovered = preferredLanguageCovered,
        forceSearch = forceSearch,
    )
}
