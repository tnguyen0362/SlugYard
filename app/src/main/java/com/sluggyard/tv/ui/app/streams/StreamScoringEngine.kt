package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.debrid.StreamTextSizeParser
import com.sluggyard.tv.core.streamresolution.StreamCacheState

/**
 * Rewrite stream auto-pick.
 *
 * 1. Hard gates — eligible candidates only (720p / DV 8.6 / size / digital-release)
 * 2. Soft rank by content kind:
 *    - Anime: Dual+soft → softsub format → Instant as **tie-break only** → seeds/decode/qual/size
 *    - K/J drama: softsubs → seeders → quality → size (Dual-Audio ignored)
 *    - Movies / mainstream: softsub language → seeders → quality → size (Instant-first pool)
 *
 * Preferred subtitle language is never English-hardcoded.
 * Anime deliberately does **not** hard-gate Instant: an Instant Eng-PGS mono remux must not
 * beat a Dual-Audio softsub seaDex / Judas pack just because TorBox already has it.
 */
internal object StreamScoringEngine {
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
        /**
         * TMDB digital-release status for movies. When [DigitalReleasePolicy.Status.NOT_YET],
         * auto-play refuses CAM/TS/SCR/trailer junk. Null / UNKNOWN fails open.
         */
        val digitalReleaseStatus: DigitalReleasePolicy.Status? = null,
        /**
         * Play-once inventory by lowercase infoHash. Overlay on title heuristics — free
         * second-screen signal after the user has opened that release once.
         */
        val observedTracksByHash: Map<String, ObservedReleaseTracks> = emptyMap(),
    )

    /**
     * Lexicographic rank among candidates already past hard gates.
     * Compare order depends on [kind].
     */
    data class Rank(
        val kind: ContentKind,
        val softsubFit: Int,
        val dualFit: Int,
        /** Instant/direct=2, UNKNOWN=1, NOT_CACHED=0 — anime only uses this *after* tracks. */
        val cacheFit: Int,
        /** SeaDex / releases.moe curated row — soft anime preference only after track fit. */
        val curatorFit: Int,
        val seeders: Int,
        val decodeFit: Int,
        val quality: Int,
        val sizeFit: Int,
        /** True when softsub/dual came from observed memory rather than title alone. */
        val usedObservedTracks: Boolean = false,
    ) : Comparable<Rank> {
        /**
         * Dual only counts when the release also has a softsub signal — Dual-only Kametsu
         * packs must not outrank real softsubs.
         */
        val effectiveDualFit: Int get() = if (softsubFit >= 2) dualFit else 0

        override fun compareTo(other: Rank): Int = when (kind) {
            // Anime: Dual+soft first (the point of the scorer), then sub format, Instant last
            // among track ties so Instant Eng-PGS mono loses to uncached Dual multi-soft.
            ContentKind.ANIME -> compareValuesBy(
                this,
                other,
                { it.effectiveDualFit },
                { it.softsubFit },
                { it.curatorFit },
                { it.cacheFit },
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
            // Movies / live / mainstream: softsub language fit before seeders/quality so a
            // PT-only 4K remux cannot beat an English-tagged candidate when preferred=en
            // (softsubFit 3–4 vs conflict/other-lang 1).
            ContentKind.MAINSTREAM, ContentKind.LIVE -> compareValuesBy(
                this,
                other,
                { it.softsubFit },
                { it.seeders },
                { it.quality },
                { it.sizeFit },
            )
        }

        /** Back-compat for tests that still mention "trackFit". */
        val trackFit: Int get() = softsubFit * 10 + dualFit
    }

    fun choose(
        candidates: List<StreamCandidate>,
        context: Context,
        /**
         * When no Instant/Cached row survives filters, allow TorBox download starts
         * (NOT_CACHED / UNKNOWN). Prefer leaving this false until cache probes settle
         * so Finding does not race into a slow uncached resolve.
         *
         * Anime: when true, Instant and uncached are scored **together** (tracks > Instant).
         */
        allowUncachedFallback: Boolean = false,
    ): StreamCandidate? {
        val kind = classify(context)
        val playable = candidates
            .filterNot { it.isExcludedResolution() }
            .filter { it.isAutoPlayEligible(allowUncachedFallback) }
        if (playable.isEmpty()) return null

        val safePool = playable.filterNot { it.isKnownUnsupportedDolbyVision() }.ifEmpty { playable }
        val instantPool = safePool.filter { it.isInstantOrCachedAutoPlay() }
        // Mainstream: Instant hard gate. Anime: Instant is a rank key only — when uncached
        // fallback is open, Dual+ASS uncached is allowed to beat Instant Eng-PGS mono.
        val cachePool = when {
            kind == ContentKind.ANIME && allowUncachedFallback -> {
                val combined = safePool.filter {
                    it.isInstantOrCachedAutoPlay() || it.isUncachedDebridAutoPlayEligible()
                }
                combined.ifEmpty { emptyList() }
            }
            instantPool.isNotEmpty() -> instantPool
            allowUncachedFallback -> safePool.filter { it.isUncachedDebridAutoPlayEligible() }
            else -> emptyList()
        }
        // Size gate: reject opaque huge torrents that hang TorBox/MPV, but allow named
        // multi-file season packs (Judas/Anime Time Dual+Multi-Subs) so episode file-select
        // can pick SxxExx out of the pack. Resolve failure still rejects and tries the next.
        val sizedPool = cachePool.filter { it.isSaneAutoPlaySize(context) }
        // With a subtitle preference, never auto-start wrong-language packs (PL/ES-only).
        // Anime: keep neutral Dual/Multi soft packs even when they omit "English" — the old
        // "must mention preferred" shrink preferred Eng-tagged PGS remuxes over SeaDex duals.
        // Mainstream: still prefer rows that mention preferred lang when any exist.
        val preferred = normalizePreferredSubtitle(context.preferredSubtitleLanguage)
        val languageSafePool = if (preferred != null) {
            val nonConflicting = sizedPool.filter { candidate ->
                !hasConflictingSubtitleLanguage(candidate.releaseText().lowercase(), preferred)
            }
            when (kind) {
                ContentKind.ANIME -> nonConflicting
                else -> {
                    val mentioningPreferred = nonConflicting.filter { candidate ->
                        textMentionsLanguage(candidate.releaseText().lowercase(), preferred)
                    }
                    when {
                        mentioningPreferred.isNotEmpty() -> mentioningPreferred
                        nonConflicting.isNotEmpty() -> nonConflicting
                        else -> emptyList()
                    }
                }
            }
        } else {
            sizedPool
        }
        // Pre-digital movies: refuse all auto-play until TMDB shows a past digital release.
        val playPool = languageSafePool.filter { candidate ->
            DigitalReleasePolicy.allowsAutoPlay(
                context.digitalReleaseStatus,
                candidate.releaseText(),
            )
        }
        val winner = playPool.maxWithOrNull(rankComparator(context)) ?: return null
        // During Finding (Instant-only), refuse mono PGS remuxes for anime so Dual/ASS can
        // still land — not softsubFit==4 (that also blocked Instant ASS when preferred=null).
        if (
            kind == ContentKind.ANIME &&
            !allowUncachedFallback &&
            !isAcceptableAnimeInstantAutoStart(winner, rank(winner, context))
        ) {
            return null
        }
        return winner
    }

    /**
     * Instant-only Finding may start dual softpacks and ASS immediately. Wait only on
     * **PGS mono** (Demon Slayer Instant chrome trap).
     */
    private fun isAcceptableAnimeInstantAutoStart(
        candidate: StreamCandidate,
        rank: Rank,
    ): Boolean {
        if (rank.effectiveDualFit >= 1) return true
        val text = candidate.releaseText().lowercase()
        if (PGS_SOFTSUB_FORMAT.containsMatchIn(text) && rank.dualFit == 0) return false
        return true
    }

    fun rankedCandidates(
        candidates: List<StreamCandidate>,
        context: Context,
    ): List<StreamCandidate> {
        val wellFormed = candidates
            .filter { it.isWellFormed() }
            .filterNot { it.isExcludedResolution() }
            .distinctBy { it.dedupeKey() }
        val safePool = wellFormed.filterNot { it.isKnownUnsupportedDolbyVision() }.ifEmpty { wellFormed }
        return safePool.sortedWith(rankComparator(context).reversed())
    }

    fun rank(candidate: StreamCandidate, context: Context): Rank {
        val text = candidate.searchText().lowercase()
        val releaseText = candidate.releaseText().lowercase()
        val kind = classify(context)
        val episodeLike = isEpisodeLike(context)
        val animeEpisode = kind == ContentKind.ANIME && episodeLike
        // Softsub/dual signals must come from the release name — not addon chrome
        // ("MediaFusion | Midnight 🧲 ⏳ 720p") or dump metadata that false-triggers "sub"/"en".
        val tracks = trackSignals(releaseText, context, kind, candidate)
        return Rank(
            kind = kind,
            softsubFit = tracks.softsubFit,
            dualFit = tracks.dualFit,
            cacheFit = cacheFit(candidate),
            curatorFit = if (kind == ContentKind.ANIME && candidate.isCuratedSeaDexSource()) 1 else 0,
            seeders = seederFit(candidate),
            decodeFit = decodeFit(text, animeEpisode),
            quality = qualityFit(text, animeEpisode, context.digitalReleaseStatus),
            sizeFit = sizeFit(candidate, context, episodeLike),
            usedObservedTracks = tracks.usedObserved,
        )
    }

    /**
     * Debug / unit-test helper: packs [Rank] into a single int for rough comparisons.
     * Prefer [rank] / [choose] in new code.
     */
    fun score(candidate: StreamCandidate, context: Context): Int {
        val r = rank(candidate, context)
        return when (r.kind) {
            ContentKind.ANIME ->
                r.effectiveDualFit * 10_000_000 + r.softsubFit * 1_000_000 + r.curatorFit * 200_000 +
                    r.cacheFit * 100_000 + r.seeders * 1_000 + r.decodeFit * 100 +
                    r.quality * 10 + r.sizeFit
            ContentKind.K_DRAMA, ContentKind.J_DRAMA ->
                r.softsubFit * 1_000_000 + r.seeders * 1_000 + r.quality * 10 + r.sizeFit
            ContentKind.MAINSTREAM, ContentKind.LIVE ->
                r.softsubFit * 1_000_000 + r.seeders * 1_000 + r.quality * 10 + r.sizeFit
        }
    }

    private fun cacheFit(candidate: StreamCandidate): Int {
        val hash = candidate.infoHash?.trim().orEmpty()
        if (hash.isBlank()) {
            // Direct playable URL — treat like Instant.
            return if (!candidate.directUrl.isNullOrBlank()) 2 else 0
        }
        return when (candidate.cacheState) {
            StreamCacheState.CACHED, StreamCacheState.NOT_APPLICABLE -> 2
            StreamCacheState.UNKNOWN -> 1
            StreamCacheState.NOT_CACHED, StreamCacheState.CHECKING -> 0
        }
    }

    fun contentKind(context: Context): ContentKind = classify(context)

    fun filterForDisplay(candidates: List<StreamCandidate>): List<StreamCandidate> =
        candidates.filterNot { it.isExcludedResolution() }

    private fun rankComparator(context: Context): Comparator<StreamCandidate> =
        compareBy<StreamCandidate> { rank(it, context) }
            .thenBy { it.id }

    private data class TrackSignals(
        val softsubFit: Int,
        val dualFit: Int,
        val usedObserved: Boolean = false,
    )

    /**
     * SoftsubFit (higher better) — preferred-language ladder, not a per-locale bandage:
     * 5 preferred-lang + ASS/SSA (or known ASS-tier Eng fansub with preferred set)
     * 4 preferred-lang + PGS/SUP (image softsubs still beat any wrong language)
     * 3 preferred-lang + SRT/VTT / plain softsub / Multi-Subs tag match
     * 2 softsub without preferred-lang tag (neutral multi / unmarked ASS-tier groups)
     * 1 no softsub signal (dub-only, opaque)
     * 0 RAW / hardsub / conflicting subtitle language (Polish/Spanish ASS never outrank Eng SRT)
     *
     * DualFit (anime only; always 0 for K/J drama):
     * 1 has Dual-Audio / Multi-Audio
     * 0 otherwise
     *
     * When [ObservedReleaseTracks] exist for this infoHash, softsub/dual take max(title, observed).
     * Observed never invents softsubs for conflicting-title packs that are clearly wrong-lang-only
     * (conflictingLang stays 0 from titles).
     *
     * Multi-Subs alone is NOT max score when a preference is set — A&C "Multi-Sub" packs
     * are often Latam/España-only and must not beat real Eng Dual packs or fall through
     * as auto-play winners.
     */
    private fun trackSignals(
        text: String,
        context: Context,
        kind: ContentKind,
        candidate: StreamCandidate? = null,
    ): TrackSignals {
        val preferred = normalizePreferredSubtitle(context.preferredSubtitleLanguage)
        val softsub = isLikelySoftsubRelease(text)
        val knownAssTierGroup = isKnownAnimeSoftsubGroup(text)
        val multiSubs = MULTI_SUBS_REGEX.containsMatchIn(text)
        val explicitSub = EXPLICIT_SUB_REGEX.containsMatchIn(text) || textHasBracketLanguageTag(text)
        val assFormat = ASS_SOFTSUB_FORMAT.containsMatchIn(text) || knownAssTierGroup
        val pgsFormat = PGS_SOFTSUB_FORMAT.containsMatchIn(text)
        val srtFormat = SRT_SOFTSUB_FORMAT.containsMatchIn(text)
        val hasSoftSignal = softsub || multiSubs || explicitSub || assFormat || pgsFormat || srtFormat
        val dualAudio = DUAL_AUDIO_REGEX.containsMatchIn(text)
        val hardsub = HARD_SUB_REGEX.containsMatchIn(text)
        val raw = RAW_REGEX.containsMatchIn(text)
        val hasPreferred = preferred != null && textMentionsLanguage(text, preferred)
        val conflictingLang = preferred != null && hasConflictingSubtitleLanguage(text, preferred)

        var softsubFit = when {
            raw || (hardsub && !hasSoftSignal) -> 0
            // Wrong-language ASS/SRT never beats preferred Eng SRT/PGS (score 0, out of auto-play).
            conflictingLang -> 0
            // Preferred language: ASS > PGS > SRT/plain soft — fall through within that ladder.
            hasPreferred && hasSoftSignal && assFormat -> 5
            hasPreferred && hasSoftSignal && pgsFormat -> 4
            hasPreferred && hasSoftSignal -> 3
            // Preferred set: known Eng fansub groups (SubsPlease…) ship ASS without saying Eng.
            preferred != null && knownAssTierGroup && hasSoftSignal -> 5
            // Preferred set: multi/soft without a lang tag — below any preferred-lang format.
            preferred != null && hasSoftSignal -> 2
            // No preference: keep ASS-tier ahead of plain softsubs.
            preferred == null && hasSoftSignal && assFormat -> 4
            preferred == null && hasSoftSignal && pgsFormat -> 3
            preferred == null && hasSoftSignal -> 3
            else -> 1
        }

        var dualFit = when (kind) {
            ContentKind.ANIME -> if (dualAudio) 1 else 0
            ContentKind.K_DRAMA, ContentKind.J_DRAMA -> 0
            ContentKind.MAINSTREAM, ContentKind.LIVE -> if (dualAudio) 1 else 0
        }

        var usedObserved = false
        val hash = candidate?.infoHash?.trim()?.lowercase().orEmpty()
        val observed = if (hash.isNotEmpty()) context.observedTracksByHash[hash] else null
        // Real player inventory always wins floor max — title tags are best-effort only.
        if (observed != null) {
            val (obsSoft, obsDual) = observed.toTitleScaleSignals(
                preferredSubtitleLanguage = context.preferredSubtitleLanguage,
                animeLike = kind == ContentKind.ANIME ||
                    kind == ContentKind.MAINSTREAM ||
                    kind == ContentKind.LIVE,
            )
            if (obsSoft > softsubFit) {
                softsubFit = obsSoft
                usedObserved = true
            }
            if (obsDual > dualFit) {
                dualFit = obsDual
                usedObserved = true
            }
        }
        return TrackSignals(softsubFit = softsubFit, dualFit = dualFit, usedObserved = usedObserved)
    }

    private fun decodeFit(text: String, animeEpisode: Boolean): Int {
        if (!animeEpisode) return 5
        // Hi10 / AVC10 → often software decode on leanback; HEVC/AV1 10-bit is fine.
        return if (isLikelyAvc10BitSoftwareDecode(text)) 1 else 5
    }

    private fun qualityFit(text: String, animeEpisode: Boolean, digitalReleaseStatus: DigitalReleasePolicy.Status?): Int {
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
        // Sources ranking: bury early-release junk when digital isn't out yet.
        if (digitalReleaseStatus == DigitalReleasePolicy.Status.NOT_YET &&
            DigitalReleasePolicy.isEarlyReleaseJunk(text)
        ) {
            base -= 50
        }
        return base.coerceIn(0, 100)
    }

    private fun seederFit(candidate: StreamCandidate): Int {
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
        candidate: StreamCandidate,
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
        return isKnownAnimeSoftsubGroup(text)
    }

    /** Fansub groups that ship styled ASS without putting "ASS" in the release name. */
    private fun isKnownAnimeSoftsubGroup(text: String): Boolean =
        ANIME_SOFTSUB_GROUPS.any { group ->
            Regex("""[\[(]${Regex.escape(group)}[\])]""").containsMatchIn(text) ||
                (group.length >= 5 &&
                    Regex("""(?:^|[\s._-])${Regex.escape(group)}(?:$|[\s._-])""")
                        .containsMatchIn(text))
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
            "fr", "fre", "fra", "french" -> listOf("fr", "fre", "fra", "french", "vostfr", "vf")
            "de", "ger", "deu", "german" -> listOf("de", "ger", "deu", "german")
            "it", "ita", "italian" -> listOf("it", "ita", "italian")
            "ru", "rus", "russian" -> listOf("ru", "rus", "russian")
            "ar", "ara", "arabic" -> listOf("ar", "ara", "arabic")
            "hi", "hin", "hindi" -> listOf("hi", "hin", "hindi")
            "ja", "jpn", "japanese" -> listOf("ja", "jpn", "japanese", "jp")
            "ko", "kor", "korean" -> listOf("ko", "kor", "korean", "kr")
            "zh", "chi", "zho", "chinese" -> listOf("zh", "chi", "zho", "chinese", "mandarin", "cantonese")
            "pl", "pol", "polish" -> listOf("pl", "pol", "polish", "polski", "napisy")
            "nl", "dut", "dutch" -> listOf("nl", "dut", "dutch", "nederlands")
            "tr", "tur", "turkish" -> listOf("tr", "tur", "turkish", "turkce", "türkçe")
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

    private fun resolveSizeBytes(candidate: StreamCandidate): Long? =
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

    private fun StreamCandidate.searchText(): String =
        listOf(
            title,
            detailLabel.orEmpty(),
            metadataText.orEmpty(),
            filename.orEmpty(),
            streamDescription.orEmpty(),
            bingeGroup.orEmpty(),
        ).joinToString(" ")

    /** Release identity only — used for softsub/dual detection (ignores addon chrome). */
    private fun StreamCandidate.releaseText(): String =
        listOf(
            filename.orEmpty(),
            title,
            detailLabel.orEmpty(),
            streamDescription.orEmpty(),
        ).joinToString(" ")

    private fun StreamCandidate.isWellFormed(): Boolean =
        !directUrl.isNullOrBlank() || !infoHash.isNullOrBlank()

    /**
     * Episode auto-play size policy:
     * - Under soft ceiling (5GB / 12GB UHD): always ok (single-ep sized).
     * - Over soft ceiling: allow only named multi-file season packs (Dual/Multi-Subs/softsub
     *   group + Season/COMPLETE/batch) up to a hard pack ceiling — TorBox file-select can
     *   pull the episode out. Opaque giants (S01E01.mkv at 20GB+) stay blocked.
     */
    private fun StreamCandidate.isSaneAutoPlaySize(context: Context): Boolean {
        if (!isEpisodeLike(context)) return true
        val size = resolveSizeBytes(this) ?: return true
        if (size <= 0L) return true
        val text = releaseText().lowercase()
        val uhd = RESOLUTION_UHD.containsMatchIn(text)
        val softMaxBytes = (if (uhd) 12.0 else 5.0) * 1_073_741_824.0
        if (size <= softMaxBytes) return true
        val packMaxBytes = (if (uhd) 40.0 else 20.0) * 1_073_741_824.0
        if (size > packMaxBytes) return false
        return looksLikeResolvableSeasonPack(text)
    }

    /**
     * True for Dual/Multi-Subs season packs we want auto-play to try (Judas, Anime Time, …).
     * False for single-episode labels with inflated torrent size (MediaFusion hang repro).
     */
    private fun looksLikeResolvableSeasonPack(text: String): Boolean {
        if (!SEASON_PACK_MARKER.containsMatchIn(text)) return false
        return DUAL_AUDIO_REGEX.containsMatchIn(text) ||
            MULTI_SUBS_REGEX.containsMatchIn(text) ||
            ENG_SUB_REGEX.containsMatchIn(text) ||
            isLikelySoftsubRelease(text)
    }

    /**
     * True when the release advertises a non-preferred subtitle language, or is a known
     * Spanish-leaning fansub pack (A&C / AandC) while preferred is not Spanish.
     * Multi-Subs does NOT clear the conflict — A&C Multi-Sub is Latam/España, not Eng.
     */
    private fun hasConflictingSubtitleLanguage(text: String, preferred: String): Boolean {
        if (textMentionsLanguage(text, preferred)) return false
        val preferredBase = preferred.substringBefore('-').substringBefore('_').lowercase()
        if (preferredBase != "es" && looksLikeSpanishFansubPack(text)) return true
        return SUBTITLE_CONFLICT_LANGUAGE_BASES.any { base ->
            base != preferredBase &&
                // Japanese is usually the dual-audio source track, not a subtitle conflict.
                base != "ja" &&
                textMentionsConflictLanguage(text, base)
        }
    }

    /** A&C / AandC packs ship Spanish audio+ASS despite "Multi-Sub" labels. */
    private fun looksLikeSpanishFansubPack(text: String): Boolean =
        SPANISH_FANSUB_GROUP.containsMatchIn(text) ||
            Regex("\\bespecial\\b").containsMatchIn(text)

    /**
     * Conflict detection uses unambiguous language names / codes only — short aliases like
     * "it" / "de" / "hi" / "es" false-positive on ordinary title words ("Make It Right",
     * "Hi Score Girl") and would demote good softsubs to the Spanish-ASS tier.
     */
    private fun textMentionsConflictLanguage(text: String, base: String): Boolean {
        val aliases = when (base) {
            "en" -> listOf("english", "eng")
            "es" -> listOf("spanish", "espanol", "español", "latino", "castellano")
            "pt" -> listOf("portuguese", "portugues", "brazilian", "brasil", "pt-br", "ptbr")
            "fr" -> listOf("french", "vostfr")
            "de" -> listOf("german", "deutsch")
            "it" -> listOf("italian", "italiano")
            "ru" -> listOf("russian", "русский")
            "ar" -> listOf("arabic")
            "hi" -> listOf("hindi")
            "ko" -> listOf("korean")
            "zh" -> listOf("chinese", "mandarin", "cantonese")
            // FrixySubs / Polish WEB-DL packs: "[Napisy PL]", "Polski", "PL Subs"
            "pl" -> listOf("polish", "polski", "napisy", "pl", "pol")
            "nl" -> listOf("dutch", "nederlands", "nl")
            "tr" -> listOf("turkish", "turkce", "türkçe")
            "uk" -> listOf("ukrainian")
            "cs" -> listOf("czech", "cesky", "česky")
            "sv" -> listOf("swedish", "svenska")
            "th" -> listOf("thai")
            "vi" -> listOf("vietnamese", "viet")
            "id" -> listOf("indonesian", "bahasa")
            "ms" -> listOf("malay", "melayu")
            else -> return false
        }
        return aliases.any { alias ->
            Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(text) ||
                Regex("""[\[(]\s*${Regex.escape(alias)}\s*[\])]""").containsMatchIn(text)
        }
    }

    private fun StreamCandidate.isAutoPlayEligible(
        allowUncachedFallback: Boolean = false,
    ): Boolean {
        if (!isWellFormed()) return false
        val hash = infoHash?.trim().orEmpty()
        if (hash.isNotBlank()) {
            return when (cacheState) {
                StreamCacheState.CACHED -> true
                StreamCacheState.NOT_APPLICABLE -> true
                StreamCacheState.NOT_CACHED, StreamCacheState.UNKNOWN -> allowUncachedFallback
                StreamCacheState.CHECKING -> false
            }
        }
        val url = directUrl?.trim().orEmpty()
        if (url.isBlank()) return false
        return !isNonInstantStreamUrl(url)
    }

    private fun StreamCandidate.isInstantOrCachedAutoPlay(): Boolean {
        if (!isWellFormed()) return false
        val hash = infoHash?.trim().orEmpty()
        if (hash.isBlank()) return true
        return when (cacheState) {
            StreamCacheState.CACHED, StreamCacheState.NOT_APPLICABLE -> true
            StreamCacheState.UNKNOWN, StreamCacheState.CHECKING, StreamCacheState.NOT_CACHED -> false
        }
    }

    /** Non-Instant torrent eligible only as post-probe auto-play fallback (force debrid). */
    private fun StreamCandidate.isUncachedDebridAutoPlayEligible(): Boolean {
        val hash = infoHash?.trim().orEmpty()
        if (hash.isBlank()) return !directUrl.isNullOrBlank() && !isNonInstantStreamUrl(directUrl.trim())
        return when (cacheState) {
            StreamCacheState.NOT_CACHED, StreamCacheState.UNKNOWN -> true
            StreamCacheState.CACHED, StreamCacheState.NOT_APPLICABLE, StreamCacheState.CHECKING -> false
        }
    }

    private fun StreamCandidate.isKnownUnsupportedDolbyVision(): Boolean {
        val text = searchText().lowercase()
        return Regex("\\b(?:dolby[ ._-]*vision|dv)\\s*(?:profile\\s*|p\\s*)?8\\.6\\b").containsMatchIn(text)
    }

    private fun StreamCandidate.isExcludedResolution(): Boolean =
        RESOLUTION_720.containsMatchIn(searchText().lowercase())

    private fun StreamCandidate.dedupeKey(): String = when {
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
    private val ENG_SUB_REGEX = Regex("\\b(eng(?:lish)?\\s*subs?|subs?\\s*(?:eng|english))\\b")
    private val DUAL_AUDIO_REGEX = Regex("\\b(dual.?audio|multi.?audio)\\b")
    private val HARD_SUB_REGEX = Regex("\\b(hardsub|hard.?subs?|burned.?in)\\b")
    private val RAW_REGEX = Regex("\\b(raw|no.?subs?|unsubbed)\\b")
    private val SOFTSUB_KEYWORD = Regex("\\b(softsub|soft.?subs?|ass|ssa)\\b")
    /** Styled softsub container — higher softsubFit than SRT/VTT when language matches. */
    private val ASS_SOFTSUB_FORMAT = Regex("\\b(ass|ssa)\\b")
    /** Image-based softsubs (bluray PGS) — still preferred over any wrong-language ASS. */
    private val PGS_SOFTSUB_FORMAT = Regex("\\b(pgs|sup|hdmv[ ._-]?pgs|presentation\\s*graphic)\\b")
    /** Plain softsub dumps — still softsubs, but lose to ASS when language is equal. */
    private val SRT_SOFTSUB_FORMAT = Regex("\\b(srt|subrip|vtt|webvtt)\\b")
    private val SEASON_PACK_MARKER = Regex(
        "\\b(season\\s*\\d+|complete|batch|s\\d{1,2}\\s*\\+\\s*specials|ep(?:isodes?)?\\s*\\d+\\s*[-~]\\s*\\d+)\\b",
    )
    private val SPANISH_FANSUB_GROUP = Regex(
        """[\[(]\s*a\s*&\s*c\s*[\])]|[\[(]\s*aandc\s*[\])]|\baandc\b""",
    )
    private val SUBTITLE_CONFLICT_LANGUAGE_BASES = listOf(
        "en", "es", "pt", "fr", "de", "it", "ru", "ar", "hi", "ko", "zh", "ja",
        // Non-Romance packs that used to score as generic softsub (softsubFit=2) and beat Eng.
        "pl", "nl", "tr", "uk", "cs", "sv", "th", "vi", "id", "ms",
    )
    private val RESOLUTION_UHD = Regex("\\b(2160p|4k|uhd)\\b")
    private val RESOLUTION_1080 = Regex("\\b1080p\\b")
    private val RESOLUTION_720 = Regex("\\b720p\\b")
    private val RESOLUTION_SD = Regex("\\b(480p|sd)\\b")
    private val MODERN_CODEC = Regex("\\b(av1|hevc|h\\.?265|x265)\\b")
    private val HDR_TAG = Regex("\\b(hdr10\\+?|dolby.?vision|dv)\\b")
    private val CAM_TAG = Regex("\\b(cam(?:rip)?|hdcam|telesync|tscam|telecine|screener)\\b|\\[\\s*cam(?:rip)?\\s*]")
    private val AI_UPSCALE = Regex("\\b(ai.?upscale|upscaled|ai.?enhance|reupscale|topaz|esrgan)\\b")
}
