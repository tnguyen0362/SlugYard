package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

/**
 * Multi-dimensional stream scoring engine for intelligent autoplay selection.
 *
 * Balances quality, audio, subtitles, reliability, and file size with
 * content-type-aware weighting (anime/K-drama prioritize subs+seeders,
 * mainstream prioritizes quality+audio).
 */
object StreamScoringEngine {

    /**
     * Content classification for weight adjustment.
     */
    enum class ContentType {
        MAINSTREAM,
        ANIME,
        K_DRAMA,
        J_DRAMA,
        LIVE_EVENT
    }

    /**
     * Scoring weights per content type.
     * Each set sums to 100.
     */
    data class Weights(
        val quality: Float,
        val audio: Float,
        val subtitles: Float,
        val reliability: Float,
        val size: Float
    )

    private val WEIGHTS = mapOf(
        ContentType.MAINSTREAM to Weights(quality = 30f, audio = 15f, subtitles = 10f, reliability = 35f, size = 10f),
        ContentType.ANIME to Weights(quality = 15f, audio = 5f, subtitles = 35f, reliability = 35f, size = 10f),
        ContentType.K_DRAMA to Weights(quality = 15f, audio = 10f, subtitles = 35f, reliability = 30f, size = 10f),
        ContentType.J_DRAMA to Weights(quality = 15f, audio = 10f, subtitles = 35f, reliability = 30f, size = 10f),
        ContentType.LIVE_EVENT to Weights(quality = 20f, audio = 10f, subtitles = 5f, reliability = 55f, size = 10f)
    )

    /**
     * Content metadata for classification and scoring.
     */
    data class ContentContext(
        val contentType: String?,
        val genres: String?,
        val contentLanguage: String?,
        val title: String?,
        val season: Int?,
        val episode: Int?
    )

    /**
     * Classify content type from metadata.
     */
    fun classifyContent(ctx: ContentContext): ContentType {
        val titleLower = ctx.title?.lowercase().orEmpty()
        val genresLower = ctx.genres?.lowercase().orEmpty()
        val lang = ctx.contentLanguage?.lowercase()

        // Live event detection
        if (genresLower.contains("sport") || genresLower.contains("news") ||
            titleLower.contains("live") && (titleLower.contains("sport") || titleLower.contains("fight") || titleLower.contains("match"))) {
            return ContentType.LIVE_EVENT
        }

        // Anime detection: Animation genre + (Japanese/Chinese/Korean language OR anime keywords)
        val isAnimation = genresLower.contains("animation") || genresLower.contains("anime")
        val hasAnimeKeywords = listOf("anime", "otaku", "shonen", "shoujo", "seinen", "isekai", "mecha")
            .any { titleLower.contains(it) }
        val isJapaneseLike = lang in listOf("ja", "jp", "ko", "kr", "zh", "cn")

        if (isAnimation && (isJapaneseLike || hasAnimeKeywords)) {
            return ContentType.ANIME
        }

        // K-drama detection
        if (lang in listOf("ko", "kr") || genresLower.contains("korean") || titleLower.contains("kdrama")) {
            return ContentType.K_DRAMA
        }

        // J-drama detection
        if (lang in listOf("ja", "jp") || genresLower.contains("japanese drama")) {
            return ContentType.J_DRAMA
        }

        return ContentType.MAINSTREAM
    }

    /**
     * Score a single stream (0-100 per dimension).
     */
    fun scoreStream(stream: Stream, ctx: ContentContext): StreamScore {
        val qualityScore = scoreQuality(stream)
        val audioScore = scoreAudio(stream)
        val subtitleScore = scoreSubtitles(stream, ctx)
        val reliabilityScore = scoreReliability(stream)
        val sizeScore = scoreSize(stream)

        val contentType = classifyContent(ctx)
        val weights = WEIGHTS[contentType] ?: WEIGHTS[ContentType.MAINSTREAM]!!

        val total = (
            qualityScore * weights.quality +
            audioScore * weights.audio +
            subtitleScore * weights.subtitles +
            reliabilityScore * weights.reliability +
            sizeScore * weights.size
            ) / 100f

        return StreamScore(
            total = total,
            quality = qualityScore,
            audio = audioScore,
            subtitles = subtitleScore,
            reliability = reliabilityScore,
            size = sizeScore,
            contentType = contentType
        )
    }

    /**
     * Quality score (0-100).
     */
    private fun scoreQuality(stream: Stream): Float {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val resolution = parsed?.resolution?.lowercase() ?: stream.quality?.lowercase()
        val hdr = parsed?.hdr?.joinToString(" ")?.lowercase()

        var score = when {
            resolution == null || resolution == "unknown" -> 40f
            resolution.contains("2160") || resolution.contains("4k") || resolution.contains("uhd") -> 100f
            resolution.contains("1080") -> 85f
            resolution.contains("720") -> 60f
            resolution.contains("480") -> 35f
            resolution.contains("360") -> 20f
            else -> 40f
        }

        // HDR bonus
        if (!hdr.isNullOrBlank()) {
            when {
                hdr.contains("dv") || hdr.contains("dolby") -> score += 10f
                hdr.contains("hdr10+") -> score += 8f
                hdr.contains("hdr10") || hdr.contains("hdr") -> score += 5f
                hdr.contains("hlg") -> score += 3f
            }
        }

        // Bit depth bonus
        val bitDepth = parsed?.bitDepth?.lowercase()
        if (bitDepth == "10" || bitDepth == "10bit") score += 3f

        return score.coerceAtMost(100f)
    }

    /**
     * Audio score (0-100).
     */
    private fun scoreAudio(stream: Stream): Float {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val audio = parsed?.audio?.joinToString(" ")?.lowercase()
        val channels = parsed?.channels?.joinToString(" ")?.lowercase()

        if (audio.isNullOrBlank() && channels.isNullOrBlank()) return 50f

        var score = 50f

        // Audio codec scoring
        if (!audio.isNullOrBlank()) {
            when {
                audio.contains("atmos") -> score = 100f
                audio.contains("dts-hd") || audio.contains("dts:x") || audio.contains("dtshd") -> score = 95f
                audio.contains("truehd") -> score = 90f
                audio.contains("dts") -> score = 80f
                audio.contains("eac3") || audio.contains("e-ac-3") || audio.contains("dd+") -> score = 75f
                audio.contains("ac3") || audio.contains("dd5.1") || audio.contains("dd 5.1") -> score = 70f
                audio.contains("aac") || audio.contains("flac") -> score = 65f
                audio.contains("mp3") || audio.contains("opus") -> score = 40f
            }
        }

        // Channel count bonus
        if (!channels.isNullOrBlank()) {
            when {
                channels.contains("7.1") || channels.contains("8") -> score += 5f
                channels.contains("5.1") || channels.contains("6") -> score += 3f
                channels.contains("2.0") || channels.contains("stereo") -> score -= 5f
            }
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Subtitle score (0-100).
     * Based on detected languages in the stream metadata.
     */
    private fun scoreSubtitles(stream: Stream, ctx: ContentContext): Float {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val languages = parsed?.languages?.map { it.lowercase() }
        val contentLang = ctx.contentLanguage?.lowercase()

        // If no language info, assume neutral
        if (languages.isNullOrEmpty()) return 50f

        var score = 50f

        // Check if content language is present (high value for non-English content)
        if (contentLang != null && contentLang in languages) {
            score += 20f
        }

        // English subtitles are always valuable
        if ("english" in languages || "en" in languages || "eng" in languages) {
            score += 15f
        }

        // Multiple languages = more options
        if (languages.size >= 3) score += 10f
        else if (languages.size >= 2) score += 5f

        // For anime/K-drama/J-drama, heavily reward native language subs
        val contentType = classifyContent(ctx)
        if (contentType in listOf(ContentType.ANIME, ContentType.K_DRAMA, ContentType.J_DRAMA)) {
            val nativeLang = when (contentType) {
                ContentType.ANIME -> listOf("japanese", "ja", "jpn")
                ContentType.K_DRAMA -> listOf("korean", "ko", "kor")
                ContentType.J_DRAMA -> listOf("japanese", "ja", "jpn")
                else -> emptyList()
            }
            if (nativeLang.any { it in languages }) {
                score += 15f
            }
        }

        return score.coerceAtMost(100f)
    }

    /**
     * Reliability score (0-100).
     * Based on debrid cache status, URL availability, and source trust.
     */
    private fun scoreReliability(stream: Stream): Float {
        var score = 50f

        // Debrid cache status is the strongest signal
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CACHED -> score = 100f
            StreamDebridCacheState.NOT_CACHED -> score = 20f
            StreamDebridCacheState.CHECKING -> score = 40f
            StreamDebridCacheState.UNKNOWN, null -> {
                // No debrid info, check URL availability
                score = when {
                    stream.isDirectDebrid() -> 95f
                    stream.getStreamUrl() != null -> 70f
                    stream.isTorrent() -> 50f
                    else -> 30f
                }
            }
        }

        // Direct debrid bonus (already resolved, instant play)
        if (stream.isDirectDebrid()) {
            score = 95f.coerceAtLeast(score)
        }

        // Torrent with many trackers = more reliable
        val sourceCount = stream.sources?.size ?: 0
        if (sourceCount >= 10) score += 5f
        else if (sourceCount >= 5) score += 3f

        val seedCount = Regex("(?:seeders?|seeds?)\\s*[:=]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find("${stream.name.orEmpty()} ${stream.title.orEmpty()} ${stream.description.orEmpty()}")
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (seedCount != null) {
            score += when {
                seedCount >= 100 -> 10f
                seedCount >= 25 -> 6f
                seedCount >= 5 -> 3f
                else -> -10f
            }
        }

        return score.coerceIn(0f, 100f)
    }

    /**
     * Size score (0-100).
     * Penalize files that are too small (likely compressed/re-encoded) or
     * unreasonably large for the quality level.
     */
    private fun scoreSize(stream: Stream): Float {
        val sizeBytes = stream.behaviorHints?.videoSize
            ?: stream.clientResolve?.stream?.raw?.size
            ?: stream.debridCacheStatus?.cachedSize

        if (sizeBytes == null || sizeBytes <= 0) return 60f

        val sizeGB = sizeBytes / (1024.0 * 1024.0 * 1024.0)
        val resolution = stream.clientResolve?.stream?.raw?.parsed?.resolution?.lowercase()
            ?: stream.quality?.lowercase()

        // Expected size ranges per resolution (for 2-hour movie)
        val expectedSizeGB = when {
            resolution == null -> 4.0
            resolution.contains("2160") || resolution.contains("4k") -> 15.0
            resolution.contains("1080") -> 8.0
            resolution.contains("720") -> 4.0
            resolution.contains("480") -> 2.0
            else -> 4.0
        }

        // Score based on ratio to expected size
        val ratio = sizeGB / expectedSizeGB
        return when {
            ratio < 0.3 -> 20f  // Way too small, likely low quality
            ratio < 0.5 -> 40f  // Too small
            ratio < 0.7 -> 60f  // Below expected
            ratio < 0.9 -> 80f  // Good
            ratio <= 1.3 -> 100f // Perfect range
            ratio <= 1.8 -> 85f  // Above expected but acceptable
            ratio <= 2.5 -> 70f  // Large, might be slow
            else -> 50f  // Very large, likely remux or multi-audio
        }
    }

    /**
     * Hard filter: disqualify streams that should never autoplay.
     */
    fun isHardDisqualified(stream: Stream): Boolean {
        val searchText = buildString {
            append(stream.name.orEmpty()).append(' ')
            append(stream.title.orEmpty()).append(' ')
            append(stream.description.orEmpty()).append(' ')
            append(stream.behaviorHints?.filename.orEmpty())
        }.lowercase()

        // CAM/TS/TC recordings
        val camPatterns = listOf(
            "\\bcam\\b", "\\bts\\b", "\\btc\\b", "\\btelesync\\b", "\\btelecine\\b",
            "\\bpdvd\\b", "\\bpredvd\\b", "\\bscreener\\b", "\\bdvdscr\\b", "\\br5\\b"
        )
        if (camPatterns.any { Regex(it).containsMatchIn(searchText) }) return true

        // Watermarked/promo
        if (searchText.contains("watermark") || searchText.contains("promo")) return true

        return false
    }
}

/**
 * Result of scoring a single stream.
 */
data class StreamScore(
    val total: Float,
    val quality: Float,
    val audio: Float,
    val subtitles: Float,
    val reliability: Float,
    val size: Float,
    val contentType: StreamScoringEngine.ContentType
)
