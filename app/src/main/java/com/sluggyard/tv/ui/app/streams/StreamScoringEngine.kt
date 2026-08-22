package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.debrid.StreamTextSizeParser
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.data.local.PreferredAudioLayout

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
         * User audio-layout preference (Stereo / 5.1 / 7.1+). SOFT signal only: a low-rank
         * tiebreak that never gates auto-play or removes candidates. Container truth
         * (observed memory) overrides title hints; unknown = neutral.
         */
        val preferredAudioLayout: PreferredAudioLayout? = null,
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
        /** Audio-layout preference fit — soft tiebreak only: 3 exact, 2 compatible surround, 1 neutral, 0 mismatch. */
        val audioFit: Int = 1,
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
                { it.audioFit },
                { it.sizeFit },
            )
            // K/J drama: subs matter; dual does not.
            ContentKind.K_DRAMA, ContentKind.J_DRAMA -> compareValuesBy(
                this,
                other,
                { it.softsubFit },
                { it.seeders },
                { it.quality },
                { it.audioFit },
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
                { it.audioFit },
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
        val preferred = normalizePreferredSubtitle(context.preferredSubtitleLanguage)
        // Instant with a real softsub/lang signal (fit >= 2). Opaque Instant WEB (fit 1) is
        // not a language winner — titles like "Movie.1080p.WEB-DL" say nothing about dialogue.
        val labeledInstant = if (preferred != null) {
            instantPool.filter { rank(it, context).softsubFit >= 2 }
        } else {
            emptyList()
        }
        val uncachedEligible = {
            safePool.filter { it.isUncachedDebridAutoPlayEligible() }
        }
        val combinedInstantAndUncached = {
            safePool.filter {
                it.isInstantOrCachedAutoPlay() || it.isUncachedDebridAutoPlayEligible()
            }
        }
        // Mainstream: Instant hard gate only when Instant rows actually look playable for
        // the preferred language. Otherwise Finding used to auto-start the first TorBox
        // cached WEB (unlabeled audio, no subs) and never score an Eng-tagged uncached row.
        val cachePool = when {
            kind == ContentKind.ANIME && allowUncachedFallback ->
                combinedInstantAndUncached().ifEmpty { emptyList() }
            preferred != null && labeledInstant.isNotEmpty() -> labeledInstant
            preferred != null && instantPool.isNotEmpty() && allowUncachedFallback ->
                combinedInstantAndUncached().ifEmpty { emptyList() }
            instantPool.isNotEmpty() -> instantPool
            allowUncachedFallback -> uncachedEligible()
            else -> emptyList()
        }
        // Size / language / digital gates apply to the Instant pool first. If Instant rows all
        // fail those gates and uncached fallback is open, re-run the same gates on
        // debrid-startable rows instead of returning null while better scrapers may still arrive.
        var playPool = gatedPlayPool(cachePool, context, kind)
        if (playPool.isEmpty() && allowUncachedFallback) {
            val uncachedPool = uncachedEligible()
            if (uncachedPool.isNotEmpty()) {
                playPool = gatedPlayPool(uncachedPool, context, kind)
            }
        }
        val winner = playPool.maxWithOrNull(rankComparator(context)) ?: return null
        val winnerRank = rank(winner, context)
        // During Finding (Instant-only), refuse weak anime Instant traps so Dual/ASS can still land:
        // mono PGS remuxes and plain SRT WEB dumps (Angel Next Door Instant English SRT).
        // softsubFit==4 alone is not the bar — that would also block Instant ASS when preferred=null.
        if (
            kind == ContentKind.ANIME &&
            !allowUncachedFallback &&
            !isAcceptableAnimeInstantAutoStart(
                winner,
                winnerRank,
                context.preferredSubtitleLanguage,
            )
        ) {
            return null
        }
        // Opaque Instant (softsubFit 1: no softsub / language token) must not auto-start
        // while scrapers are still running. Conflicting tagged Instant (fit 0) is a different
        // path — exclusive packs already empty the pool; origin-tagged Multi-Subs still play.
        // MKV header probe rejects the ceiling case where the file has zero subtitle tracks.
        if (
            kind != ContentKind.ANIME &&
            preferred != null &&
            !allowUncachedFallback &&
            winnerRank.softsubFit == 1
        ) {
            return null
        }
        return winner
    }

    /**
     * Instant-only Finding may start dual softpacks and ASS (or known fansub groups) immediately.
     * Wait on **PGS mono**, **plain SRT mono**, and **wrong-language Instant soft** (VOSTFR when
     * preferred=en) so Eng Dual/ASS Instant can still land.
     */
    private fun isAcceptableAnimeInstantAutoStart(
        candidate: StreamCandidate,
        rank: Rank,
        preferredSubtitleLanguage: String?,
    ): Boolean {
        // Dual+soft already clears fansub packs; do not wait extra for those Instant hits.
        if (rank.effectiveDualFit >= 1) return true
        val preferred = normalizePreferredSubtitle(preferredSubtitleLanguage)
        // Conflicting localization Instant (softsubFit 0) must not auto-start while preferred
        // is set — Angel Next Door locked on Tsundere VOSTFR French ASS over Eng Multi-Subs.
        // Dual-only Instant (soft=1 with Dual-Audio) still starts so Kametsu-type CACHED duals
        // keep beating UNKNOWN soft rows in Instant-only Finding.
        if (preferred != null && rank.softsubFit == 0) return false
        val text = candidate.releaseText().lowercase()
        // Prefer known ASS fansub groups (SubsPlease etc.) even when the title never says ASS.
        if (isKnownAnimeSoftsubGroup(text)) return true
        val ass = ASS_SOFTSUB_FORMAT.containsMatchIn(text)
        val pgs = PGS_SOFTSUB_FORMAT.containsMatchIn(text)
        val srt = SRT_SOFTSUB_FORMAT.containsMatchIn(text)
        // Demon Slayer Instant Eng-PGS mono trap.
        if (pgs && rank.dualFit == 0) return false
        // Plain SRT/VTT mono Instant — hold for ASS dual/fansub while scrapers dump.
        if (srt && !ass && rank.dualFit == 0) return false
        if (rank.softsubFit >= 5) return true
        return !srt
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
            audioFit = audioFitOf(candidate, context),
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
                    r.quality * 10 + r.audioFit * 4 + r.sizeFit
            ContentKind.K_DRAMA, ContentKind.J_DRAMA ->
                r.softsubFit * 1_000_000 + r.seeders * 1_000 + r.quality * 10 +
                    r.audioFit * 4 + r.sizeFit
            ContentKind.MAINSTREAM, ContentKind.LIVE ->
                r.softsubFit * 1_000_000 + r.seeders * 1_000 + r.quality * 10 +
                    r.audioFit * 4 + r.sizeFit
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

    /** True when settings asked for a real subtitle language (not empty / "none"). */
    fun hasPreferredLanguage(preferredSubtitleLanguage: String?): Boolean =
        normalizePreferredSubtitle(preferredSubtitleLanguage) != null

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
     * When [ObservedReleaseTracks] exist with soft inventory, softsubFit is replaced by observed
     * (file/player truth). Title Multi-Subs must not stick above pure-SRT container truth.
     * [ListingReleaseProbe] lifts structured MediaInfo / file-path signals from free listing text
     * (no network) under max with title — never overrides a clearer title conflict (score 0).
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
        // Free listing parse (structured Audio:/Subs: / companion .ass paths) — no I/O.
        val listingAnimeLike = kind == ContentKind.ANIME ||
            kind == ContentKind.MAINSTREAM ||
            kind == ContentKind.LIVE
        val (listSoft, listDual) = ListingReleaseProbe.inferTrackFits(
            releaseBlob = text,
            preferredSubtitleLanguage = context.preferredSubtitleLanguage,
            animeLike = listingAnimeLike,
        )
        // Never lift out of hard conflict (0): wrong-lang title stays out of auto-play.
        if (softsubFit > 0 && listSoft > softsubFit) softsubFit = listSoft
        // Never invent dual on K/J drama even if listing text looks dual-ish.
        if (listingAnimeLike && listDual > dualFit) dualFit = listDual

        val hash = candidate?.infoHash?.trim()?.lowercase().orEmpty()
        val observed = if (hash.isNotEmpty()) context.observedTracksByHash[hash] else null
        // Container / player inventory is truth: replace softsubFit (do not only max-lift).
        // Title "Multi-Subs" at 2–3 must not stay sticky when file is pure SRT.
        if (observed != null && observed.hasSoftsubTrack) {
            val (obsSoft, obsDual) = observed.toTitleScaleSignals(
                preferredSubtitleLanguage = context.preferredSubtitleLanguage,
                animeLike = listingAnimeLike,
            )
            // Never invent soft over a hard title conflict (wrong-lang score 0).
            if (softsubFit > 0) {
                softsubFit = obsSoft
                usedObserved = true
            }
            if (listingAnimeLike && obsDual > dualFit) {
                dualFit = obsDual
                usedObserved = true
            }
        } else if (observed != null) {
            // Dual-only / incomplete memory still lifts dual floor when higher.
            val (obsSoft, obsDual) = observed.toTitleScaleSignals(
                preferredSubtitleLanguage = context.preferredSubtitleLanguage,
                animeLike = listingAnimeLike,
            )
            if (obsSoft > softsubFit && softsubFit > 0) {
                softsubFit = obsSoft
                usedObserved = true
            }
            if (listingAnimeLike && obsDual > dualFit) {
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

    /**
     * Audio-layout preference fit — SOFT signal only. Never gates (no filter in [choose]);
     * it breaks rank ties below quality. Container truth (observed memory) wins when present;
     * otherwise best-effort release-title hints; unknown = neutral 1.
     */
    private fun audioFitOf(candidate: StreamCandidate, context: Context): Int {
        val preferred = context.preferredAudioLayout ?: return 1
        val hash = candidate.infoHash?.trim()?.lowercase().orEmpty()
        val observed = if (hash.isNotEmpty()) context.observedTracksByHash[hash] else null
        val truthChannels = observed?.maxAudioChannels
        if (truthChannels != null) {
            return layoutFit(preferred, truthChannels)
        }
        val titleChannels = titleLayoutChannels(candidate.releaseText().lowercase())
        return if (titleChannels != null) layoutFit(preferred, titleChannels) else 1
    }

    /**
     * Channel count → layout match: 2 → stereo, 6 → 5.1, 8+ → 7.1-class. Mono is an explicit
     * mismatch for every preference; surround families (5.1 ↔ 7.1) are compatible; stereo vs
     * any surround is a mismatch.
     */
    private fun layoutFit(preferred: PreferredAudioLayout, channels: Int): Int {
        if (channels <= 1) return 0
        val layout = when {
            channels == 2 -> PreferredAudioLayout.STEREO
            channels <= 6 -> PreferredAudioLayout.SURROUND_5_1
            else -> PreferredAudioLayout.SURROUND_7_1
        }
        return when {
            layout == preferred -> 3
            preferred != PreferredAudioLayout.STEREO && layout != PreferredAudioLayout.STEREO -> 2
            else -> 0
        }
    }

    /**
     * Channel-layout hints from the release name. Lossless/object tiers (Atmos, TrueHD,
     * DTS-HD MA, DTS:X) are 7.1-class; explicit 5.1/7.1 tokens; stereo/2.0. Null when the
     * title says nothing (neutral). Callers pass lowercased text.
     */
    private fun titleLayoutChannels(text: String): Int? = when {
        AUDIO_ATMOS_LOSSLESS_REGEX.containsMatchIn(text) -> 8
        AUDIO_7_1_REGEX.containsMatchIn(text) -> 8
        AUDIO_5_1_REGEX.containsMatchIn(text) -> 6
        AUDIO_STEREO_REGEX.containsMatchIn(text) -> 2
        else -> null
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
     * Soft language signal for ranking only (see [trackSignals] softsubFit).
     * Preferred-language hits clear the flag; non-preferred names / known Spanish fansub
     * groups demote rank so preferred softsubs win when both Instant rows exist.
     */
    private fun hasConflictingSubtitleLanguage(text: String, preferred: String): Boolean {
        if (textMentionsLanguage(text, preferred)) return false
        val preferredBase = preferred.substringBefore('-').substringBefore('_').lowercase()
        if (preferredBase != "es" && looksLikeSpanishFansubPack(text)) return true
        return SUBTITLE_CONFLICT_LANGUAGE_BASES.any { base ->
            base != preferredBase && textMentionsConflictLanguage(text, base)
        }
    }

    /**
     * Hard veto for packs that are *exclusively* a non-preferred localization (Spanish ASS,
     * Napisy PL, A&C Latam Multi-Sub). Multi-audio / multi-sub / dual releases and bare
     * origin/dialogue language tags are **not** exclusive — they stay Instant-eligible
     * and ranking sorts them. No content-kind or language-family allowlists.
     */
    private fun isExclusiveNonPreferredLocalizationPack(
        text: String,
        preferred: String,
    ): Boolean {
        if (textMentionsLanguage(text, preferred)) return false
        val preferredBase = preferred.substringBefore('-').substringBefore('_').lowercase()
        // French soft WEB packs almost never put "sub" in the title — VOSTFR *is* the pack signal.
        if (preferredBase != "fr" && Regex("""\bvostfr\b""").containsMatchIn(text)) return true
        if (preferredBase != "es" && looksLikeSpanishFansubPack(text)) return true
        // Multi-language packaging is not an exclusive foreign pack.
        if (MULTI_SUBS_REGEX.containsMatchIn(text) || DUAL_AUDIO_REGEX.containsMatchIn(text)) {
            return false
        }
        if (!hasConflictingSubtitleLanguage(text, preferred)) return false
        // Conflicting *and* localization-pack framing (softsub/ASS/napisy/etc), not a bare
        // dialogue/origin language token on an Instant WEB-DL.
        return hasLocalizationPackSignal(text)
    }

    private fun hasLocalizationPackSignal(text: String): Boolean =
        ASS_SOFTSUB_FORMAT.containsMatchIn(text) ||
            PGS_SOFTSUB_FORMAT.containsMatchIn(text) ||
            SRT_SOFTSUB_FORMAT.containsMatchIn(text) ||
            SOFTSUB_KEYWORD.containsMatchIn(text) ||
            EXPLICIT_SUB_REGEX.containsMatchIn(text) ||
            HARD_SUB_REGEX.containsMatchIn(text) ||
            Regex("""\bnapisy\b""").containsMatchIn(text) ||
            Regex("""\bvostfr\b""").containsMatchIn(text)

    /**
     * Size / exclusive-localization / digital-release gates, then preferred-language preference.
     * Language preference never hard-empties Instant when only origin-tagged rows exist.
     */
    private fun gatedPlayPool(
        pool: List<StreamCandidate>,
        context: Context,
        @Suppress("UNUSED_PARAMETER") kind: ContentKind,
    ): List<StreamCandidate> {
        // Size gate: reject opaque huge torrents that hang TorBox/MPV, but allow named
        // multi-file season packs so episode file-select can pull SxxExx out of the pack.
        val sizedPool = pool.filter { it.isSaneAutoPlaySize(context) }
        val preferred = normalizePreferredSubtitle(context.preferredSubtitleLanguage)
        val afterLanguage = if (preferred == null) {
            sizedPool
        } else {
            val notExclusive = sizedPool.filter { candidate ->
                !isExclusiveNonPreferredLocalizationPack(
                    candidate.releaseText().lowercase(),
                    preferred,
                )
            }
            // All Instant rows are exclusive foreign packs (e.g. Spanish ASS only) → empty,
            // wait for scrapers / uncached. Mixed / multi-sub / bare tags stay.
            if (notExclusive.isEmpty()) {
                emptyList()
            } else {
                // Prefer non-conflicting packs; never require the preferred *word* in the title
                // (dual/multi softpacks often omit it). Ranking uses softsubFit for preferred fit.
                // Fail-open to notExclusive when every row is only mildly conflicting (origin tags).
                val nonConflicting = notExclusive.filter { candidate ->
                    !hasConflictingSubtitleLanguage(candidate.releaseText().lowercase(), preferred)
                }
                nonConflicting.ifEmpty { notExclusive }
            }
        }
        // Pre-digital movies: refuse all auto-play until TMDB shows a past digital release.
        return afterLanguage.filter { candidate ->
            DigitalReleasePolicy.allowsAutoPlay(
                context.digitalReleaseStatus,
                candidate.releaseText(),
            )
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
            "it" -> listOf("italian", "italiano", "ita")
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

    private val MULTI_SUBS_REGEX = Regex("\\b(multi.?subs?|m.?subs?|msubs|dual.?subs?|multiple.?subtitle)\\b")
    private val EXPLICIT_SUB_REGEX = Regex("\\b(sub|subs|subtitle|subtitles|cc)\\b")
    private val ENG_SUB_REGEX = Regex("\\b(eng(?:lish)?\\s*subs?|subs?\\s*(?:eng|english))\\b")
    private val DUAL_AUDIO_REGEX = Regex("\\b(dual.?audio|multi.?audio)\\b")
    /** Lossless/object audio tiers — 7.1-class regardless of base channel count. */
    private val AUDIO_ATMOS_LOSSLESS_REGEX = Regex(
        "\\b(atmos|truehd|dts[\\s._-]?x|dts[\\s._-]?hd[\\s._-]?ma|" +
            "dolby[\\s._-]?digital[\\s._-]?plus[\\s._-]?atmos)\\b",
    )
    /** Explicit 7.1-class tokens (incl. 7.1.4 / 7.1.2 height layouts). */
    private val AUDIO_7_1_REGEX = Regex("\\b(7\\.1(?:\\.\\d+)?|7ch|dd7\\.1)\\b")
    /** Explicit 5.1 tokens. */
    private val AUDIO_5_1_REGEX = Regex("\\b(5\\.1(?:\\.\\d+)?|5ch|dd5\\.1|dts[\\s._-]?5\\.1)\\b")
    /** Stereo tokens. */
    private val AUDIO_STEREO_REGEX = Regex("\\b(2\\.0|stereo|2ch)\\b")
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
