package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerMetaLookupTest {
    @Test
    fun prefersParentIdOverEpisodeShapedContentId() {
        val lookup = PlayerMetaLookup.resolve(
            contentId = "tt0944947:1:2",
            contentType = "series",
            parentId = "tt0944947",
            parentType = "series",
        )
        assertEquals("tt0944947", lookup?.id)
        assertEquals("series", lookup?.type)
    }

    @Test
    fun normalizesEpisodeShapedImdbIdWhenParentMissing() {
        val lookup = PlayerMetaLookup.resolve(
            contentId = "tt0944947:2:5",
            contentType = "series",
            parentId = null,
            parentType = null,
        )
        assertEquals("tt0944947", lookup?.id)
        assertEquals("series", lookup?.type)
    }

    @Test
    fun leavesMovieIdUnchanged() {
        assertEquals("tt0133093", PlayerMetaLookup.normalizeSeriesMetaId("tt0133093"))
    }

    @Test
    fun returnsNullWhenIdentityMissing() {
        assertNull(
            PlayerMetaLookup.resolve(
                contentId = null,
                contentType = null,
                parentId = null,
                parentType = null,
            ),
        )
    }
}
