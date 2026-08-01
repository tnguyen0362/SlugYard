package com.sluggyard.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for the Phase 1 pre-ExoPlayer payload validator.
 *
 * Only the pure [StreamPayloadValidator.classifyPayload] logic is exercised here; the network
 * probe is covered by the architecture note in [StreamPayloadValidator] (peek-safe by virtue
 * of being a standalone request) and is not unit tested to avoid real I/O.
 */
class StreamPayloadValidatorTest {

    private fun probe(
        httpStatus: Int = 200,
        contentType: String? = null,
        bytes: ByteArray = ByteArray(0),
        finalUrl: String = "https://comet.example/stream.mkv",
        contentLength: Long = -1L
    ): PayloadProbe = PayloadProbe(httpStatus, contentType, finalUrl, contentLength, bytes)

    private val ebml = byteArrayOf(
        0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(),
        0x00, 0x00, 0x00, 0x00,
    )

    // ── Valid Matroska prefix ───────────────────────────────────────────────────

    @Test
    fun `valid matroska prefix with matroska expectation is accepted`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "video/x-matroska", bytes = ebml),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertEquals(PayloadVerdict.Valid, verdict)
    }

    @Test
    fun `valid webm prefix with webm expectation is accepted`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "video/webm", bytes = ebml),
            expectedMimeType = MimeTypes.VIDEO_WEBM
        )
        assertEquals(PayloadVerdict.Valid, verdict)
    }

    @Test
    fun `valid matroska prefix without explicit mime is accepted via known magic`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = ebml),
            expectedMimeType = null
        )
        assertEquals(PayloadVerdict.Valid, verdict)
    }

    // ── HTML / error payload (the diagnosed Comet case) ─────────────────────────

    @Test
    fun `html error body labeled matroska is rejected`() {
        val html = "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>".toByteArray()
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "text/html", bytes = html),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue("expected Invalid, got $verdict", verdict is PayloadVerdict.Invalid)
    }

    @Test
    fun `html error body with generic content type is rejected`() {
        val html = "<html><head><title>Expired</title></head></html>".toByteArray()
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "application/octet-stream", bytes = html),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
        assertEquals("html-or-xml-error-body", (verdict as PayloadVerdict.Invalid).reason)
    }

    @Test
    fun `json error body labeled matroska is rejected`() {
        val json = "{\"error\":\"stream expired\"}".toByteArray()
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "application/json", bytes = json),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
    }

    @Test
    fun `xml error page labeled matroska is rejected`() {
        val xml = "<?xml version=\"1.0\"?><error>not found</error>".toByteArray()
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = xml),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
    }

    @Test
    fun `html body with leading whitespace is still detected`() {
        val html = "   \n  <html><body>error</body></html>".toByteArray()
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = html),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
    }

    // ── Empty / truncated payload ───────────────────────────────────────────────

    @Test
    fun `empty body is rejected as truncated`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = ByteArray(0)),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
        assertEquals("empty-or-truncated", (verdict as PayloadVerdict.Invalid).reason)
    }

    @Test
    fun `truncated body shorter than magic is rejected when matroska expected`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = byteArrayOf(0x1A, 0x45)), // too short for EBML
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
    }

    // ── Non-2xx status ───────────────────────────────────────────────────────────

    @Test
    fun `non-2xx http status is rejected regardless of bytes`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(httpStatus = 403, bytes = ebml),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
        assertEquals("http-status-403", (verdict as PayloadVerdict.Invalid).reason)
    }

    @Test
    fun `404 status is rejected`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(httpStatus = 404, bytes = "not found".toByteArray()),
            expectedMimeType = null
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
    }

    // ── Non-Comet / unknown cases (must not be falsely rejected) ─────────────────

    @Test
    fun `valid mp4 ftyp prefix is accepted`() {
        // size(4) + "ftyp" + ...
        val mp4 = byteArrayOf(
            0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00
        )
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "video/mp4", bytes = mp4),
            expectedMimeType = MimeTypes.VIDEO_MP4
        )
        assertEquals(PayloadVerdict.Valid, verdict)
    }

    @Test
    fun `valid mpegts prefix is accepted`() {
        val ts = ByteArray(256).also { it[0] = 0x47; it[188] = 0x47 }
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = ts),
            expectedMimeType = null
        )
        assertEquals(PayloadVerdict.Valid, verdict)
    }

    @Test
    fun `unknown bytes without error signal are indeterminate not rejected`() {
        // Random-ish bytes that are neither a known magic nor an HTML/JSON error prefix.
        val unknown = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x09, 0x0A, 0x0B, 0x0C)
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = unknown),
            expectedMimeType = null
        )
        assertTrue("expected Indeterminate, got $verdict", verdict is PayloadVerdict.Indeterminate)
    }

    @Test
    fun `unknown bytes with matroska expectation are rejected`() {
        // Bytes that are not EBML and not an error page; matroska expectation tightens the gate.
        val unknown = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x09, 0x0A, 0x0B, 0x0C)
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = unknown),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertTrue(verdict is PayloadVerdict.Invalid)
        assertEquals("expected-matroska-but-not-ebml", (verdict as PayloadVerdict.Invalid).reason)
    }

    @Test
    fun `text content type with real media bytes is not rejected`() {
        // A mislabeled content-type must not override real media magic bytes.
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "text/plain", bytes = ebml),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertEquals(PayloadVerdict.Valid, verdict)
    }

    @Test
    fun `valid matroska prefix with 206 partial content status is accepted`() {
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(httpStatus = 206, contentType = "video/x-matroska", bytes = ebml),
            expectedMimeType = MimeTypes.VIDEO_MATROSKA
        )
        assertEquals(PayloadVerdict.Valid, verdict)
    }

    // ── Short text/plain error body regression (Gateway timeout) ───────────────

    @Test
    fun `short text plain gateway timeout body is rejected`() {
        // A short text/plain error body (e.g. a CDN/gateway timeout page) must be rejected
        // and must not be misclassified as MPEG-TS just because it happens to start with a
        // 0x47 byte. The content-type is a likely-error type and the bytes are not a known
        // media magic, so the content-type gate fires.
        val body = "Gateway timeout".toByteArray()
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(contentType = "text/plain", bytes = body),
            expectedMimeType = null
        )
        assertTrue("expected Invalid, got $verdict", verdict is PayloadVerdict.Invalid)
        assertEquals("content-type-text/plain", (verdict as PayloadVerdict.Invalid).reason)
    }

    @Test
    fun `short body starting with ts sync byte is not accepted as mpegts`() {
        // A body shorter than 188 bytes cannot be a real MPEG-TS stream even if its first
        // byte is the 0x47 sync byte — the detector must require at least 188 bytes and a
        // second sync byte at offset 188.
        val shortTs = ByteArray(64).also { it[0] = 0x47 }
        val verdict = StreamPayloadValidator.classifyPayload(
            probe(bytes = shortTs),
            expectedMimeType = null
        )
        // Not a known media magic and no error signal → indeterminate, NOT Valid.
        assertTrue("expected Indeterminate, got $verdict", verdict is PayloadVerdict.Indeterminate)
    }
}
