package com.sluggyard.tv.ui.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryWatchCodecTest {
    @Test
    fun `codec keeps library and watched states independent`() {
        val state = LibraryWatchState(libraryIds = setOf("movie"), watchedIds = setOf("episode"))

        val restored = LibraryWatchCodec.decode(LibraryWatchCodec.encode(state))

        assertEquals(setOf("movie"), restored.libraryIds)
        assertEquals(setOf("episode"), restored.watchedIds)
    }

    @Test
    fun `corrupted stored state safely resets`() {
        assertTrue(LibraryWatchCodec.decode("invalid").libraryIds.isEmpty())
        assertNull(LibraryWatchCodec.decodeOrNull("invalid"))
    }

    @Test
    fun `library entries are bounded when decoded`() {
        val state = LibraryWatchState(
            libraryEntries = (0..500).map { index ->
                LibraryEntry("id-$index", "Title $index", changedAtEpochMs = index.toLong())
            },
        )

        val restored = LibraryWatchCodec.decode(LibraryWatchCodec.encode(state))

        assertEquals(500, restored.libraryEntries.size)
        assertEquals("id-500", restored.libraryEntries.first().id)
    }
}
