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

    @Test
    fun `EBML with no subtitle CodecIDs rejects auto-pick when dialogue is required`() {
        val audioOnly = ByteArray(MatroskaSubtitleCodecProbe.MIN_CONFIDENT_SCAN_BYTES + 64)
        val ebml = byteArrayOf(0x1A, 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
        System.arraycopy(ebml, 0, audioOnly, 0, ebml.size)
        val aac = "A_AAC".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(aac, 0, audioOnly, 16, aac.size)
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(audioOnly)
        assertTrue(inv.isEbml)
        assertFalse(inv.hasAnySoft)
        assertTrue(inv.confidentNoSoftsubs)
        assertTrue(
            MatroskaSubtitleCodecProbe.shouldRejectAutoPick(
                inventory = inv,
                remainingResolveAttemptsAfterThis = 2,
                requireDialogueSubs = true,
                rejectAnimePureSrt = false,
            ),
        )
        assertFalse(
            MatroskaSubtitleCodecProbe.shouldRejectAutoPick(
                inventory = inv,
                remainingResolveAttemptsAfterThis = 2,
                requireDialogueSubs = false,
                rejectAnimePureSrt = false,
            ),
        )
    }

    @Test
    fun `movie SRT inventory is not rejected`() {
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(pack("S_TEXT/UTF8"))
        assertFalse(
            MatroskaSubtitleCodecProbe.shouldRejectAutoPick(
                inventory = inv,
                remainingResolveAttemptsAfterThis = 2,
                requireDialogueSubs = true,
                rejectAnimePureSrt = false,
            ),
        )
    }

    @Test
    fun `tiny EBML prefix without CodecIDs fails open`() {
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(pack("A_AAC"))
        assertTrue(inv.isEbml)
        assertFalse(inv.confidentNoSoftsubs)
        assertFalse(
            MatroskaSubtitleCodecProbe.shouldRejectAutoPick(
                inventory = inv,
                remainingResolveAttemptsAfterThis = 2,
                requireDialogueSubs = true,
                rejectAnimePureSrt = false,
            ),
        )
    }

    // --- EBML audio walker ---

    private fun vint(value: Int): ByteArray {
        var len = 1
        while (value >= (1 shl (7 * len))) len++
        val out = ByteArray(len)
        var v = value
        for (i in len - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        out[0] = (out[0].toInt() or (1 shl (7 * len))).toByte()
        return out
    }

    private fun element(id: ByteArray, payload: ByteArray): ByteArray =
        id + vint(payload.size) + payload

    private fun audioTrack(codec: String, channels: Int): ByteArray =
        byteArrayOf(0x83.toByte(), 0x81.toByte(), 0x02) +
            element(byteArrayOf(0x86.toByte()), codec.toByteArray(StandardCharsets.US_ASCII)) +
            element(byteArrayOf(0xE1.toByte()), element(byteArrayOf(0x9F.toByte()), byteArrayOf(channels.toByte())))

    private fun subTrack(codec: String): ByteArray =
        byteArrayOf(0x83.toByte(), 0x81.toByte(), 0x11) +
            element(byteArrayOf(0x86.toByte()), codec.toByteArray(StandardCharsets.US_ASCII))

    /** Minimal valid-shaped MKV: EBML header, Segment with UNKNOWN size, one Tracks element. */
    private fun mkvBytes(vararg tracks: ByteArray): ByteArray {
        val header = byteArrayOf(0x1A, 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()) +
            vint(31) + ByteArray(31)
        val tracksPayload = tracks.fold(ByteArray(0)) { acc, t -> acc + element(byteArrayOf(0xAE.toByte()), t) }
        val tracksEl = element(byteArrayOf(0x16, 0x54.toByte(), 0xAE.toByte(), 0x6B), tracksPayload)
        val segment = byteArrayOf(0x18, 0x53.toByte(), 0x80.toByte(), 0x67) +
            byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            tracksEl
        return header + segment
    }

    @Test
    fun `walker reads audio codecs and channels from EBML`() {
        val bytes = mkvBytes(audioTrack("A_AAC", 6), audioTrack("A_EAC3", 8), subTrack("S_TEXT/UTF8"))
        val (codecs, channels) = MatroskaSubtitleCodecProbe.parseTrackAudio(bytes)
        assertEquals(setOf("A_AAC", "A_EAC3"), codecs)
        assertEquals(setOf(6, 8), channels)
    }

    @Test
    fun `walker excludes non-audio tracks`() {
        val bytes = mkvBytes(subTrack("S_TEXT/ASS"))
        val (codecs, channels) = MatroskaSubtitleCodecProbe.parseTrackAudio(bytes)
        assertTrue(codecs.isEmpty())
        assertTrue(channels.isEmpty())
    }

    @Test
    fun `walker handles unknown Segment size`() {
        // Segment size varint = 8-byte all-ones (mkvmerge style) — walker must still find Tracks.
        val bytes = mkvBytes(audioTrack("A_AC3", 6))
        val (codecs, channels) = MatroskaSubtitleCodecProbe.parseTrackAudio(bytes)
        assertEquals(setOf("A_AC3"), codecs)
        assertEquals(setOf(6), channels)
    }

    @Test
    fun `walker fails open on truncated prefix`() {
        val (c1, ch1) = MatroskaSubtitleCodecProbe.parseTrackAudio(ByteArray(0))
        assertTrue(c1.isEmpty())
        assertTrue(ch1.isEmpty())
        val onlyHeader = byteArrayOf(0x1A, 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
        val (c2, ch2) = MatroskaSubtitleCodecProbe.parseTrackAudio(onlyHeader)
        assertTrue(c2.isEmpty())
        assertTrue(ch2.isEmpty())
    }

    @Test
    fun `scanPrefix surfaces audio inventory`() {
        val inv = MatroskaSubtitleCodecProbe.scanPrefix(mkvBytes(audioTrack("A_EAC3", 8)))
        assertTrue(inv.isEbml)
        assertEquals(setOf("A_EAC3"), inv.audioCodecs)
        assertEquals(setOf(8), inv.audioChannels)
        assertFalse(inv.hasAnySoft)
    }
}
