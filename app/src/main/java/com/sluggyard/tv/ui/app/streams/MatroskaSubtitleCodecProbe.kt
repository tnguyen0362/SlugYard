package com.sluggyard.tv.ui.app.streams

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * File-truth subtitle inventory from a Matroska (EBML) header prefix — not release titles.
 *
 * Track CodecIDs are stored as literal UTF-8 strings in the Tracks element near the start of
 * most WEB-DL / fansub MKVs. A bounded Range GET + string scan is enough to distinguish
 * S_TEXT/ASS vs S_TEXT/UTF8 (SRT) before the player commits — the gap titles can never close
 * (e.g. ToonsHub "Multi-Subs" Instant that is SRT-only).
 *
 * Fail-open: empty / non-EBML / timed-out probes never reject auto-pick.
 */
object MatroskaSubtitleCodecProbe {
    private const val TAG = "SlugYardMkvProbe"
    /** Enough for Tracks on typical embeds; larger Speakers/Attachments may sit later — fail-open. */
    const val DEFAULT_BYTE_LIMIT = 1_048_576
    private const val PROBE_TIMEOUT_MS = 12_000L

    /** Known Matroska CodecID payloads (substring match on header bytes). */
    private val ASS_CODECS = listOf("S_TEXT/ASS", "S_TEXT/SSA")
    private val SRT_CODECS = listOf("S_TEXT/UTF8", "S_TEXT/ASCII", "S_TEXT/WEBVTT", "S_TEXT/S_TEXT")
    private val PGS_CODECS = listOf("S_HDMV/PGS", "S_HDMV/TEXTST")
    private val OTHER_SOFT = listOf("S_VOBSUB", "S_TEXT/USF", "S_KATE")

    data class Inventory(
        val isEbml: Boolean = false,
        val hasAss: Boolean = false,
        val hasSrt: Boolean = false,
        val hasPgs: Boolean = false,
        val hasOtherSoft: Boolean = false,
        val codecHits: List<String> = emptyList(),
        val bytesScanned: Int = 0,
        val error: String? = null,
    ) {
        val hasAnySoft: Boolean
            get() = hasAss || hasSrt || hasPgs || hasOtherSoft

        /**
         * True when we saw real soft CodecIDs — safe to demote this hash in auto-pick memory.
         * Non-EBML / empty headers are not confident (fail-open).
         */
        val confidentSoftInventory: Boolean
            get() = isEbml && hasAnySoft
    }

    fun scanPrefix(bytes: ByteArray): Inventory {
        if (bytes.isEmpty()) return Inventory(error = "empty")
        val isEbml = startsWithEbml(bytes)
        if (!isEbml) {
            // Still scan: some remuxes ship odd prefixes after CDN wrappers.
        }
        val hits = LinkedHashSet<String>()
        var hasAss = false
        var hasSrt = false
        var hasPgs = false
        var hasOther = false
        for (id in ASS_CODECS) {
            if (containsAscii(bytes, id)) {
                hasAss = true
                hits += id
            }
        }
        for (id in SRT_CODECS) {
            if (containsAscii(bytes, id)) {
                hasSrt = true
                hits += id
            }
        }
        for (id in PGS_CODECS) {
            if (containsAscii(bytes, id)) {
                hasPgs = true
                hits += id
            }
        }
        for (id in OTHER_SOFT) {
            if (containsAscii(bytes, id)) {
                hasOther = true
                hits += id
            }
        }
        return Inventory(
            isEbml = isEbml,
            hasAss = hasAss,
            hasSrt = hasSrt,
            hasPgs = hasPgs,
            hasOtherSoft = hasOther,
            codecHits = hits.toList(),
            bytesScanned = bytes.size,
        )
    }

    /**
     * Auto-pick should skip pure text-soft (SRT/VTT) Instant winners for anime when ASS/PGS
     * inventory is absent. Never rejects non-confident scans or when no more resolve attempts
     * remain (last chance still plays).
     */
    fun shouldRejectAnimeAutoPick(
        inventory: Inventory,
        remainingResolveAttemptsAfterThis: Int,
    ): Boolean {
        if (remainingResolveAttemptsAfterThis <= 0) return false
        if (!inventory.confidentSoftInventory) return false
        // ASS or PGS: keep — preferred dialogue formats.
        if (inventory.hasAss || inventory.hasPgs) return false
        // Pure SRT / UTF8 soft: reject and try next Instant.
        return inventory.hasSrt
    }

    fun toObservedTracks(
        inventory: Inventory,
        observedAtMs: Long = System.currentTimeMillis(),
    ): ObservedReleaseTracks {
        // Lang bases stay empty — CodecID parse does not yield Language tags here.
        // Soft/ASS/PGS/SRT flags alone lift/lower softsubFit vs title Multi-Subs lies.
        return ObservedReleaseTracks(
            audioLangBases = emptySet(),
            subtitleLangBases = emptySet(),
            hasAss = inventory.hasAss,
            hasPgs = inventory.hasPgs,
            hasSrt = inventory.hasSrt || inventory.hasOtherSoft,
            hasSoftsubTrack = inventory.hasAnySoft,
            observedAtMs = observedAtMs,
        )
    }

    /**
     * Range-GET the MKV prefix after debrid resolve. Returns empty-error inventory on failure.
     */
    suspend fun probeHttpUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        byteLimit: Int = DEFAULT_BYTE_LIMIT,
    ): Inventory = withContext(Dispatchers.IO) {
        if (!isHttpScheme(url)) {
            return@withContext Inventory(error = "non-http")
        }
        val timed = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { performRangeGet(url, headers, byteLimit) }.getOrElse {
                Inventory(error = it.message ?: "io-error")
            }
        }
        timed ?: Inventory(error = "timeout")
    }

    private fun performRangeGet(
        url: String,
        headers: Map<String, String>,
        byteLimit: Int,
    ): Inventory {
        val limit = byteLimit.coerceIn(64 * 1024, 4 * 1024 * 1024)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-${limit - 1}")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "SlugYard/1.0 (Android TV; MKV track probe)")
            headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) setRequestProperty(k, v)
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                return Inventory(error = "http-$code")
            }
            val stream = connection.inputStream ?: return Inventory(error = "no-body")
            val bytes = stream.use { input ->
                val buf = ByteArray(limit)
                var read = 0
                while (read < limit) {
                    val n = input.read(buf, read, limit - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
            val inv = scanPrefix(bytes)
            Log.i(
                TAG,
                "scan bytes=${inv.bytesScanned} ebml=${inv.isEbml} ass=${inv.hasAss} " +
                    "srt=${inv.hasSrt} pgs=${inv.hasPgs} hits=${inv.codecHits}",
            )
            return inv
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun isHttpScheme(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    private fun startsWithEbml(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
    }

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(StandardCharsets.US_ASCII)
        if (n.isEmpty() || haystack.size < n.size) return false
        outer@ for (i in 0..(haystack.size - n.size)) {
            for (j in n.indices) {
                if (haystack[i + j] != n[j]) continue@outer
            }
            return true
        }
        return false
    }
}
