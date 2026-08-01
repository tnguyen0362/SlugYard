package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSettingsCodecTest {
    @Test
    fun `codec preserves visibility choices`() {
        val settings = HomeSettings(
            hideUnreleased = true,
            excludedCatalogKeys = setOf(HomeSettings.catalogKey("one", "two")),
            catalogOrderKeys = listOf(
                HomeSettings.catalogKey("second", "featured"),
                HomeSettings.catalogKey("first", "popular"),
            ),
        )
        assertEquals(settings, HomeSettingsCodec.decode(HomeSettingsCodec.encode(settings)))
    }

    @Test
    fun `saved order ignores malformed keys and duplicate entries`() {
        val settings = HomeSettings(
            catalogOrderKeys = listOf(
                "broken",
                HomeSettings.catalogKey("one", "popular"),
                HomeSettings.catalogKey("one", "popular"),
                HomeSettings.catalogKey("two", "featured"),
            ),
        )

        assertEquals(
            listOf(HomeCatalogKey("one", "popular"), HomeCatalogKey("two", "featured")),
            settings.savedCatalogOrder(),
        )
    }
}
