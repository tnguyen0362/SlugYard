package com.sluggyard.tv.ui.app.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class MatroskaSubtitleCodecProbeTest {

    private fun pack(vararg chunks: String): ByteArray {
        val ebml = byteArrayOf(0x1A, 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
        val rest = chunks.joinToString("\u0000").toByteArray(StandardCharsets.US_ASCII)
        return ebml + rest
    }

    @Test
    fun `detects ASS and SRT CodecIDs in EBML prefix`() {
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(
            pack("junk", "S_TEXT/ASS", "S_TEXT/UTF8", "A_AAC"),
        )
        assertTrue(inv.isEbml)
        assertTrue(inv.hasAss)
        assertTrue(inv.hasSrt)
        assertTrue(inv.confidentSoftInventory)
        assertFalse(inv.hasPgs)
    }

    @Test
    fun `detects PGS`() {
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(pack("S_HDMV/PGS"))
        assertTrue(inv.hasPgs)
        assertTrue(inv.confidentSoftInventory)
        assertFalse(
            MatroskaSubtitleCodecProbe.shouldRejectAnimeAutoPick(inv, remainingResolveAttemptsAfterThis = 2),
        )
    }

    @Test
    fun `pure SRT rejects anime auto when retries remain`() {
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(pack("S_TEXT/UTF8"))
        assertTrue(inv.hasSrt)
        assertFalse(inv.hasAss)
        assertTrue(
            MatroskaSubtitleCodecProbe.shouldRejectAnimeAutoPick(inv, remainingResolveAttemptsAfterThis = 1),
        )
        assertFalse(
            MatroskaSubtitleCodecProbe.shouldRejectAnimeAutoPick(inv, remainingResolveAttemptsAfterThis = 0),
        )
    }

    @Test
    fun `empty or non-EBML fails open`() {
        val empty = MatroskaSubtitleCodecProbe.scanPrefix(ByteArray(0))
        assertFalse(empty.confidentSoftInventory)
        assertFalse(
            MatroskaSubtitleCodecProbe.shouldRejectAnimeAutoPick(empty, remainingResolveAttemptsAfterThis = 2),
        )
        val mp4ish = "ftypisom".toByteArray()
        val non = MatroskaSubtitleCodecProbe.scanPrefix(mp4ish)
        assertFalse(non.isEbml)
        assertFalse(non.confidentSoftInventory)
    }

    @Test
    fun `toObserved maps flags for rank memory`() {
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(pack("S_TEXT/UTF8"))
        val obs = MatroskaSubtitleCodecProbe.toObservedTracks(inv)
        assertTrue(obs.hasSrt)
        assertTrue(obs.hasSoftsubTrack)
        assertFalse(obs.hasAss)
        val (soft, _) = obs.toTitleScaleSignals(preferredSubtitleLanguage = "en", animeLike = true)
        // Preferred miss (no lang tags from CodecID) + SRT → soft=2 path
        assertEquals(2, soft)
    }
}
