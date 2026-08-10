package com.sluggyard.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFetchIdentityTest {
    private val request = SubtitleFetchRequest(type = "series", id = "tt123", videoId = "episode:1")

    @Test
    fun `result is current only for matching generation and source`() {
        val source = SubtitleFetchIdentity(request, "https://source-a", season = 1, episode = 1)

        assertTrue(isCurrentSubtitleFetch(3L, 3L, source, source))
        assertFalse(isCurrentSubtitleFetch(2L, 3L, source, source))
        assertFalse(
            isCurrentSubtitleFetch(
                3L,
                3L,
                source,
                source.copy(streamUrl = "https://source-b"),
            ),
        )
    }
}
