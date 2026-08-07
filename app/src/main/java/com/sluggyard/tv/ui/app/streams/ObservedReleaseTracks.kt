package com.sluggyard.tv.ui.app.streams

/**
 * Track inventory learned after MPV/Exo actually opens a release — deeper than title tags,
 * free on later auto-picks for the same infoHash (season pack memory applies across episodes).
 *
 * Never written from release titles. Only from real player track lists once they settle.
 */
data class ObservedReleaseTracks(
    /** Normalized language bases (eng, jpn, spa, …). Empty when unknown. */
    val audioLangBases: Set<String> = emptySet(),
    val subtitleLangBases: Set<String> = emptySet(),
    val hasAss: Boolean = false,
    val hasPgs: Boolean = false,
    val hasSrt: Boolean = false,
    /** Any non-forced, non-sign-only text track. */
    val hasSoftsubTrack: Boolean = false,
    val observedAtMs: Long = 0L,
) {
    val dualAudio: Boolean
        get() = audioLangBases.size >= 2 ||
            (audioLangBases.contains("eng") && audioLangBases.contains("jpn")) ||
            (audioLangBases.contains("en") && audioLangBases.contains("ja"))

    /**
     * Maps inventory into the same softsubFit / dualFit scale as title heuristics so rank can
     * take the max of text vs observed without a second rank axis.
     *
     * @param animeLike when true, dual audio is scored; K/J-drama callers pass false.
     */
    fun toTitleScaleSignals(
        preferredSubtitleLanguage: String?,
        animeLike: Boolean,
    ): Pair</* softsubFit */ Int, /* dualFit */ Int> {
        val preferred = preferredSubtitleLanguage?.trim()?.lowercase()?.takeIf {
            it.isNotEmpty() && it != "none"
        }
        val preferredBase = preferred?.substringBefore('-')?.substringBefore('_')
        val preferredHit = preferredBase != null && subtitleLangBases.any { base ->
            languageBasesMatch(base, preferredBase)
        }
        val hasSoft = hasSoftsubTrack || hasAss || hasPgs || hasSrt
        val softsubFit = when {
            !hasSoft -> 1
            preferred == null && hasAss -> 4
            preferred == null && hasPgs -> 3
            preferred == null -> 3
            preferredHit && hasAss -> 5
            preferredHit && hasPgs -> 4
            preferredHit -> 3
            // Softsubs exist but not in preferred language — still real soft (never 0: we opened).
            hasAss || hasSoft -> 2
            else -> 1
        }
        val dualFit = if (animeLike && dualAudio) 1 else 0
        return softsubFit to dualFit
    }

    companion object {
        fun languageBasesMatch(a: String, b: String): Boolean {
            val x = normalizeLangBase(a)
            val y = normalizeLangBase(b)
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            // eng/en, jpn/ja, etc.
            val groups = listOf(
                setOf("en", "eng", "english"),
                setOf("ja", "jpn", "japanese", "jp"),
                setOf("es", "spa", "spanish"),
                setOf("pt", "por", "portuguese"),
                setOf("zh", "chi", "zho", "chinese"),
                setOf("ko", "kor", "korean"),
            )
            return groups.any { x in it && y in it }
        }

        fun normalizeLangBase(raw: String?): String {
            val t = raw?.trim()?.lowercase().orEmpty()
            if (t.isEmpty() || t == "und" || t == "unknown") return ""
            return when (val base = t.substringBefore('-').substringBefore('_')) {
                "en", "eng", "english" -> "eng"
                "ja", "jpn", "jp", "japanese" -> "jpn"
                "es", "spa", "spanish" -> "spa"
                "pt", "por", "portuguese" -> "por"
                "fr", "fre", "fra", "french" -> "fra"
                "de", "ger", "deu", "german" -> "deu"
                "ko", "kor", "korean" -> "kor"
                "zh", "chi", "zho", "chinese" -> "zho"
                else -> base.take(8)
            }
        }
    }
}

/**
 * Build observation from player track lists. Ignores forced / signs-only subs for softsub
 * detection; counts all distinct audio languages for dual.
 */
internal fun observedReleaseTracksFromPlayer(
    audioLanguages: List<String?>,
    subtitleTracks: List<PlayerSubtitleObservation>,
    observedAtMs: Long = System.currentTimeMillis(),
): ObservedReleaseTracks {
    val audioBases = audioLanguages
        .map { ObservedReleaseTracks.normalizeLangBase(it) }
        .filter { it.isNotEmpty() }
        .toSet()
    var hasAss = false
    var hasPgs = false
    var hasSrt = false
    var hasSoft = false
    val subBases = mutableSetOf<String>()
    for (sub in subtitleTracks) {
        if (sub.isForced || sub.isSignsAndSongs) continue
        hasSoft = true
        ObservedReleaseTracks.normalizeLangBase(sub.language).takeIf { it.isNotEmpty() }?.let {
            subBases += it
        }
        when {
            sub.looksAss -> hasAss = true
            sub.looksPgs -> hasPgs = true
            sub.looksSrt -> hasSrt = true
        }
    }
    return ObservedReleaseTracks(
        audioLangBases = audioBases,
        subtitleLangBases = subBases,
        hasAss = hasAss,
        hasPgs = hasPgs,
        hasSrt = hasSrt,
        hasSoftsubTrack = hasSoft,
        observedAtMs = observedAtMs,
    )
}

/** Minimal player-facing face so streams package need not depend on TrackInfo UI types. */
internal data class PlayerSubtitleObservation(
    val language: String?,
    val isForced: Boolean = false,
    val isSignsAndSongs: Boolean = false,
    val looksAss: Boolean = false,
    val looksPgs: Boolean = false,
    val looksSrt: Boolean = false,
)

/** True when this source is SeaDex / curated (AIOStreams seadex builtin, sources.seadex, …). */
internal fun StreamCandidate.isCuratedSeaDexSource(): Boolean {
    val blob = listOf(sourceLabel, bingeGroup.orEmpty(), id, title)
        .joinToString(" ")
        .lowercase()
    return blob.contains("seadex") || blob.contains("sea-dex") || blob.contains("releases.moe")
}

/**
 * When Instant rank is ambiguous (track-fit differs but both Instant) and neither has memory,
 * auto-pick should not open HTTP headers — Finding holds for dual/ASS or uncached fallthrough.
 * Kept for unit coverage of the wait policy (choose() implements the PGS mono bar directly).
 */
internal fun animeInstantTrackAmbiguityShouldWait(
    top: StreamScoringEngine.Rank,
    runnerUp: StreamScoringEngine.Rank?,
    topHasMemory: Boolean,
    runnerUpHasMemory: Boolean,
): Boolean {
    if (runnerUp == null) return false
    if (topHasMemory || runnerUpHasMemory) return false
    if (top.kind != StreamScoringEngine.ContentKind.ANIME) return false
    val dualDelta = kotlin.math.abs(top.effectiveDualFit - runnerUp.effectiveDualFit)
    val softDelta = kotlin.math.abs(top.softsubFit - runnerUp.softsubFit)
    return dualDelta > 0 || softDelta >= 2
}
