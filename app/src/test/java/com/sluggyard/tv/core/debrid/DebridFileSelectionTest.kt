package com.sluggyard.tv.core.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridFileSelectionTest {
    @Test
    fun `absolute padded season 1 episode matches 004 not inventory 4`() {
        val correct = "Jujutsu Kaisen - 004 - Curse Womb Must Die.mkv"
        val wrong = "Jujutsu Kaisen - 028 - Hidden Inventory 4.mkv"
        assertTrue(correct.matchesDebridEpisodeFile(1, 4))
        assertFalse(wrong.matchesDebridEpisodeFile(1, 4))
        assertEquals(80, correct.debridEpisodeMatchScore(1, 4))
        assertEquals(-1, wrong.debridEpisodeMatchScore(1, 4))
    }

    @Test
    fun `season 2 requires explicit season marker for absolute packs`() {
        // Absolute "004" must not win S02E04 in series-wide numbered packs.
        assertFalse("Jujutsu Kaisen - 004 - Curse Womb Must Die.mkv".matchesDebridEpisodeFile(2, 4))
        assertTrue("Show.S02E04.mkv".matchesDebridEpisodeFile(2, 4))
    }

    @Test
    fun `standard sxxexx still matches`() {
        assertTrue("show.s01e04.1080p.mkv".matchesDebridEpisodeFile(1, 4))
        assertTrue("show.1x04.mkv".matchesDebridEpisodeFile(1, 4))
    }
}
