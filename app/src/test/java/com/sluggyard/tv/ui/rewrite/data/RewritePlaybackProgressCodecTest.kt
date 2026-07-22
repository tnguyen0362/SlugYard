package com.sluggyard.tv.ui.rewrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewritePlaybackProgressCodecTest {
    @Test
    fun `round trip preserves a resume checkpoint`() {
        val checkpoint = RewritePlaybackCheckpoint(
            contentId = "tt001",
            contentType = "movie",
            title = "A title",
            posterUrl = "https://image.test/poster.jpg",
            positionMs = 120_000,
            durationMs = 600_000,
            updatedAtEpochMs = 42L,
        )

        assertEquals(listOf(checkpoint), RewritePlaybackProgressCodec.decode(RewritePlaybackProgressCodec.encode(listOf(checkpoint))))
    }

    @Test
    fun `malformed persisted data fails closed`() {
        assertTrue(RewritePlaybackProgressCodec.decode("not json").isEmpty())
    }

    @Test
    fun `invalid persisted checkpoints are ignored`() {
        val valid = RewritePlaybackCheckpoint(
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
            RewritePlaybackProgressCodec.decode(RewritePlaybackProgressCodec.encode(listOf(invalid, valid))),
        )
    }
}
