package com.sluggyard.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import com.sluggyard.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Phase 1: narrow pre-ExoPlayer payload validation for progressive HTTP(S) streams.
 *
 * A diagnosed Comet failure handed ExoPlayer a URL labeled
 * `video/x-matroska` whose bytes were not a readable Matroska stream. ExoPlayer then spent
 * several seconds failing inside the extractor (`UnrecognizedInputFormatException`) before
 * the dual-player failover switched to MPV.
 *
 * This validator performs a bounded, separate Range GET before ExoPlayer extractor startup
 * and rejects responses that are *clearly* not media: non-2xx status, HTML/XML/JSON error
 * bodies, empty/truncated payloads, or a Matroska-labeled response whose first bytes do not
 * contain the EBML magic. It only returns [PayloadVerdict.Invalid] on strong evidence; any
 * probe failure or ambiguous result returns [PayloadVerdict.Indeterminate] so playback is
 * never blocked and the existing ExoPlayer / MPV paths are preserved.
 *
 * Peek-safety: the probe is a standalone HTTP request distinct from ExoPlayer's
 * [androidx.media3.datasource.DataSource]. It does not consume or replay ExoPlayer's stream.
 * No credentials or full signed URLs are logged; only the redacted [urlForLog] / host are used.
 */
internal object StreamPayloadValidator {
    private const val TAG = "StreamPayloadValidator"

    /** Maximum number of leading bytes read for magic-byte sniffing. */
    internal const val PROBE_BYTE_LIMIT = 4096

    /** Wall-clock budget for the entire probe (connect + read). */
    internal const val PROBE_TIMEOUT_MS = 6000L

    // EBML header magic — Matroska (.mkv) and WebM containers.
    internal val EBML_MAGIC = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())

    // "ftyp" box marker for ISO BMFF / MP4 / MOV, located at offset 4.
    internal val FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte())

    // "FLV" header for Flash Video.
    internal val FLV_MAGIC = byteArrayOf(0x46.toByte(), 0x4C.toByte(), 0x56.toByte())

    // MPEG-TS sync byte (0x47) repeated every 188 bytes.
    internal const val TS_SYNC: Byte = 0x47

    /**
     * Pure classification of a response probe. Kept free of any network/Android dependency
     * so it can be unit tested directly. [expectedMimeType] is the MIME the pipeline inferred
     * for the URL (e.g. [androidx.media3.common.MimeTypes.VIDEO_MATROSKA]); it is only used to
     * tighten the Matroska-specific check and never to reject a stream that merely lacks it.
     */
    fun classifyPayload(
        probe: PayloadProbe,
        expectedMimeType: String?
    ): PayloadVerdict {
        // 1. HTTP status — redirects are followed by the probe client, so this is the final
        //    response code. Non-2xx is a hard reject.
        if (probe.httpStatus !in 200..299) {
            return PayloadVerdict.Invalid("http-status-${probe.httpStatus}")
        }

        val contentType = probe.contentType?.substringBefore(';')?.trim()?.lowercase()
        val bytes = probe.initialBytes

        // 2. Empty / truncated body — a progressive media stream must have at least a few
        //    container bytes. Treat zero bytes as a clear invalid payload.
        if (bytes.isEmpty()) {
            return PayloadVerdict.Invalid("empty-or-truncated")
        }

        // 3. Content-Type strongly indicates a non-media error body. We only reject when the
        //    bytes also fail to look like known media (content-type alone can be mislabeled by
        //    CDN/proxy layers, so we require byte-level confirmation).
        if (contentType != null && isLikelyErrorContentType(contentType) &&
            !looksLikeKnownMedia(bytes)
        ) {
            return PayloadVerdict.Invalid("content-type-$contentType")
        }

        // 4. Byte-level HTML/XML/JSON error-page detection. This catches the Comet case where
        //    a redirect/error page is served with a generic or missing content-type.
        if (looksLikeHtmlOrXmlError(bytes) && !looksLikeKnownMedia(bytes)) {
            return PayloadVerdict.Invalid("html-or-xml-error-body")
        }

        // 5. Matroska/WebM expectation: the first bytes MUST begin with the EBML magic. This is
        //    the exact diagnosed Comet failure (labeled matroska, non-EBML bytes).
        if (expectedMimeType != null && isMatroskaFamilyMime(expectedMimeType)) {
            if (!startsWithMagic(bytes, EBML_MAGIC)) {
                return PayloadVerdict.Invalid("expected-matroska-but-not-ebml")
            }
            return PayloadVerdict.Valid
        }

        // 6. A known media magic is present — accept.
        if (looksLikeKnownMedia(bytes)) {
            return PayloadVerdict.Valid
        }

        // 7. No strong invalidity signal and no known magic. Do not guess: let ExoPlayer try.
        return PayloadVerdict.Indeterminate("no-known-magic-no-strong-error-signal")
    }

    private fun isMatroskaFamilyMime(mime: String): Boolean {
        val m = mime.lowercase()
        return m == "video/x-matroska" || m == "audio/x-matroska" ||
            m == "video/mkv" || m == "audio/mkv" ||
            m == "video/webm" || m == "audio/webm"
    }

    private fun isLikelyErrorContentType(ct: String): Boolean =
        ct == "text/html" || ct.startsWith("text/html") ||
            ct == "application/json" ||
            ct == "text/plain" ||
            ct == "text/xml" || ct == "application/xml" ||
            ct == "application/xhtml+xml"

    /**
     * Returns true when the leading bytes match a recognized media container magic. Conservative
     * by design: only well-known, unambiguous signatures are accepted.
     */
    internal fun looksLikeKnownMedia(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // Matroska / WebM (EBML).
        if (startsWithMagic(bytes, EBML_MAGIC)) return true
        // ISO BMFF / MP4 / MOV: size(4) + "ftyp".
        if (bytes.size >= 8 &&
            bytes[4] == FTYP[0] && bytes[5] == FTYP[1] &&
            bytes[6] == FTYP[2] && bytes[7] == FTYP[3]
        ) return true
        // FLV.
        if (bytes.size >= 3 &&
            bytes[0] == FLV_MAGIC[0] && bytes[1] == FLV_MAGIC[1] && bytes[2] == FLV_MAGIC[2]
        ) return true
        // MPEG-TS: a real transport stream is a sequence of 188-byte packets each
        // starting with the 0x47 sync byte. A short body (e.g. a single 0x47 byte
        // from a truncated/error response) must NOT be accepted as TS — require at
        // least 188 bytes and confirm the sync byte at both offset 0 and offset 188
        // (the start of the second packet) so a lone sync byte or non-TS body is
        // rejected.
        if (bytes.size > 188 &&
            bytes[0] == TS_SYNC && bytes[188] == TS_SYNC
        ) return true
        return false
    }

    /**
     * Detects HTML/XML/JSON error-page prefixes. Skips leading whitespace/BOM and inspects the
     * first ~64 printable bytes. Returns false for anything that does not clearly look like a
     * markup or JSON error document.
     */
    internal fun looksLikeHtmlOrXmlError(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 256)
        var start = 0
        while (start < limit && (bytes[start].toInt() and 0xFF) <= 0x20) start++
        if (start >= limit) return false
        val windowLen = minOf(limit - start, 64)
        val prefix = String(bytes, start, windowLen, Charsets.US_ASCII)
        val lower = prefix.lowercase()
        return lower.startsWith("<!doctype html") || lower.startsWith("<html") ||
            lower.startsWith("<?xml") || lower.startsWith("<error") ||
            lower.startsWith("{") || lower.startsWith("[")
    }

    private fun startsWithMagic(bytes: ByteArray, magic: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        for (i in magic.indices) if (bytes[i] != magic[i]) return false
        return true
    }

    /**
     * Performs a bounded Range GET against [url] and returns a verdict. Only [PayloadVerdict.Invalid]
     * is actionable; [PayloadVerdict.Indeterminate] is returned for any probe failure (timeout,
     * network error, non-http scheme) so the caller never blocks playback on a probe hiccup.
     *
     * This is a standalone request separate from ExoPlayer's data source — it does not consume
     * or replay ExoPlayer's stream, so it is peek-safe by construction.
     */
    suspend fun validateProgressiveHttpPayload(
        url: String,
        headers: Map<String, String>,
        expectedMimeType: String?
    ): PayloadVerdict = validateProgressiveHttpPayloadWithDetails(
        url = url,
        headers = headers,
        expectedMimeType = expectedMimeType
    ).verdict

    suspend fun validateProgressiveHttpPayloadWithDetails(
        url: String,
        headers: Map<String, String>,
        expectedMimeType: String?
    ): ProgressivePayloadValidation = withContext(Dispatchers.IO) {
        if (!isHttpScheme(url)) {
            return@withContext ProgressivePayloadValidation(
                verdict = PayloadVerdict.Indeterminate("non-http-scheme"),
                fullFileRange = null,
                resolvedUrl = null
            )
        }
        val sanitized = PlayerMediaSourceFactory.sanitizeHeaders(headers)
        val probe = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { performProbe(url, sanitized) }.getOrNull()
        }
        if (probe == null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Probe indeterminate (timeout/error) host=${runCatching { Uri.parse(url).host }.getOrDefault("unknown")}")
            }
            return@withContext ProgressivePayloadValidation(
                verdict = PayloadVerdict.Indeterminate("probe-timeout-or-error"),
                fullFileRange = null,
                resolvedUrl = null
            )
        }
        val verdict = classifyPayload(probe, expectedMimeType)
        val fullFileRange = probe.fullFileRange().takeIf { verdict == PayloadVerdict.Valid }
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "Probe verdict=$verdict host=${runCatching { Uri.parse(url).host }.getOrDefault("unknown")} " +
                    "status=${probe.httpStatus} contentType=${probe.contentType} bytes=${probe.initialBytes.size} " +
                    "headers=${sanitized.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(",").ifBlank { "none" }} " +
                    "finalHost=${runCatching { Uri.parse(probe.finalUrl).host }.getOrDefault("unknown")} " +
                    "contentLength=${probe.contentLength} contentRange=${probe.contentRange ?: "none"} " +
                    "acceptRanges=${probe.acceptRanges ?: "none"} prefix=${hexPrefix(probe.initialBytes)} " +
                    "fullFileRange=${fullFileRange != null}"
            )
        }
        ProgressivePayloadValidation(
            verdict = verdict,
            fullFileRange = fullFileRange,
            resolvedUrl = probe.finalUrl
        )
    }

    private fun PayloadProbe.fullFileRange(): String? {
        val match = CONTENT_RANGE_PATTERN.matchEntire(contentRange?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start != 0L || total <= 0L) return null
        return "bytes=0-${total - 1L}"
    }

    private fun performProbe(url: String, headers: Map<String, String>): PayloadProbe {
        val connection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = headers,
            method = "GET",
            connectTimeoutMs = 4000,
            readTimeoutMs = 4000,
            range = "bytes=0-${PROBE_BYTE_LIMIT - 1}"
        )
        try {
            val status = connection.responseCode
            val contentType = connection.contentType
            val finalUrl = connection.url.toString()
            val contentLength = connection.contentLength.let { if (it < 0) -1L else it.toLong() }
            val contentRange = connection.getHeaderField("Content-Range")
            val acceptRanges = connection.getHeaderField("Accept-Ranges")
            val bytes = readBounded(connection, PROBE_BYTE_LIMIT)
            return PayloadProbe(
                httpStatus = status,
                contentType = contentType,
                finalUrl = finalUrl,
                contentLength = contentLength,
                initialBytes = bytes,
                contentRange = contentRange,
                acceptRanges = acceptRanges
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun readBounded(connection: HttpURLConnection, limit: Int): ByteArray {
        val stream: InputStream? = try {
            if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        } catch (_: Exception) {
            null
        }
        if (stream == null) return ByteArray(0)
        return stream.use {
            val buf = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val n = it.read(buf, read, limit - read)
                if (n < 0) break
                read += n
            }
            if (read == 0) ByteArray(0) else buf.copyOf(read)
        }
    }

    private fun isHttpScheme(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    private fun hexPrefix(bytes: ByteArray): String = buildString(minOf(bytes.size, 8) * 2) {
        for (index in 0 until minOf(bytes.size, 8)) {
            append("%02x".format(bytes[index].toInt() and 0xff))
        }
    }

    private val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
}

internal data class ProgressivePayloadValidation(
    val verdict: PayloadVerdict,
    val fullFileRange: String?,
    val resolvedUrl: String? = null
)

/** Snapshot of the probed response used by [StreamPayloadValidator.classifyPayload]. */
internal data class PayloadProbe(
    val httpStatus: Int,
    val contentType: String?,
    val finalUrl: String,
    val contentLength: Long,
    val initialBytes: ByteArray,
    val contentRange: String? = null,
    val acceptRanges: String? = null
) {
    // Generated equals/hashCode would compare arrays by identity; this data class is only used
    // as a local transport into classifyPayload, so identity-based copy semantics are fine.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Outcome of a payload probe. */
internal sealed class PayloadVerdict {
    /** Bytes/status/content-type indicate a real media payload. */
    object Valid : PayloadVerdict()

    /** Strong evidence the response is not a playable media payload (e.g. Comet error body). */
    data class Invalid(val reason: String) : PayloadVerdict()

    /** Could not prove either way; the caller must not block playback. */
    data class Indeterminate(val reason: String) : PayloadVerdict()
}

/**
 * Thrown when the pre-ExoPlayer payload probe rejects a stream. Routed through the existing
 * [PlayerRuntimeController.handleInitializePlayerException] path so the dual-player failover
 * switches to MPV exactly as it does for an ExoPlayer startup error.
 */
internal class InvalidStreamPayloadException(val reason: String) :
    RuntimeException("Invalid stream payload: $reason")
