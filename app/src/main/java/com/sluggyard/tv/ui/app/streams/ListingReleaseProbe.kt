package com.sluggyard.tv.ui.app.streams

/**
 * Lightweight **pre-play** "beyond title" — pure string scan of free listing fields, no network,
 * no debrid open, no MKV parse. Complements [ObservedReleaseTracks] (post-play memory).
 *
 * Targets structured addon blurb (MediaInfo-style, AIO/MediaFusion) and file-path inventories
 * that often list companion `.ass` / Dual Audio folder names even when the short title is bare.
 *
 * Output uses the same softsubFit / dualFit scale as title heuristics so rank can take max().
 */
internal object ListingReleaseProbe {
    /** Cap free-text scan so huge addon dumps cannot freeze rank on leanback. */
    private const val MaxBlobChars = 4_000
    // Prefer capture after labels — stronger than loose keywords in the whole blob.
    private val AUDIO_SECTION = Regex(
        """(?is)(?:audio(?:\s*(?:tracks?|languages?))?|音軌|音轨)\s*[:：]\s*([^\n\r|]+(?:\|[^\n\r]+)*)""",
    )
    private val SUB_SECTION = Regex(
        """(?is)(?:sub(?:title)?s?(?:\s*(?:tracks?|languages?))?|字幕)\s*[:：]\s*([^\n\r|]+(?:\|[^\n\r]+)*)""",
    )
    private val DUAL_AUDIO = Regex("""\b(dual[ ._-]?audio|multi[ ._-]?audio|2[ ._-]?audio|dualaudio)\b""", RegexOption.IGNORE_CASE)
    private val ASS = Regex("""\b(ass|ssa)\b|\.ass\b|\.ssa\b""", RegexOption.IGNORE_CASE)
    private val PGS = Regex("""\b(pgs|sup|hdmv[ ._-]?pgs)\b|\.sup\b""", RegexOption.IGNORE_CASE)
    private val SRT = Regex("""\b(srt|subrip|vtt|webvtt)\b|\.srt\b|\.vtt\b""", RegexOption.IGNORE_CASE)
    private val SOFT = Regex("""\b(soft[ ._-]?subs?|softsubs?)\b""", RegexOption.IGNORE_CASE)
    private val MULTI_SUB = Regex("""\b(multi[ ._-]?subs?|multiple[ ._-]?subtitle)\b""", RegexOption.IGNORE_CASE)
    private val RAW = Regex("""\b(raw|no[ ._-]?subs?|unsubbed)\b""", RegexOption.IGNORE_CASE)
    private val HARD = Regex("""\b(hardsub|hard[ ._-]?subs?|burned[ ._-]?in)\b""", RegexOption.IGNORE_CASE)

    // Companion subtitle file paths in pack inventories: "Episode.eng.ass", "/Subs/English.ass"
    private val SIDE_CAR_ASS = Regex(
        """(?i)(?:^|[\\/_\s.\[(])(?:eng(?:lish)?|en|jpn?|ja|japanese|spa|es|por|pt|multi)[^\\/\n]{0,40}\.(?:ass|ssa)\b""",
    )
    private val SIDE_CAR_PGS = Regex(
        """(?i)(?:^|[\\/_\s.\[(])(?:eng(?:lish)?|en)[^\\/\n]{0,40}\.(?:sup|pgs)\b""",
    )
    private val SIDE_CAR_SRT = Regex(
        """(?i)(?:^|[\\/_\s.\[(])(?:eng(?:lish)?|en)[^\\/\n]{0,40}\.(?:srt|vtt)\b""",
    )

    private val LANG_ENG = Regex("""\b(eng(?:lish)?|en)\b""", RegexOption.IGNORE_CASE)
    private val LANG_JPN = Regex("""\b(jpn?|japanese|jp)\b""", RegexOption.IGNORE_CASE)
    private val LANG_SPA = Regex("""\b(spa(?:nish)?|es|castellano|latino)\b""", RegexOption.IGNORE_CASE)
    private val LANG_POR = Regex("""\b(por(?:tuguese)?|pt(?:-?br)?)\b""", RegexOption.IGNORE_CASE)

    /**
     * @return softsubFit (0–5) and dualFit (0–1), same scale as title heuristics.
     */
    fun inferTrackFits(
        releaseBlob: String,
        preferredSubtitleLanguage: String?,
        animeLike: Boolean,
    ): Pair<Int, Int> {
        val text = releaseBlob.trim().take(MaxBlobChars)
        if (text.isEmpty()) return 1 to 0

        val audioSec = AUDIO_SECTION.find(text)?.groupValues?.getOrNull(1).orEmpty()
        val subSec = SUB_SECTION.find(text)?.groupValues?.getOrNull(1).orEmpty()
        val blob = text

        val dual = when {
            !animeLike -> false
            DUAL_AUDIO.containsMatchIn(blob) -> true
            audioSec.isNotEmpty() && sectionHasTwoDistinctAudios(audioSec) -> true
            // "Japanese + English audio" floating in description
            LANG_JPN.containsMatchIn(blob) && LANG_ENG.containsMatchIn(blob) &&
                Regex("""\b(dual[ ._-]?audio|multi[ ._-]?audio|2[ ._-]?audio)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(blob) -> true
            else -> false
        }

        if (RAW.containsMatchIn(blob) && !ASS.containsMatchIn(blob) && !SOFT.containsMatchIn(blob)) {
            return 0 to if (dual) 1 else 0
        }

        val hasAss = ASS.containsMatchIn(blob) || ASS.containsMatchIn(subSec) || SIDE_CAR_ASS.containsMatchIn(blob)
        val hasPgs = PGS.containsMatchIn(blob) || PGS.containsMatchIn(subSec) || SIDE_CAR_PGS.containsMatchIn(blob)
        val hasSrt = SRT.containsMatchIn(blob) || SRT.containsMatchIn(subSec) || SIDE_CAR_SRT.containsMatchIn(blob)
        val multi = MULTI_SUB.containsMatchIn(blob)
        val softKw = SOFT.containsMatchIn(blob)
        val hardOnly = HARD.containsMatchIn(blob) && !hasAss && !hasPgs && !hasSrt && !softKw
        if (hardOnly) return 0 to if (dual) 1 else 0

        val softPresent = hasAss || hasPgs || hasSrt || softKw || multi || subSec.isNotEmpty()
        if (!softPresent) return 1 to if (dual) 1 else 0

        val preferred = preferredSubtitleLanguage?.trim()?.lowercase()?.takeIf {
            it.isNotEmpty() && it != "none"
        }
        val preferredBase = preferred?.substringBefore('-')?.substringBefore('_')
        val preferredHit = preferredBase != null && (
            textMentionsLanguage(subSec.ifEmpty { blob }, preferredBase) ||
                (preferredBase.startsWith("en") && (LANG_ENG.containsMatchIn(subSec) || SIDE_CAR_ASS.containsMatchIn(blob)))
            )

        val softFit = when {
            preferred == null && hasAss -> 4
            preferred == null && hasPgs -> 3
            preferred == null -> 3
            preferredHit && hasAss -> 5
            preferredHit && hasPgs -> 4
            preferredHit -> 3
            // Soft present, preferred unknown / other-lang — floor 2 so dual still competes
            hasAss || softPresent -> 2
            else -> 1
        }
        return softFit to if (dual) 1 else 0
    }

    private fun sectionHasTwoDistinctAudios(section: String): Boolean {
        var hits = 0
        if (LANG_JPN.containsMatchIn(section)) hits++
        if (LANG_ENG.containsMatchIn(section)) hits++
        if (LANG_SPA.containsMatchIn(section)) hits++
        if (LANG_POR.containsMatchIn(section)) hits++
        // Slash / plus lists: "Japanese / English"
        if (hits >= 2) return true
        val slashLangs = Regex("""[a-zA-Z]{2,}(?:\s*/\s*[a-zA-Z]{2,})+""")
            .findAll(section)
            .count()
        return slashLangs >= 1 && section.count { it == '/' || it == '+' || it == ',' || it == '|' } >= 1 &&
            (LANG_JPN.containsMatchIn(section) || LANG_ENG.containsMatchIn(section))
    }

    private fun textMentionsLanguage(text: String, base: String): Boolean {
        val b = base.lowercase()
        val aliases = when {
            b.startsWith("en") -> listOf("en", "eng", "english")
            b.startsWith("ja") || b == "jp" -> listOf("ja", "jpn", "japanese", "jp")
            b.startsWith("es") || b.startsWith("sp") -> listOf("es", "spa", "spanish", "latino")
            b.startsWith("pt") -> listOf("pt", "por", "portuguese")
            else -> listOf(b)
        }
        return aliases.any { alias ->
            Regex("""\b${Regex.escape(alias)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    }
}
