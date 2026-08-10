package com.sluggyard.tv.ui.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeVerticalArtworkWarmPolicyTest {
    @Test
    fun `before focus warms first two catalog entry cards`() {
        assertEquals(
            listOf(1, 2),
            homeVerticalWarmRowIndices(rowCount = 5, focusedRowIndex = -1, firstCatalogRowIndex = 1),
        )
    }

    @Test
    fun `focused row warms only its vertical neighbors`() {
        assertEquals(
            listOf(3, 1),
            homeVerticalWarmRowIndices(rowCount = 5, focusedRowIndex = 2, firstCatalogRowIndex = 1),
        )
    }

    @Test
    fun `last row warms wrap target and previous row`() {
        assertEquals(
            listOf(1, 3),
            homeVerticalWarmRowIndices(rowCount = 5, focusedRowIndex = 4, firstCatalogRowIndex = 1),
        )
    }

    @Test
    fun `single catalog is warmed once`() {
        assertEquals(
            listOf(0),
            homeVerticalWarmRowIndices(rowCount = 1, focusedRowIndex = -1, firstCatalogRowIndex = 0),
        )
    }
}
