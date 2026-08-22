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
    /** Below this, missing CodecIDs may just mean Tracks sits later — fail-open. */
    const val MIN_CONFIDENT_SCAN_BYTES = 64 * 1024
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
        /** Audio codec IDs found by the EBML walk (A_AAC, A_AC3, A_EAC3, A_TRUEHD, A_DTS…). */
        val audioCodecs: Set<String> = emptySet(),
        /** Audio channel counts found by the EBML walk (2, 6, 8, …). */
        val audioChannels: Set<Int> = emptySet(),
        val bytesScanned: Int = 0,
        val error: String? = null,
    ) {
        val hasAnySoft: Boolean
            get() = hasAss || hasSrt || hasPgs || hasOtherSoft

        /**
         * Highest audio channel count from container truth (2=stereo, 6=5.1, 8+=7.1-class).
         * Null when unknown / non-EBML / fail-open.
         */
        val maxAudioChannels: Int?
            get() = audioChannels.maxOrNull()


        /**
         * True when we saw real soft CodecIDs — safe to demote this hash in auto-pick memory.
         * Non-EBML / empty headers are not confident (fail-open).
         */
        val confidentSoftInventory: Boolean
            get() = isEbml && hasAnySoft

        /**
         * EBML prefix with no subtitle CodecIDs after a real scan — the file has no
         * softsubs. Empty / timed-out / tiny prefixes stay fail-open.
         */
        val confidentNoSoftsubs: Boolean
            get() = isEbml &&
                error == null &&
                !hasAnySoft &&
                bytesScanned >= MIN_CONFIDENT_SCAN_BYTES
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
        // Container-truth audio layout (codec + channel counts) — the EBML walk reads
        // TrackEntry(TrackType audio) → CodecID + Audio/Channels. Fail-open on malformed
        // structures: ranking just falls back to title heuristics.
        val (audioCodecs, audioChannels) = if (isEbml) {
            parseTrackAudio(bytes)
        } else {
            emptySet<String>() to emptySet<Int>()
        }
        return Inventory(
            isEbml = isEbml,
            hasAss = hasAss,
            hasSrt = hasSrt,
            hasPgs = hasPgs,
            hasOtherSoft = hasOther,
            codecHits = hits.toList(),
            audioCodecs = audioCodecs,
            audioChannels = audioChannels,
            bytesScanned = bytes.size,
        )
    }

    /**
     * Auto-pick should skip a resolved file when header truth says it cannot provide
     * dialogue subs the user asked for.
     *
     * - Preferred language set + EBML with **zero** subtitle CodecIDs: always reject.
     *   Title heuristics cannot see unlabeled audio / empty subtitle tracks — this can.
     * - Anime + pure SRT: keep waiting for ASS/PGS while retries remain.
     * - Non-EBML / timeout / tiny prefix: fail-open.
     */
    fun shouldRejectAutoPick(
        inventory: Inventory,
        remainingResolveAttemptsAfterThis: Int,
        requireDialogueSubs: Boolean,
        rejectAnimePureSrt: Boolean,
    ): Boolean {
        if (requireDialogueSubs && inventory.confidentNoSoftsubs) return true
        if (!rejectAnimePureSrt) return false
        return shouldRejectAnimeAutoPick(inventory, remainingResolveAttemptsAfterThis)
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
            audioCodecs = inventory.audioCodecs,
            audioChannels = inventory.audioChannels,
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
                    "srt=${inv.hasSrt} pgs=${inv.hasPgs} hits=${inv.codecHits} " +
                    "audio=${inv.audioCodecs.joinToString(",")} ch=${inv.audioChannels.joinToString(",")}",
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

    // ---- EBML audio walk ------------------------------------------------------
    // Element IDs (Matroska spec): Segment 0x18538067, Tracks 0x1654AE6B,
    // TrackEntry 0xAE, TrackType 0x83 (2=audio), CodecID 0x86, Audio 0xE1, Channels 0x9F.
    private const val SEGMENT_ID = 0x18538067L
    private const val TRACKS_ID = 0x1654AE6BL
    private const val TRACK_ENTRY_ID = 0xAEL
    private const val TRACK_TYPE_ID = 0x83L
    private const val CODEC_ID_ID = 0x86L
    private const val AUDIO_ID = 0xE1L
    private const val CHANNELS_ID = 0x9FL

    /**
     * Bounded EBML walk over the fetched prefix: Segment → Tracks → TrackEntry
     * (TrackType=audio) → CodecID string + Audio/Channels varint. Container-truth
     * audio layout titles rarely state. Any malformed structure fails open to empty.
     * Returns (audioCodecs, audioChannels) from TrackType 0x02 entries only.
     */
    internal fun parseTrackAudio(bytes: ByteArray): Pair<Set<String>, Set<Int>> {
        val codecs = LinkedHashSet<String>()
        val channels = LinkedHashSet<Int>()
        if (bytes.size < 4) return codecs to channels
        // Skip the EBML header element (ID 1A45DFA3 is fixed 4 bytes) to reach Segment.
        // pos already points past that ID, so only the size vint + header payload remain.
        var pos = 4
        val headerSize = readVintSize(bytes, pos)
        if (headerSize == null || headerSize.first < 0) return codecs to channels
        pos += headerSize.second + headerSize.first
        val (segIdLen, segId) = readElementId(bytes, pos) ?: return codecs to channels
        if (segId != SEGMENT_ID) return codecs to channels
        val segSize = readVintSize(bytes, pos + segIdLen)
        val segPayloadStart = pos + segIdLen + (segSize?.second ?: 0)
        // mkvmerge frequently leaves Segment size unknown — walk to the prefix end then.
        val segEnd = if (segSize == null || segSize.first < 0) {
            bytes.size
        } else {
            (segPayloadStart + segSize.first).coerceAtMost(bytes.size)
        }
        pos = segPayloadStart
        while (pos + 2 <= segEnd) {
            val (idLen, id) = readElementId(bytes, pos) ?: return codecs to channels
            val size = readVintSize(bytes, pos + idLen) ?: return codecs to channels
            val payloadStart = pos + idLen + size.second
            val payloadEnd = payloadStart + size.first
            if (size.first < 0 || payloadEnd > segEnd) return codecs to channels
            if (id == TRACKS_ID) {
                parseTracks(bytes, payloadStart, payloadEnd, codecs, channels)
                return codecs to channels
            }
            pos = payloadEnd
        }
        return codecs to channels
    }

    private fun parseTracks(
        bytes: ByteArray,
        start: Int,
        end: Int,
        codecs: MutableSet<String>,
        channels: MutableSet<Int>,
    ) {
        var pos = start
        while (pos + 2 <= end) {
            val (idLen, id) = readElementId(bytes, pos) ?: return
            val size = readVintSize(bytes, pos + idLen) ?: return
            val payloadStart = pos + idLen + size.second
            val payloadEnd = payloadStart + size.first
            if (size.first < 0 || payloadEnd > end) return
            if (id == TRACK_ENTRY_ID) {
                parseTrackEntry(bytes, payloadStart, payloadEnd, codecs, channels)
            }
            pos = payloadEnd
        }
    }

    private fun parseTrackEntry(
        bytes: ByteArray,
        start: Int,
        end: Int,
        codecs: MutableSet<String>,
        channels: MutableSet<Int>,
    ) {
        var pos = start
        var trackType = -1
        var codecId: String? = null
        var audioStart = -1
        var audioEnd = -1
        while (pos + 2 <= end) {
            val (idLen, id) = readElementId(bytes, pos) ?: return
            val size = readVintSize(bytes, pos + idLen) ?: return
            val payloadStart = pos + idLen + size.second
            val payloadEnd = payloadStart + size.first
            if (size.first < 0 || payloadEnd > end) return
            when (id) {
                TRACK_TYPE_ID -> if (size.first == 1) trackType = bytes[payloadStart].toInt() and 0xFF
                CODEC_ID_ID -> if (size.first in 1..128) {
                    codecId = String(bytes, payloadStart, size.first, StandardCharsets.UTF_8)
                }
                AUDIO_ID -> {
                    audioStart = payloadStart
                    audioEnd = payloadEnd
                }
            }
            pos = payloadEnd
        }
        if (trackType != 2) return
        codecId?.takeIf { it.isNotBlank() }?.let(codecs::add)
        if (audioStart >= 0) {
            parseAudioChannels(bytes, audioStart, audioEnd)
                ?.takeIf { it > 0 }
                ?.let(channels::add)
        }
    }

    private fun parseAudioChannels(bytes: ByteArray, start: Int, end: Int): Int? {
        var pos = start
        while (pos + 2 <= end) {
            val (idLen, id) = readElementId(bytes, pos) ?: return null
            val size = readVintSize(bytes, pos + idLen) ?: return null
            val payloadStart = pos + idLen + size.second
            val payloadEnd = payloadStart + size.first
            if (size.first < 0 || payloadEnd > end) return null
            if (id == CHANNELS_ID && size.first in 1..4) {
                var value = 0
                for (i in 0 until size.first) {
                    value = (value shl 8) or (bytes[payloadStart + i].toInt() and 0xFF)
                }
                return value
            }
            pos = payloadEnd
        }
        return null
    }

    /**
     * EBML element ID: 1–4 bytes, length from the first byte's leading zero bits
     * (0x1A45DFA3 → 4 bytes, 0xAE → 1 byte). Returns (idLength, id).
     */
    private fun readElementId(bytes: ByteArray, pos: Int): Pair<Int, Long>? {
        if (pos >= bytes.size) return null
        val first = bytes[pos].toInt() and 0xFF
        if (first == 0) return null
        var len = 1
        var mask = 0x80
        while (len < 4 && (first and mask) == 0) {
            len++
            mask = mask shr 1
        }
        if (pos + len > bytes.size) return null
        var id = 0L
        for (i in 0 until len) {
            id = (id shl 8) or (bytes[pos + i].toInt().toLong() and 0xFFL)
        }
        return len to id
    }

    /**
     * EBML size varint: length = 1 + leading zero bits of the first byte (up to 8 bytes),
     * marker bit dropped. Returns (value, vintLength). Null when malformed or the value
     * exceeds the bounded prefix; the all-ones "unknown size" marker returns -1 so callers
     * can still skip the vint and decide legality (Segment unknown → walk to prefix end).
     */
    private fun readVintSize(bytes: ByteArray, pos: Int): Pair<Int, Int>? {
        if (pos >= bytes.size) return null
        val first = bytes[pos].toInt() and 0xFF
        if (first == 0) return null
        var len = 1
        var probe = 0x80
        while (len < 8 && (first and probe) == 0) {
            len++
            probe = probe shr 1
        }
        if (pos + len > bytes.size) return null
        var raw = 0L
        for (i in 0 until len) {
            raw = (raw shl 8) or (bytes[pos + i].toInt().toLong() and 0xFFL)
        }
        // The marker bit sits at bit (7 * len) for a len-byte vint (e.g. len 8 → 0x01).
        val markerBit = 1L shl (7 * len)
        val value = raw and (markerBit - 1)
        // All value bits set = "unknown size" (common for Segment/Clusters).
        if (value == markerBit - 1) return -1 to len
        if (value > Int.MAX_VALUE.toLong()) return null
        return value.toInt() to len
    }
}
