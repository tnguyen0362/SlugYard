package com.sluggyard.tv.ui.rewrite.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteHomeFocusPolicyTest {
    @Test
    fun heroDownEntersContinueWatchingWhenPresent() {
        assertEquals(
            0,
            heroDownCatalogRowIndex(listOf("continue_watching", "popular")),
        )
    }

    @Test
    fun heroDownUsesFirstCatalogRowWhenContinueWatchingIsAbsent() {
        assertEquals(
            0,
            heroDownCatalogRowIndex(listOf("popular", "family")),
        )
    }
}
