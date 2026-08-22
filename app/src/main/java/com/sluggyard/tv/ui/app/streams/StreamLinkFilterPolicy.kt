package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.debrid.StreamTextSizeParser

/**
 * Shared hard admission policy for Sources + [selectAutoPlayCandidate].
 * Always-junk (CAM/3D/VR) cannot be toggled off.
 */
object StreamLinkFilterPolicy {
    private const val BYTES_PER_GB = 1_073_741_824.0

    private val CAM_JUNK = Regex(
        """\b(cam(?:rip)?|hdcam|telesync|telecine|tscam|screener|hdts|hd-?tc|r5)\b|\[\s*cam(?:rip)?\s*]""",
        RegexOption.IGNORE_CASE,
    )
    // Avoid bare \bou\b / \bsbs\b alone — too many false positives. Prefer explicit 3D / Half forms.
    private val THREE_D = Regex(
        """\b(?:3[dD]|half[ ._-]?sbs|half[ ._-]?ou|hsbs|hou)\b|\[\s*3[dD]\s*]""",
        RegexOption.IGNORE_CASE,
    )
    private val VR = Regex(
        """\b(?:vr180|vr360|180vr|360vr|fisheye|sbs[ ._-]?vr)\b|\bvr\b""",
        RegexOption.IGNORE_CASE,
    )
    private val REMUX = Regex("""\b(?:remux|bdremux|bluray[ ._-]?remux)\b""", RegexOption.IGNORE_CASE)
    private val DOLBY_VISION = Regex(
        """\b(?:dolby[ ._-]*vision|dovi)\b|\bdv\b""",
        RegexOption.IGNORE_CASE,
    )
    private val UHD = Regex("""\b(?:2160p|4k|uhd)\b""", RegexOption.IGNORE_CASE)
    private val AV1 = Regex("""\bav1\b""", RegexOption.IGNORE_CASE)
    private val HEVC = Regex("""\b(?:hevc|h\.?265|x265)\b""", RegexOption.IGNORE_CASE)
    private val H264 = Regex("""\b(?:avc|h\.?264|x264)\b""", RegexOption.IGNORE_CASE)

    fun allows(candidate: StreamCandidate, settings: StreamLinkFilterSettings): Boolean {
        val text = releaseBlob(candidate)
        if (CAM_JUNK.containsMatchIn(text)) return false
        if (THREE_D.containsMatchIn(text)) return false
        if (VR.containsMatchIn(text)) return false
        if (!settings.allowRemux && REMUX.containsMatchIn(text)) return false
        if (!settings.allowDolbyVision && DOLBY_VISION.containsMatchIn(text)) return false
        if (!allowsResolution(text, settings.maxResolution)) return false
        if (!allowsSize(candidate, settings.maxSizeGb)) return false
        if (!allowsCodec(text, settings.codec)) return false
        return true
    }

    fun filter(candidates: List<StreamCandidate>, settings: StreamLinkFilterSettings): List<StreamCandidate> =
        candidates.filter { allows(it, settings) }

    fun filterGroups(groups: List<StreamGroup>, settings: StreamLinkFilterSettings): List<StreamGroup> =
        groups.map { group ->
            when (val state = group.state) {
                is StreamGroupState.Content -> {
                    val kept = filter(state.streams, settings)
                    group.copy(
                        state = when {
                            kept.isEmpty() && state.streams.isNotEmpty() -> StreamGroupState.Empty
                            else -> StreamGroupState.Content(kept)
                        },
                    )
                }
                else -> group
            }
        }

    /** True when raw groups had streams but filters wiped every Content row (and nothing still Loading). */
    fun emptiedByFilters(
        before: List<StreamGroup>,
        after: List<StreamGroup>,
    ): Boolean {
        if (before.any { it.state is StreamGroupState.Loading }) return false
        if (after.any { it.state is StreamGroupState.Loading }) return false
        val beforeCount = before.sumOf { (it.state as? StreamGroupState.Content)?.streams?.size ?: 0 }
        val afterCount = after.sumOf { (it.state as? StreamGroupState.Content)?.streams?.size ?: 0 }
        return beforeCount > 0 && afterCount == 0
    }

    fun releaseBlob(candidate: StreamCandidate): String =
        listOf(
            candidate.title,
            candidate.detailLabel.orEmpty(),
            candidate.filename.orEmpty(),
            candidate.streamDescription.orEmpty(),
            candidate.metadataText.orEmpty(),
            candidate.sourceLabel,
        ).joinToString(" ")

    private fun allowsResolution(text: String, max: StreamLinkMaxResolution): Boolean =
        when (max) {
            StreamLinkMaxResolution.UNLIMITED -> true
            StreamLinkMaxResolution.UHD_4K -> true
            StreamLinkMaxResolution.FHD_1080 -> !UHD.containsMatchIn(text)
        }

    private fun allowsSize(candidate: StreamCandidate, maxSizeGb: Int?): Boolean {
        if (maxSizeGb == null) return true
        val bytes = resolveSizeBytes(candidate) ?: return true // fail open
        if (bytes <= 0L) return true
        val maxBytes = maxSizeGb * BYTES_PER_GB
        return bytes <= maxBytes
    }

    private fun resolveSizeBytes(candidate: StreamCandidate): Long? =
        candidate.videoSizeBytes?.takeIf { it > 0L }
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.streamDescription)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.title)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.detailLabel)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.filename)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.metadataText)

    private fun allowsCodec(text: String, codec: StreamLinkCodecFilter): Boolean {
        if (codec == StreamLinkCodecFilter.ANY) return true
        val detected = detectCodec(text) ?: return true // fail open when untagged
        return detected == codec
    }

    private fun detectCodec(text: String): StreamLinkCodecFilter? = when {
        AV1.containsMatchIn(text) -> StreamLinkCodecFilter.AV1
        HEVC.containsMatchIn(text) -> StreamLinkCodecFilter.HEVC
        H264.containsMatchIn(text) -> StreamLinkCodecFilter.H264
        else -> null
    }
}
