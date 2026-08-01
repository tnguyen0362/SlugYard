package com.sluggyard.tv.ui.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {
    @Test
    fun `codec restores a neutral active profile`() {
        val state = ProfileState(
            profiles = listOf(Profile("a", "Alex"), Profile("b", "Sam")),
            activeProfileId = "b",
            rememberLastProfile = false,
        )

        assertEquals(state, ProfileCodec.decode(ProfileCodec.encode(state)))
    }

    @Test
    fun `invalid state falls back to a usable profile`() {
        val restored = ProfileCodec.decode("bad")

        assertEquals("Viewer", restored.activeProfile.name)
        assertTrue(restored.profiles.isNotEmpty())
    }
}
