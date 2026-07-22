package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteHomeSettingsCodecTest {
    @Test
    fun `codec preserves visibility choices`() {
        val settings = RewriteHomeSettings(
            hideUnreleased = true,
            excludedCatalogKeys = setOf(RewriteHomeSettings.catalogKey("one", "two")),
            catalogOrderKeys = listOf(
                RewriteHomeSettings.catalogKey("second", "featured"),
                RewriteHomeSettings.catalogKey("first", "popular"),
            ),
        )
        assertEquals(settings, RewriteHomeSettingsCodec.decode(RewriteHomeSettingsCodec.encode(settings)))
    }

    @Test
    fun `saved order ignores malformed keys and duplicate entries`() {
        val settings = RewriteHomeSettings(
            catalogOrderKeys = listOf(
                "broken",
                RewriteHomeSettings.catalogKey("one", "popular"),
                RewriteHomeSettings.catalogKey("one", "popular"),
                RewriteHomeSettings.catalogKey("two", "featured"),
            ),
        )

        assertEquals(
            listOf(HomeCatalogKey("one", "popular"), HomeCatalogKey("two", "featured")),
            settings.savedCatalogOrder(),
        )
    }
}
