package com.sluggyard.tv.ui.rewrite.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class RewriteSettingsEffectsTest {
    @Test
    fun profileSelectionFallsBackToFirstProfileWhenRememberLastIsDisabled() {
        assertEquals("second", effectiveProfileId("second", true, listOf("first", "second")))
        assertEquals("first", effectiveProfileId("second", false, listOf("first", "second")))
        assertEquals("1", effectiveProfileId("missing", false, emptyList()))
    }
}
