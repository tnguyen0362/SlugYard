package com.sluggyard.tv.core.aggregation

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrderingPolicyTest {

    @Test
    fun `saved available catalogs lead and newly discovered catalogs remain visible`() {
        val a = HomeCatalogKey("one", "featured")
        val b = HomeCatalogKey("one", "popular")
        val c = HomeCatalogKey("two", "trending")
        val removed = HomeCatalogKey("old", "gone")

        val result = orderedHomeCatalogs(
            availableInDefaultOrder = listOf(a, b, c),
            savedOrder = listOf(c, removed, c),
        )

        assertEquals(listOf(c, a, b), result)
    }

    @Test
    fun `without a saved order uses manifest and addon declaration order`() {
        val result = orderedHomeCatalogs(
            availableInDefaultOrder = listOf(
                HomeCatalogKey("first", "a"),
                HomeCatalogKey("second", "b"),
            ),
            savedOrder = emptyList(),
        )

        assertEquals(listOf("first", "second"), result.map { it.addonId })
    }

    @Test
    fun `stable hero keeps surviving previous order before appending new candidates`() {
        data class Hero(val id: String)
        val result = stableHeroOrder(
            candidates = listOf(Hero("new"), Hero("kept"), Hero("other")),
            previousKeys = listOf("kept", "removed"),
            keyOf = Hero::id,
            maxItems = 3,
        )

        assertEquals(listOf("kept", "new", "other"), result.map(Hero::id))
    }

    @Test
    fun `hero policy de duplicates source candidates and honors maximum`() {
        data class Hero(val id: String)
        val result = stableHeroOrder(
            candidates = listOf(Hero("a"), Hero("a"), Hero("b"), Hero("c")),
            previousKeys = emptyList(),
            keyOf = Hero::id,
            maxItems = 2,
        )

        assertEquals(listOf("a", "b"), result.map(Hero::id))
    }
}
