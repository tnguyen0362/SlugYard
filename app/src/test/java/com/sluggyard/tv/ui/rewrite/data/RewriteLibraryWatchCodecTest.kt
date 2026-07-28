package com.sluggyard.tv.ui.rewrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RewriteLibraryWatchCodecTest {
    @Test
    fun `codec keeps library and watched states independent`() {
        val state = RewriteLibraryWatchState(libraryIds = setOf("movie"), watchedIds = setOf("episode"))

        val restored = RewriteLibraryWatchCodec.decode(RewriteLibraryWatchCodec.encode(state))

        assertEquals(setOf("movie"), restored.libraryIds)
        assertEquals(setOf("episode"), restored.watchedIds)
    }

    @Test
    fun `corrupted stored state safely resets`() {
        assertTrue(RewriteLibraryWatchCodec.decode("invalid").libraryIds.isEmpty())
        assertNull(RewriteLibraryWatchCodec.decodeOrNull("invalid"))
    }

    @Test
    fun `library entries are bounded when decoded`() {
        val state = RewriteLibraryWatchState(
            libraryEntries = (0..500).map { index ->
                RewriteLibraryEntry("id-$index", "Title $index", changedAtEpochMs = index.toLong())
            },
        )

        val restored = RewriteLibraryWatchCodec.decode(RewriteLibraryWatchCodec.encode(state))

        assertEquals(500, restored.libraryEntries.size)
        assertEquals("id-500", restored.libraryEntries.first().id)
    }
}
