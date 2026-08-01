package com.sluggyard.tv.ui.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsEffectsTest {
    @Test
    fun profileSelectionFallsBackToFirstProfileWhenRememberLastIsDisabled() {
        assertEquals("second", effectiveProfileId("second", true, listOf("first", "second")))
        assertEquals("first", effectiveProfileId("second", false, listOf("first", "second")))
        assertEquals("1", effectiveProfileId("missing", false, emptyList()))
    }
}
