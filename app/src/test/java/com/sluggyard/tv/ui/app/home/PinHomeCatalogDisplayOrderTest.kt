package com.sluggyard.tv.ui.app.home

import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PinHomeCatalogDisplayOrderTest {
    @Test
    fun pinsPopularFeaturedThenTmdbThenRest() {
        val popular = HomeCatalogKey("cinemeta", "top")
        val featured = HomeCatalogKey("cinemeta", "featured")
        val action = HomeCatalogKey(TmdbHomeAddonId, "genre_action")
        val pick = HomeCatalogKey("aiolios", "your_pick")
        val ordered = listOf(pick, action, featured, popular)
        val titles = mapOf(
            popular to "Popular",
            featured to "Featured",
            action to "Action",
            pick to "Your Pick",
        )
        assertEquals(
            listOf(popular, featured, action, pick),
            pinHomeCatalogDisplayOrder(ordered) { titles[it] },
        )
    }
}
