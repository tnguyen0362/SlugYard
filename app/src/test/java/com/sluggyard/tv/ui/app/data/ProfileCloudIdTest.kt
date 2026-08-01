package com.sluggyard.tv.ui.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCloudIdTest {
    @Test
    fun nextCloudProfileId_incrementsPastExistingIntegers() {
        assertEquals("1", nextCloudProfileId(emptyList()))
        assertEquals("3", nextCloudProfileId(listOf("1", "2")))
        // Non-integer legacy ids do not occupy the cloud id space until remapped.
        assertEquals("1", nextCloudProfileId(listOf("default", "uuid-here")))
    }

    @Test
    fun remapNonIntegerProfileIds_mapsDefaultAndUuid() {
        val state = ProfileState(
            profiles = listOf(
                Profile("default", "Viewer"),
                Profile("a1b2c3d4-e5f6-7890-abcd-ef1234567890", "Kids"),
            ),
            activeProfileId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        )
        val (remapped, remaps) = remapNonIntegerProfileIds(state)
        assertEquals("1", remaps["default"])
        assertEquals("2", remaps["a1b2c3d4-e5f6-7890-abcd-ef1234567890"])
        assertEquals(listOf("1", "2"), remapped.profiles.map { it.id })
        assertEquals("2", remapped.activeProfileId)
    }

    @Test
    fun remapNonIntegerProfileIds_noopWhenAlreadyInteger() {
        val state = ProfileState(
            profiles = listOf(Profile("1", "Viewer"), Profile("2", "Kids")),
            activeProfileId = "2",
        )
        val (remapped, remaps) = remapNonIntegerProfileIds(state)
        assertTrue(remaps.isEmpty())
        assertEquals(state, remapped)
    }
}
