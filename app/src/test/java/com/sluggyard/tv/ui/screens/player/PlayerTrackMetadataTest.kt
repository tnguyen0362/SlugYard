package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackMetadataTest {
    @Test
    fun `unset und language is ignored`() {
        assertTrue(isUnsetLanguageCode("und"))
        assertTrue(isUnsetLanguageCode("UND"))
        assertTrue(isUnsetLanguageCode(null))
        assertFalse(isUnsetLanguageCode("en"))
    }

    @Test
    fun `resolves eng token and english name from und container language`() {
        assertEquals("en", resolveTrackLanguageCode("und", "English"))
        assertEquals("en", resolveTrackLanguageCode(null, "[ENG] Full"))
        assertEquals("ja", resolveTrackLanguageCode("und", "jpn"))
        assertNull(resolveTrackLanguageCode("und", "und"))
        assertNull(resolveTrackLanguageCode(null, "Audio 1"))
    }

    @Test
    fun `display name falls back to track ordinal instead of unknown language`() {
        assertEquals("Track 3", trackLanguageDisplayName("und", "und", ordinal1Based = 3))
        assertEquals("Track 1", trackLanguageDisplayName(null, "Audio 1", ordinal1Based = 1))
        assertEquals("English", trackLanguageDisplayName("und", "English", ordinal1Based = 2))
    }
}
