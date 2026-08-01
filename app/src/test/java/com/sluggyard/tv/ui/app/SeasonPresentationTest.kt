package com.sluggyard.tv.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonPresentationTest {
    @Test
    fun `specials are labeled and sorted after regular seasons`() {
        assertEquals("Specials", seasonDisplayLabel(0))
        assertEquals("Season 2", seasonDisplayLabel(2))
        assertEquals(listOf(1, 2, 0), listOf(0, 2, 1).regularSeasonsThenSpecials { it })
    }
}
