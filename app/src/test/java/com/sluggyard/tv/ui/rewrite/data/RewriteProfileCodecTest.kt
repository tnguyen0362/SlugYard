package com.sluggyard.tv.ui.rewrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteProfileCodecTest {
    @Test
    fun `codec restores a neutral active profile`() {
        val state = RewriteProfileState(
            profiles = listOf(RewriteProfile("a", "Alex"), RewriteProfile("b", "Sam")),
            activeProfileId = "b",
            rememberLastProfile = false,
        )

        assertEquals(state, RewriteProfileCodec.decode(RewriteProfileCodec.encode(state)))
    }

    @Test
    fun `invalid state falls back to a usable profile`() {
        val restored = RewriteProfileCodec.decode("bad")

        assertEquals("Viewer", restored.activeProfile.name)
        assertTrue(restored.profiles.isNotEmpty())
    }
}
