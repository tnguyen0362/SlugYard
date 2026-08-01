package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipLookupIdTest {
    @Test
    fun `episode IMDb IDs are reduced to the series ID for skip providers`() {
        assertEquals("tt1234567", normalizeSkipLookupId("tt1234567:2:4"))
    }

    @Test
    fun `non IMDb provider IDs are preserved`() {
        assertEquals("kitsu:987:2:4", normalizeSkipLookupId("kitsu:987:2:4"))
    }
}
