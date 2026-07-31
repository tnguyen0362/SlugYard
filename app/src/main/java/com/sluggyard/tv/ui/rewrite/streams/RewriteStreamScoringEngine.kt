package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.debrid.StreamTextSizeParser
import com.sluggyard.tv.core.streamresolution.StreamCacheState

/**
 * Rewrite stream auto-pick.
 *
 * 1. Hard gates — eligible + cached/direct only (uncached stays in Sources)
 * 2. Soft rank by content kind:
 *    - Anime: softsubs → Dual-Audio → seeders → decode → quality → size
 *    - K/J drama: softsubs → seeders → quality → size (Dual-Audio ignored)
 *    - Movies / mainstream: seeders → quality → size → light softsub signal
 *
 * Preferred subtitle language is never English-hardcoded.
 */
internal object RewriteStreamScoringEngine {
    enum class ContentKind { MAINSTREAM, ANIME, K_DRAMA, J_DRAMA, LIVE }

    data class Context(
        val title: String,
        val contentType: String,
        val genres: List<String> = emptyList(),
        val language: String? = null,
        /**
         * User subtitle preference from settings (e.g. "en", "es", "pt-br").
         * Empty / "none" means no language preference — still prefer softsubs over RAW for anime.
         */
        val preferredSubtitleLanguage: String? = null,
    )

    /**
     * Lexicographic rank among candidates already inside the same cache tier.
     * Compare order depends on [kind].
     */
    data class Rank(
        val kind: ContentKind,
        val softsubFit: Int,
        val dualFit: Int,
        val seeders: Int,
        val decodeFit: Int,
        val quality: Int,
        val sizeFit: Int,
    ) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int = when (kind) {
            // Anime: subs first, then dual, then seeders.
            ContentKind.ANIME -> compareValuesBy(
                this,
                other,
                { it.softsubFit },
                { it.dualFit },
                { it.seeders },
                { it.decodeFit },
                { it.quality },
                { it.sizeFit },
            )
            // K/J drama: subs matter; dual does not.
            ContentKind.K_DRAMA, ContentKind.J_DRAMA -> compareValuesBy(
                this,
                other,
                { it.softsubFit },
                { it.seeders },
                { it.quality },
                { it.sizeFit },
            )
            // Movies / live / mainstream: seeders + quality first.
            ContentKind.MAINSTREAM, ContentKind.LIVE -> compareValuesBy(
                this,
                other,
                { it.seeders },
                { it.quality },
                { it.sizeFit },
                { it.softsubFit },
            )
        }

        /** Back-compat for tests that still mention "trackFit". */
        val trackFit: Int get() = softsubFit * 10 + dualFit
    }

    fun choose(candidates: List<RewriteStreamCandidate>, context: Context): RewriteStreamCandidate? {
        val playable = candidates
            .filterNot { it.isExcludedResolution() }
            .filter { it.isAutoPlayEligible() }
        if (playable.isEmpty()) return null

        val safePool = playable.filterNot { it.isKnownUnsupportedDolbyVision() }.ifEmpty { playable }
        // Auto-Play is instant/cached only. Uncached torrents stay in Sources for manual pick —
        // never auto-start a debrid download from ranking.
        val instantPool = safePool.filter { it.isInstantOrCachedAutoPlay() }
        // Hard gate: episode auto-play must be a single-episode-sized file. MediaFusion often
        // marks whole season packs CACHED (~15–80GB) with opaque titles; softsub ranking then
        // picks them and MPV hangs on TorBox CDN ("Starting stream" forever).
        val sizedPool = instantPool.filter { it.isSaneAutoPlaySize(context) }
        return sizedPool.maxWithOrNull(rankComparator(context))
    }

    fun rankedCandidates(
        candidates: List<RewriteStreamCandidate>,
        context: Context,
    ): List<RewriteStreamCandidate> {
        val wellFormed = candidates
            .filter { it.isWellFormed() }
            .filterNot { it.isExcludedResolution() }
            .distinctBy { it.dedupeKey() }
        val safePool = wellFormed.filterNot { it.isKnownUnsupportedDolbyVision() }.ifEmpty { wellFormed }
        return safePool.sortedWith(rankComparator(context).reversed())
    }

    fun rank(candidate: RewriteStreamCandidate, context: Context): Rank {
        val text = candidate.searchText().lowercase()
        val releaseText = candidate.releaseText().lowercase()
        val kind = classify(context)
        val episodeLike = isEpisodeLike(context)
        val animeEpisode = kind == ContentKind.ANIME && episodeLike
        // Softsub/dual signals must come from the release name — not addon chrome
        // ("MediaFusion | Midnight 🧲 ⏳ 720p") or dump metadata that false-triggers "sub"/"en".
        val tracks = trackSignals(releaseText, context, kind)
        return Rank(
            kind = kind,
            softsubFit = tracks.softsubFit,
            dualFit = tracks.dualFit,
            seeders = seederFit(candidate),
            decodeFit = decodeFit(text, animeEpisode),
            quality = qualityFit(text, animeEpisode),
            sizeFit = sizeFit(candidate, context, episodeLike),
        )
    }

    /**
     * Debug / unit-test helper: packs [Rank] into a single int for rough comparisons.
     * Prefer [rank] / [choose] in new code.
     */
    fun score(candidate: RewriteStreamCandidate, context: Context): Int {
        val r = rank(candidate, context)
        return when (r.kind) {
            ContentKind.ANIME ->
                r.softsubFit * 1_000_000 + r.dualFit * 100_000 + r.seeders * 1_000 +
                    r.decodeFit * 100 + r.quality * 10 + r.sizeFit
            ContentKind.K_DRAMA, ContentKind.J_DRAMA ->
                r.softsubFit * 1_000_000 + r.seeders * 1_000 + r.quality * 10 + r.sizeFit
            ContentKind.MAINSTREAM, ContentKind.LIVE ->
                r.seeders * 1_000_000 + r.quality * 1_000 + r.sizeFit * 10 + r.softsubFit
        }
    }

    fun contentKind(context: Context): ContentKind = classify(context)

    fun filterForDisplay(candidates: List<RewriteStreamCandidate>): List<RewriteStreamCandidate> =
        candidates.filterNot { it.isExcludedResolution() }

    private fun rankComparator(context: Context): Comparator<RewriteStreamCandidate> =
        compareBy<RewriteStreamCandidate> { rank(it, context) }
            .thenBy { it.id }

    private data class TrackSignals(val softsubFit: Int, val dualFit: Int)

    /**
     * SoftsubFit (higher better):
     * 3 preferred-lang / multi-subs / known softsub group
     * 2 any softsub/sub tag
     * 1 none
     * 0 RAW / hardsub
     *
     * DualFit (anime only; always 0 for K/J drama):
     * 1 has Dual-Audio / Multi-Audio
     * 0 otherwise
     */
    private fun trackSignals(text: String, context: Context, kind: ContentKind): TrackSignals {
        val preferred = normalizePreferredSubtitle(context.preferredSubtitleLanguage)
        val softsub = isLikelySoftsubRelease(text)
        val multiSubs = MULTI_SUBS_REGEX.containsMatchIn(text)
        val explicitSub = EXPLICIT_SUB_REGEX.containsMatchIn(text) || textHasBracketLanguageTag(text)
        val hasSoftSignal = softsub || multiSubs || explicitSub
        val dualAudio = DUAL_AUDIO_REGEX.containsMatchIn(text)
        val hardsub = HARD_SUB_REGEX.containsMatchIn(text)
        val raw = RAW_REGEX.containsMatchIn(text)
        val hasPreferred = preferred != null && textMentionsLanguage(text, preferred)

        val softsubFit = when {
            raw || (hardsub && !hasSoftSignal) -> 0
            hasSoftSignal && (hasPreferred || multiSubs || softsub) -> 3
            hasSoftSignal -> 2
            else -> 1
        }

        val dualFit = when (kind) {
            ContentKind.ANIME -> if (dualAudio) 1 else 0
            ContentKind.K_DRAMA, ContentKind.J_DRAMA -> 0
            ContentKind.MAINSTREAM, ContentKind.LIVE -> if (dualAudio) 1 else 0
        }
        return TrackSignals(softsubFit = softsubFit, dualFit = dualFit)
    }

    private fun decodeFit(text: String, animeEpisode: Boolean): Int {
        if (!animeEpisode) return 5
        // Hi10 / AVC10 → often software decode on leanback; HEVC/AV1 10-bit is fine.
        return if (isLikelyAvc10BitSoftwareDecode(text)) 1 else 5
    }

    private fun qualityFit(text: String, animeEpisode: Boolean): Int {
        var base = when {
            RESOLUTION_UHD.containsMatchIn(text) -> if (animeEpisode) 70 else 100
            RESOLUTION_1080.containsMatchIn(text) -> 85
            RESOLUTION_720.containsMatchIn(text) -> 60
            RESOLUTION_SD.containsMatchIn(text) -> 35
            else -> 40
        }
        if (MODERN_CODEC.containsMatchIn(text)) base += 8
        if (HDR_TAG.containsMatchIn(text)) base += 5
        if (CAM_TAG.containsMatchIn(text)) base -= 35
        if (AI_UPSCALE.containsMatchIn(text)) base -= 40
        return base.coerceIn(0, 100)
    }

    private fun seederFit(candidate: RewriteStreamCandidate): Int {
        val seeders = candidate.seeders ?: parseSeeders(candidate.searchText())
        return when {
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
    }

    private fun sizeFit(
        candidate: RewriteStreamCandidate,
        context: Context,
        episodeLike: Boolean,
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

    private fun classify(context: Context): ContentKind {
        val type = context.contentType.trim().lowercase()
        val genres = context.genres.joinToString(" ").lowercase()
        val text = "${context.title} $type $genres".lowercase()
        val liveType = type in setOf("live", "event", "channel")
        if (liveType || ("live" in text && listOf("fight", "match", "event").any(text::contains))) {
            return ContentKind.LIVE
        }
        if ("k-drama" in text || "kdrama" in text || "korean drama" in text) return ContentKind.K_DRAMA
        if ("j-drama" in text || "jdrama" in text || "japanese drama" in text) return ContentKind.J_DRAMA
        if (type == "anime") return ContentKind.ANIME
        val hasAnimeGenre = genres.split(Regex("[,|/\\s]+")).any { it == "anime" } || "anime" in genres
        if (hasAnimeGenre) return ContentKind.ANIME
        val animation = "animation" in genres || "animation" in text
        val asianLanguage = context.language?.lowercase() in setOf(
            "ja", "jp", "jpn", "ko", "kr", "kor", "zh", "cn", "zhtw", "zhcn",
        )
        val animeTerm = listOf(
            "otaku", "shonen", "shounen", "shoujo", "shojo", "seinen", "josei",
            "isekai", "mecha", "slice of life", "magical girl", "harem",
        ).any(text::contains)
        if (animation && (asianLanguage || animeTerm)) return ContentKind.ANIME
        // Cinemeta often tags anime as Animation without ja / "anime" keyword.
        if (isEpisodeLike(context) && animation) return ContentKind.ANIME
        return ContentKind.MAINSTREAM
    }

    private fun isLikelySoftsubRelease(text: String): Boolean {
        if (SOFTSUB_KEYWORD.containsMatchIn(text)) return true
        return ANIME_SOFTSUB_GROUPS.any { group ->
            Regex("""[\[(]${Regex.escape(group)}[\])]""").containsMatchIn(text) ||
                (group.length >= 5 &&
                    Regex("""(?:^|[\s._-])${Regex.escape(group)}(?:$|[\s._-])""")
                        .containsMatchIn(text))
        }
    }

    private fun isLikelyAvc10BitSoftwareDecode(text: String): Boolean {
        if (Regex("\\bhi10p?\\b").containsMatchIn(text)) return true
        if (Regex("\\bavc[\\s._-]?10\\b").containsMatchIn(text)) return true
        if (MODERN_CODEC.containsMatchIn(text)) return false
        val hasAvc = Regex("\\b(h\\.?264|x264|avc)\\b").containsMatchIn(text)
        val has10Bit = Regex("\\b10[\\s._-]?bits?\\b").containsMatchIn(text)
        return hasAvc && has10Bit
    }

    private fun textHasBracketLanguageTag(text: String): Boolean =
        Regex("""[\[(]\s*[a-z]{2,3}(?:-[a-z0-9]+)?\s*[\])]""").containsMatchIn(text)

    private fun normalizePreferredSubtitle(preferred: String?): String? {
        val code = preferred?.trim()?.lowercase().orEmpty()
        if (code.isEmpty() || code == "none") return null
        return code
    }

    private fun textMentionsLanguage(text: String, preferred: String): Boolean {
        val aliases = languageAliases(preferred)
        return aliases.any { alias ->
            Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(text) ||
                Regex("""[\[(]\s*${Regex.escape(alias)}\s*[\])]""").containsMatchIn(text)
        }
    }

    private fun languageAliases(preferred: String): List<String> {
        val base = preferred.substringBefore('-').substringBefore('_')
        return when (base) {
            "en", "eng", "english" -> listOf("en", "eng", "english")
            "es", "spa", "spanish" -> listOf("es", "spa", "spanish", "espanol", "español", "latino", "latin")
            "pt", "por", "portuguese" -> listOf("pt", "por", "portuguese", "portugues", "brazilian", "brasil", "pt-br", "ptbr")
            "fr", "fre", "fra", "french" -> listOf("fr", "fre", "fra", "french")
            "de", "ger", "deu", "german" -> listOf("de", "ger", "deu", "german")
            "it", "ita", "italian" -> listOf("it", "ita", "italian")
            "ru", "rus", "russian" -> listOf("ru", "rus", "russian")
            "ar", "ara", "arabic" -> listOf("ar", "ara", "arabic")
            "hi", "hin", "hindi" -> listOf("hi", "hin", "hindi")
            "ja", "jpn", "japanese" -> listOf("ja", "jpn", "japanese", "jp")
            "ko", "kor", "korean" -> listOf("ko", "kor", "korean", "kr")
            "zh", "chi", "zho", "chinese" -> listOf("zh", "chi", "zho", "chinese", "mandarin", "cantonese")
            else -> listOf(preferred, base).distinct()
        }
    }

    private fun episodeSizeScore(sizeGb: Double, text: String, expectedGb: Double): Int {
        when {
            sizeGb >= 40.0 -> return 0
            sizeGb >= 25.0 -> return 3
            sizeGb >= 15.0 -> return 10
            sizeGb >= 10.0 -> return 20
            sizeGb >= 6.0 && !RESOLUTION_UHD.containsMatchIn(text) -> return 30
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

    private fun expectedSizeGb(text: String, episodeLike: Boolean): Double {
        val uhd = RESOLUTION_UHD.containsMatchIn(text)
        val fhd = RESOLUTION_1080.containsMatchIn(text)
        val hd = RESOLUTION_720.containsMatchIn(text)
        val sd = RESOLUTION_SD.containsMatchIn(text)
        return if (episodeLike) {
            when {
                uhd -> 4.0
                fhd -> 1.5
                hd -> 0.7
                sd -> 0.35
                else -> 1.2
            }
        } else {
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
        listOf(
            title,
            detailLabel.orEmpty(),
            metadataText.orEmpty(),
            filename.orEmpty(),
            streamDescription.orEmpty(),
            bingeGroup.orEmpty(),
        ).joinToString(" ")

    /** Release identity only — used for softsub/dual detection (ignores addon chrome). */
    private fun RewriteStreamCandidate.releaseText(): String =
        listOf(
            filename.orEmpty(),
            title,
            detailLabel.orEmpty(),
            streamDescription.orEmpty(),
        ).joinToString(" ")

    private fun RewriteStreamCandidate.isWellFormed(): Boolean =
        !directUrl.isNullOrBlank() || !infoHash.isNullOrBlank()

    /**
     * Single-episode auto-play size ceiling. Season packs marked CACHED still report total
     * torrent size (often 15–80GB) and break playback when auto-started.
     */
    private fun RewriteStreamCandidate.isSaneAutoPlaySize(context: Context): Boolean {
        if (!isEpisodeLike(context)) return true
        val size = resolveSizeBytes(this) ?: return true
        if (size <= 0L) return true
        val text = searchText().lowercase()
        val maxGb = if (RESOLUTION_UHD.containsMatchIn(text)) 12.0 else 5.0
        return size <= (maxGb * 1_073_741_824.0)
    }

    private fun RewriteStreamCandidate.isAutoPlayEligible(): Boolean {
        if (!isWellFormed()) return false
        val hash = infoHash?.trim().orEmpty()
        if (hash.isNotBlank()) {
            return when (cacheState) {
                // UNKNOWN used to be eligible as a post-probe fallback; Auto-Play no longer
                // starts uncached torrents (Sources still lists them for manual pick).
                StreamCacheState.CACHED -> true
                StreamCacheState.NOT_APPLICABLE -> true
                StreamCacheState.UNKNOWN, StreamCacheState.CHECKING, StreamCacheState.NOT_CACHED -> false
            }
        }
        val url = directUrl?.trim().orEmpty()
        if (url.isBlank()) return false
        return !isNonInstantStreamUrl(url)
    }

    private fun RewriteStreamCandidate.isInstantOrCachedAutoPlay(): Boolean {
        if (!isAutoPlayEligible()) return false
        val hash = infoHash?.trim().orEmpty()
        if (hash.isBlank()) return true
        return when (cacheState) {
            StreamCacheState.CACHED, StreamCacheState.NOT_APPLICABLE -> true
            StreamCacheState.UNKNOWN, StreamCacheState.CHECKING, StreamCacheState.NOT_CACHED -> false
        }
    }

    private fun RewriteStreamCandidate.isKnownUnsupportedDolbyVision(): Boolean {
        val text = searchText().lowercase()
        return Regex("\\b(?:dolby[ ._-]*vision|dv)\\s*(?:profile\\s*|p\\s*)?8\\.6\\b").containsMatchIn(text)
    }

    private fun RewriteStreamCandidate.isExcludedResolution(): Boolean =
        RESOLUTION_720.containsMatchIn(searchText().lowercase())

    private fun RewriteStreamCandidate.dedupeKey(): String = when {
        !directUrl.isNullOrBlank() -> "url:$directUrl"
        !infoHash.isNullOrBlank() -> "hash:${infoHash.lowercase()}:${fileIndex ?: -1}"
        else -> id
    }

    private val ANIME_SOFTSUB_GROUPS = listOf(
        "subsplease",
        "erai-raws",
        "erairaws",
        "horriblesubs",
        "judas",
        "ember",
        "asenshi",
        "commie",
        "gg-anbu",
        "gspectre",
        "doki",
        "whynot",
        "tsundere-reai",
        "lostyears",
        "hanabi",
        "sam",
        "motonoke",
        "yameii",
        "tof",
        "ksd",
        "acg",
        "neko-mimi",
        "nekomimi",
        "anime time",
        "animetime",
        "rigav1",
    )

    private val MULTI_SUBS_REGEX = Regex("\\b(multi.?subs?|dual.?subs?|multiple.?subtitle)\\b")
    private val EXPLICIT_SUB_REGEX = Regex("\\b(sub|subs|subtitle|subtitles|cc)\\b")
    private val DUAL_AUDIO_REGEX = Regex("\\b(dual.?audio|multi.?audio)\\b")
    private val HARD_SUB_REGEX = Regex("\\b(hardsub|hard.?subs?|burned.?in)\\b")
    private val RAW_REGEX = Regex("\\b(raw|no.?subs?|unsubbed)\\b")
    private val SOFTSUB_KEYWORD = Regex("\\b(softsub|soft.?subs?|ass|ssa)\\b")
    private val RESOLUTION_UHD = Regex("\\b(2160p|4k|uhd)\\b")
    private val RESOLUTION_1080 = Regex("\\b1080p\\b")
    private val RESOLUTION_720 = Regex("\\b720p\\b")
    private val RESOLUTION_SD = Regex("\\b(480p|sd)\\b")
    private val MODERN_CODEC = Regex("\\b(av1|hevc|h\\.?265|x265)\\b")
    private val HDR_TAG = Regex("\\b(hdr10\\+?|dolby.?vision|dv)\\b")
    private val CAM_TAG = Regex("\\b(cam|hdcam|telesync|tscam|telecine|screener)\\b")
    private val AI_UPSCALE = Regex("\\b(ai.?upscale|upscaled|ai.?enhance|reupscale|topaz|esrgan)\\b")
}
