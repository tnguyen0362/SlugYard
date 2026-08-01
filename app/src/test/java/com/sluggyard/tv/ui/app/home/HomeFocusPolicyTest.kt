package com.sluggyard.tv.ui.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFocusPolicyTest {
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

    @Test
    fun horizontalBringIntoViewPinsLeadingEdgeToInset() {
        // Item sitting at x=400 should scroll left by 400 - inset so leading edge lands at inset.
        assertEquals(
            342f,
            homeCatalogHorizontalScrollDistance(offset = 400f, leadingInsetPx = 58f),
            0.01f,
        )
    }

    @Test
    fun horizontalBringIntoViewIsZeroWhenAlreadyPinned() {
        assertEquals(
            0f,
            homeCatalogHorizontalScrollDistance(offset = 58f, leadingInsetPx = 58f),
            0.01f,
        )
    }

    @Test
    fun verticalBringIntoViewStillClearsNavInset() {
        assertEquals(
            -40f,
            homeCatalogVerticalScrollDistance(
                offset = 36f,
                size = 300f,
                containerSize = 1080f,
                topInsetPx = 76f,
                bottomInsetPx = 48f,
            ),
            0.01f,
        )
    }
}
