package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTrackMetadataTest {
    @Test
    fun `uses explicit container language`() {
        assertEquals("jpn", resolveAudioTrackLanguageCode("jpn", "English commentary"))
    }

    @Test
    fun `infers missing language from descriptive track label`() {
        assertEquals("ja", resolveAudioTrackLanguageCode(null, "Japanese 2.0 Opus"))
    }

    @Test
    fun `does not invent language from codec-only label`() {
        assertNull(resolveAudioTrackLanguageCode("und", "Opus 2.0"))
    }
}
