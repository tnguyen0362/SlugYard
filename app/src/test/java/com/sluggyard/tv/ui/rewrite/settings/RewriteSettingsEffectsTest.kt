package com.sluggyard.tv.ui.rewrite.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewriteSettingsEffectsTest {
    @Test
    fun posterLabelsStayVisibleOnlyWhenEnabledOrFocused() {
        assertTrue(shouldShowPosterLabel(showLabels = true, focused = false))
        assertTrue(shouldShowPosterLabel(showLabels = true, focused = true))
        assertFalse(shouldShowPosterLabel(showLabels = false, focused = false))
        assertTrue(shouldShowPosterLabel(showLabels = false, focused = true))
    }

    @Test
    fun profileSelectionFallsBackToFirstProfileWhenRememberLastIsDisabled() {
        assertEquals("second", effectiveProfileId("second", true, listOf("first", "second")))
        assertEquals("first", effectiveProfileId("second", false, listOf("first", "second")))
        assertEquals("default", effectiveProfileId("missing", false, emptyList()))
    }
}
