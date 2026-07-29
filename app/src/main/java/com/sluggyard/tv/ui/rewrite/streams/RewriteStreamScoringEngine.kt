package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.debrid.StreamTextSizeParser
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
        // Never auto-start an uncached torrent (that triggers "downloading to debrid").
        // Prefer instant/direct or confirmed-cached only.
        val playable = candidates.filter { it.isAutoPlayEligible() }
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
        val episodeLike = isEpisodeLike(context)
        val weights = when (classify(context)) {
            // Episodes get a larger size weight so absurd remux/packs lose to sane encodes.
            ContentKind.ANIME -> if (episodeLike) Weights(15, 5, 30, 30, 20) else Weights(15, 5, 35, 35, 10)
            ContentKind.K_DRAMA, ContentKind.J_DRAMA ->
                if (episodeLike) Weights(15, 10, 30, 25, 20) else Weights(15, 10, 35, 30, 10)
            ContentKind.LIVE -> Weights(20, 10, 5, 55, 10)
            ContentKind.MAINSTREAM ->
                if (episodeLike) Weights(25, 15, 10, 30, 20) else Weights(30, 15, 10, 35, 10)
        }
        val quality = qualityScore(candidate)
        val audio = audioScore(candidate)
        val subtitles = subtitleScore(candidate, context)
        val reliability = reliabilityScore(candidate)
        val size = sizeScore(candidate, context, episodeLike)
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
        val aiUpscalePenalty = if (
            Regex("\\b(ai.?upscale|upscaled|ai.?enhance|reupscale|topaz|esrgan)\\b").containsMatchIn(text)
        ) 40 else 0
        return (base + codecBonus + hdrBonus - bad - aiUpscalePenalty).coerceIn(0, 100)
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

    private fun sizeScore(
        candidate: RewriteStreamCandidate,
        context: Context,
        episodeLike: Boolean = isEpisodeLike(context),
    ): Int {
        val size = resolveSizeBytes(candidate) ?: return 60
        if (size <= 0) return 60
        val sizeGb = size / 1_073_741_824.0
        val text = candidate.searchText().lowercase()
        val expected = expectedSizeGb(text, episodeLike)
        return if (episodeLike) {
            episodeSizeScore(sizeGb, text, expected)
        } else {
            movieSizeScore(sizeGb, expected)
        }
    }

    /**
     * Episodes still lose points for being oversized: a 20GB single episode is a mislabeled
     * season pack or a remux nobody asked for, and packs break episode-scoped playback.
     */
    private fun episodeSizeScore(sizeGb: Double, text: String, expectedGb: Double): Int {
        when {
            sizeGb >= 40.0 -> return 0
            sizeGb >= 25.0 -> return 3
            sizeGb >= 15.0 -> return 10
            sizeGb >= 10.0 -> return 20
            sizeGb >= 6.0 && !isUhd(text) -> return 30
        }
        val ratio = sizeGb / expectedGb
        return when {
            ratio < 0.3 -> 20
            ratio < 0.5 -> 40
            ratio < 0.7 -> 60
            ratio < 0.9 -> 80
            ratio <= 1.3 -> 100
            ratio <= 1.8 -> 85
            ratio <= 2.5 -> 70
            ratio <= 3.5 -> 35
            ratio <= 5.0 -> 15
            else -> 5
        }
    }

    /**
     * Movies are never size-penalized for being large — bigger is usually a better encode.
     * Only undersized rips lose points; everything at/above a sane encode size scores full.
     */
    private fun movieSizeScore(sizeGb: Double, expectedGb: Double): Int {
        val ratio = sizeGb / expectedGb
        return when {
            ratio < 0.3 -> 25
            ratio < 0.5 -> 50
            ratio < 0.7 -> 75
            else -> 100
        }
    }

    private fun resolveSizeBytes(candidate: RewriteStreamCandidate): Long? =
        candidate.videoSizeBytes?.takeIf { it > 0L }
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.streamDescription)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.title)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.detailLabel)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.filename)
            ?: StreamTextSizeParser.sizeBytesFromText(candidate.metadataText)

    private fun isEpisodeLike(context: Context): Boolean {
        val type = context.contentType.trim().lowercase()
        return type in setOf(
            "series", "show", "shows", "tv", "tvshow", "tvshows", "anime", "episode", "episodes",
        )
    }

    private fun isUhd(text: String): Boolean =
        Regex("\\b(2160p|4k|uhd)\\b").containsMatchIn(text)

    private fun expectedSizeGb(text: String, episodeLike: Boolean): Double {
        val uhd = isUhd(text)
        val fhd = Regex("\\b1080p\\b").containsMatchIn(text)
        val hd = Regex("\\b720p\\b").containsMatchIn(text)
        val sd = Regex("\\b(480p|sd)\\b").containsMatchIn(text)
        return if (episodeLike) {
            when {
                uhd -> 4.0
                fhd -> 1.5
                hd -> 0.7
                sd -> 0.35
                else -> 1.2
            }
        } else {
            // Movie targets leave headroom for legitimate 4K remux (~20–50GB).
            when {
                uhd -> 25.0
                fhd -> 10.0
                hd -> 4.0
                sd -> 2.0
                else -> 8.0
            }
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

    private fun RewriteStreamCandidate.isAutoPlayEligible(): Boolean {
        if (!isWellFormed()) return false
        val hash = infoHash?.trim().orEmpty()
        if (hash.isNotBlank()) {
            // Only auto-start confirmed cached torrents. UNKNOWN may still be offered after the
            // cache-check timeout (see AppShell), but NOT_CACHED / still-CHECKING never auto-play.
            return when (cacheState) {
                StreamCacheState.CACHED -> true
                StreamCacheState.UNKNOWN -> true // probe failed; try resolve once, reject if uncached
                StreamCacheState.NOT_APPLICABLE -> true // no debrid probes (e.g. Real-Debrid)
                StreamCacheState.CHECKING, StreamCacheState.NOT_CACHED -> false
            }
        }
        val url = directUrl?.trim().orEmpty()
        if (url.isBlank()) return false
        return !isNonInstantStreamUrl(url)
    }

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
