package com.sluggyard.tv.ui.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressCodecTest {
    @Test
    fun `round trip preserves a resume checkpoint`() {
        val checkpoint = PlaybackCheckpoint(
            contentId = "tt001",
            contentType = "movie",
            title = "A title",
            posterUrl = "https://image.test/poster.jpg",
            positionMs = 120_000,
            durationMs = 600_000,
            updatedAtEpochMs = 42L,
        )

        assertEquals(listOf(checkpoint), PlaybackProgressCodec.decode(PlaybackProgressCodec.encode(listOf(checkpoint))))
    }

    @Test
    fun `malformed persisted data fails closed`() {
        assertTrue(PlaybackProgressCodec.decode("not json").isEmpty())
    }

    @Test
    fun `invalid persisted checkpoints are ignored`() {
        val valid = PlaybackCheckpoint(
            contentId = "tt123",
            contentType = "movie",
            title = "Valid",
            positionMs = 2_000L,
            durationMs = 100_000L,
            updatedAtEpochMs = 2L,
        )
        val invalid = valid.copy(contentId = "", updatedAtEpochMs = 3L)

        assertEquals(
            listOf(valid),
            PlaybackProgressCodec.decode(PlaybackProgressCodec.encode(listOf(invalid, valid))),
        )
    }
}
