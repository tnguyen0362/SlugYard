package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState

/**
 * Rewrite-owned stream scoring. This works only with Rewrite models so the new playback path does
 * not depend on the migrated legacy selector or its data model.
 */
internal object RewriteStreamScoringEngine {
    enum class ContentKind { MAINSTREAM, ANIME, K_DRAMA, J_DRAMA, LIVE }

    data class Context(
        val title: String,
        val contentType: String,
        val genres: List<String> = emptyList(),
        val language: String? = null,
    )

    fun choose(candidates: List<RewriteStreamCandidate>, context: Context): RewriteStreamCandidate? {
        val playable = candidates.filter {
            it.isWellFormed() &&
                it.cacheState != StreamCacheState.CHECKING &&
                it.cacheState != StreamCacheState.NOT_CACHED
        }
        if (playable.isEmpty()) return null

        // DV7 is supported by the player conversion path. Only reject a known Profile 8.6
        // candidate when a safer alternative exists; do not blanket-reject every DV stream.
        val safePool = playable.filterNot { it.isKnownUnsupportedDolbyVision() }.ifEmpty { playable }
        return safePool.maxWithOrNull(
            compareBy<RewriteStreamCandidate> { score(it, context) }
                .thenBy { it.id },
        )
    }

    fun rankedCandidates(
        candidates: List<RewriteStreamCandidate>,
        context: Context,
    ): List<RewriteStreamCandidate> {
        val wellFormed = candidates
            .filter { it.isWellFormed() }
            .distinctBy { it.dedupeKey() }
        val safePool = wellFormed.filterNot { it.isKnownUnsupportedDolbyVision() }.ifEmpty { wellFormed }
        return safePool.sortedWith(
            compareByDescending<RewriteStreamCandidate> { score(it, context) }
                .thenBy { it.id },
        )
    }

    fun score(candidate: RewriteStreamCandidate, context: Context): Int {
        val weights = when (classify(context)) {
            ContentKind.ANIME -> Weights(15, 5, 35, 35, 10)
            ContentKind.K_DRAMA, ContentKind.J_DRAMA -> Weights(15, 10, 35, 30, 10)
            ContentKind.LIVE -> Weights(20, 10, 5, 55, 10)
            ContentKind.MAINSTREAM -> Weights(30, 15, 10, 35, 10)
        }
        val quality = qualityScore(candidate)
        val audio = audioScore(candidate)
        val subtitles = subtitleScore(candidate, context)
        val reliability = reliabilityScore(candidate)
        val size = sizeScore(candidate)
        return (
            quality * weights.quality +
                audio * weights.audio +
                subtitles * weights.subtitles +
                reliability * weights.reliability +
                size * weights.size
            ) / 100
    }

    private data class Weights(
        val quality: Int,
        val audio: Int,
        val subtitles: Int,
        val reliability: Int,
        val size: Int,
    )

    private fun classify(context: Context): ContentKind {
        val text = "${context.title} ${context.contentType} ${context.genres.joinToString(" ")}".lowercase()
        val liveType = context.contentType.lowercase() in setOf("live", "event", "channel")
        if (liveType || ("live" in text && listOf("fight", "match", "event").any(text::contains))) {
            return ContentKind.LIVE
        }
        if ("k-drama" in text || "kdrama" in text || "korean drama" in text) return ContentKind.K_DRAMA
        if ("j-drama" in text || "jdrama" in text || "japanese drama" in text) return ContentKind.J_DRAMA
        val animation = "animation" in text || "anime" in text
        val asianLanguage = context.language?.lowercase() in setOf("ja", "jp", "ko", "kr", "zh", "cn")
        val animeTerm = listOf("otaku", "shonen", "shoujo", "seinen", "isekai", "mecha", "slice of life")
            .any(text::contains)
        return if (animation && (asianLanguage || animeTerm)) ContentKind.ANIME else ContentKind.MAINSTREAM
    }

    private fun qualityScore(candidate: RewriteStreamCandidate): Int {
        val text = candidate.searchText().lowercase()
        val base = when {
            Regex("\\b(2160p|4k|uhd)\\b").containsMatchIn(text) -> 100
            Regex("\\b1080p\\b").containsMatchIn(text) -> 85
            Regex("\\b720p\\b").containsMatchIn(text) -> 60
            Regex("\\b(480p|sd)\\b").containsMatchIn(text) -> 35
            else -> 40
        }
        val codecBonus = if (Regex("\\b(av1|hevc|h265|10[- ]?bit)\\b").containsMatchIn(text)) 8 else 0
        val hdrBonus = if (Regex("\\b(hdr10\\+?|dolby.?vision|dv)\\b").containsMatchIn(text)) 5 else 0
        val bad = if (Regex("\\b(cam|hdcam|telesync|tscam|telecine|screener)\\b").containsMatchIn(text)) 35 else 0
        return (base + codecBonus + hdrBonus - bad).coerceIn(0, 100)
    }

    private fun audioScore(candidate: RewriteStreamCandidate): Int {
        val text = candidate.searchText().lowercase()
        return when {
            "atmos" in text -> 100
            "truehd" in text || "dts-hd" in text || "dts:x" in text -> 95
            Regex("\\bdts\\b").containsMatchIn(text) -> 80
            "eac3" in text || "dd+" in text -> 75
            "ac3" in text || "dd5.1" in text -> 70
            "aac" in text || "flac" in text -> 65
            "mp3" in text || "opus" in text -> 40
            else -> 50
        }
    }

    private fun subtitleScore(candidate: RewriteStreamCandidate, context: Context): Int {
        val text = candidate.searchText().lowercase()
        var score = 45
        if (Regex("\\b(sub|subs|subtitle|subtitles|cc|english|eng)\\b").containsMatchIn(text)) score += 30
        if (context.language?.lowercase() in setOf("ja", "jp", "ko", "kr", "zh", "cn") &&
            Regex("\\b(eng|english)\\b").containsMatchIn(text)
        ) score += 15
        if ("multi" in text || "dual subs" in text) score += 10
        return score.coerceIn(0, 100)
    }

    private fun reliabilityScore(candidate: RewriteStreamCandidate): Int {
        val cache = when (candidate.cacheState) {
            StreamCacheState.CACHED -> 100
            StreamCacheState.NOT_APPLICABLE -> if (candidate.directUrl != null) 100 else 45
            StreamCacheState.UNKNOWN -> if (candidate.directUrl != null) 90 else 45
            StreamCacheState.CHECKING -> 40
            StreamCacheState.NOT_CACHED -> 15
        }
        val seeders = candidate.seeders ?: parseSeeders(candidate.searchText())
        val seederScore = when {
            seeders == null -> 45
            seeders >= 500 -> 100
            seeders >= 100 -> 90
            seeders >= 50 -> 80
            seeders >= 25 -> 65
            seeders >= 10 -> 50
            seeders >= 5 -> 35
            seeders >= 2 -> 25
            else -> 15
        }
        return ((cache * 2) + seederScore) / 3
    }

    private fun sizeScore(candidate: RewriteStreamCandidate): Int {
        val size = candidate.videoSizeBytes ?: return 60
        if (size <= 0) return 60
        val sizeGb = size / 1_073_741_824.0
        val text = candidate.searchText().lowercase()
        val expected = when {
            "2160p" in text || "4k" in text -> 15.0
            "1080p" in text -> 8.0
            "720p" in text -> 4.0
            "480p" in text -> 2.0
            else -> 4.0
        }
        val ratio = sizeGb / expected
        return when {
            ratio < 0.3 -> 20
            ratio < 0.5 -> 40
            ratio < 0.7 -> 60
            ratio < 0.9 -> 80
            ratio <= 1.3 -> 100
            ratio <= 1.8 -> 85
            ratio <= 2.5 -> 70
            else -> 50
        }
    }

    private fun parseSeeders(text: String): Int? = Regex(
        "(?:seeders?|seeds?|\\bse\\b|\\bs\\b)\\s*[:=]?\\s*(\\d{1,6})",
        RegexOption.IGNORE_CASE,
    ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun RewriteStreamCandidate.searchText(): String =
        listOf(title, detailLabel.orEmpty(), metadataText.orEmpty()).joinToString(" ")

    private fun RewriteStreamCandidate.isWellFormed(): Boolean =
        !directUrl.isNullOrBlank() || !infoHash.isNullOrBlank()

    private fun RewriteStreamCandidate.isKnownUnsupportedDolbyVision(): Boolean {
        val text = searchText().lowercase()
        return Regex("\\b(?:dolby[ ._-]*vision|dv)\\s*(?:profile\\s*|p\\s*)?8\\.6\\b").containsMatchIn(text)
    }

    private fun RewriteStreamCandidate.dedupeKey(): String = when {
        !directUrl.isNullOrBlank() -> "url:$directUrl"
        !infoHash.isNullOrBlank() -> "hash:${infoHash.lowercase()}:${fileIndex ?: -1}"
        else -> id
    }
}
