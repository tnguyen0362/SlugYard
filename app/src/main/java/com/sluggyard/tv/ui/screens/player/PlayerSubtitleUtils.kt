package com.sluggyard.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.sluggyard.tv.ui.util.LANGUAGE_OVERRIDES

/**
 * Subtitle language normalization and regional-variant detection.
 *
 * The goal is to preserve accent distinctions that matter to users (Brazilian
 * vs European Portuguese, Latin-American vs Castilian Spanish) while still
 * matching against the loose tags embedded in many container formats.
 */
internal object PlayerSubtitleUtils {

    fun normalizeLanguageCode(lang: String): String {
        val code = lang.trim().lowercase()
        if (code.isBlank()) return ""

        val hyphenated = code.replace('_', '-')
        val tokenized = hyphenated
            .replace('-', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        fun containsAny(vararg needles: String): Boolean = needles.any { tokenized.contains(it) }

        if (containsAny("portuguese", "portugues")) {
            return when {
                containsAny("brazil", "brasil", "brazilian", "brasileiro", "pt br", "ptbr", "pob", "(br)") -> "pt-br"
                containsAny("portugal", "european", "europeu", "iberian", "pt pt", "ptpt") -> "pt"
                else -> "pt"
            }
        }

        if (containsAny("spanish", "espanol", "español", "castellano")) {
            return when {
                containsAny("latin", "latino", "latinoamerica", "latinoamericano", "lat am", "latam", "es 419", "es419", "la", "(419)") -> "es-419"
                else -> "es"
            }
        }

        // LANGUAGE_OVERRIDES uses mixed-case keys (e.g. pt-BR); lowercase for consistency.
        return LANGUAGE_OVERRIDES[code]?.lowercase() ?: hyphenated
    }

    fun matchesLanguageCode(language: String?, target: String): Boolean {
        if (language.isNullOrBlank()) return false
        val normalizedLanguage = normalizeLanguageCode(language)
        val normalizedTarget = normalizeLanguageCode(target)
        if (matchesNormalized(normalizedLanguage, normalizedTarget)) return true

        val subtags = language.trim().lowercase()
            .replace('_', '-')
            .split('-', '.', '/', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (subtags.size <= 1) return false

        // Walk the subtags past the primary one to honor ISO 639-3 codes (e.g. "por").
        for (subtag in subtags.drop(1)) {
            if (subtag.length != 3) continue
            if (matchesNormalized(normalizeLanguageCode(subtag), normalizedTarget)) return true
        }
        return false
    }

    private fun matchesNormalized(normalizedLanguage: String, normalizedTarget: String): Boolean {
        // Exact regional targets: "pt" must not match "pt-br", "es" must not match "es-419".
        if (normalizedTarget == "pt") return normalizedLanguage == "pt"
        if (normalizedTarget == "es") return normalizedLanguage == "es"
        return normalizedLanguage == normalizedTarget ||
            normalizedLanguage.startsWith("$normalizedTarget-") ||
            normalizedLanguage.startsWith("${normalizedTarget}_")
    }

    /**
     * Detects the regional variant of an embedded subtitle track by inspecting
     * its name, language, and trackId fields. Returns a normalized language key
     * that preserves the accent (e.g. "pt-br", "es-419") when detectable, or
     * falls back to the base language code.
     */
    fun detectTrackLanguageVariant(language: String?, name: String?, trackId: String?): String {
        val base = normalizeLanguageCode(language ?: "")
        val haystack = listOfNotNull(name, language, trackId).joinToString(" ").lowercase()

        if (base == "pt" || base == "por") {
            val brazilian = BRAZILIAN_TAGS.any(haystack::contains)
            val european = EUROPEAN_PT_TAGS.any(haystack::contains)
            return when {
                brazilian && !european -> "pt-br"
                european && !brazilian -> "pt"
                else -> base
            }
        }

        if (base == "es" || base == "spa") {
            val latino = LATINO_TAGS.any(haystack::contains)
            val castilian = CASTILIAN_TAGS.any(haystack::contains)
            return when {
                latino && !castilian -> "es-419"
                castilian && !latino -> "es"
                else -> base
            }
        }

        return base
    }

    internal val BRAZILIAN_TAGS = listOf(
        "pt-br", "pt_br", "pob", "brazilian", "brazil", "brasil", "brasileiro", " br", "(br)"
    )
    internal val EUROPEAN_PT_TAGS = listOf(
        "pt-pt", "pt_pt", "iberian", "european", "portugal", "europeu", " eu", "(eu)"
    )
    internal val LATINO_TAGS = listOf(
        "es-419", "es_419", "es-la", "es-lat", "latino", "latinoamerica",
        "latinoamericano", "latam", "lat am", "latin america"
    )
    internal val CASTILIAN_TAGS = listOf(
        "es-es", "es_es", "castilian", "castellano", "spain", "españa", "espana", "iberian"
    )

    fun mimeTypeFromUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
        return when {
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            path.endsWith(".sup") || path.endsWith(".pgs") -> "application/pgs"
            path.endsWith(".idx") || path.endsWith(".sub") -> "application/vobsub"
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /**
     * Heuristic signs-and-songs / signs & songs detector. A signs-and-songs track translates
     * on-screen text and song lyrics for a viewer who understands the audio language; it is
     * not a full dialogue subtitle. Recognized name/label/id fragments include the common
     * "signs and songs", "songs & signs", "signs/songs" forms plus the shorter "signs" /
     * "songs" markers that anime groups use when paired together.
     */
    fun isSignsAndSongsTrack(texts: List<String?>): Boolean {
        val haystack = texts.filterNotNull().joinToString(" ").lowercase()
        if (haystack.isBlank()) return false
        if (haystack.contains("sign") && haystack.contains("song")) return true
        if (haystack.contains("signs & songs") || haystack.contains("signs and songs")) return true
        if (haystack.contains("signs/songs") || haystack.contains("songs/signs")) return true
        // "Signs only" / "Songs only" variants used by some groups.
        if (haystack.contains("signs only") || haystack.contains("songs only")) return true
        return false
    }

    /**
     * Lower is better. Prefer dialogue/full ASS over Signs & Songs when auto-selecting
     * anime softsubs — pack track order often lists signs first (lowest sid).
     */
    fun assDialoguePreferenceScore(name: String?, isForced: Boolean = false): Int {
        if (isForced) return 50
        val lower = name.orEmpty().lowercase()
        if (isSignsAndSongsTrack(listOf(name))) return 20
        if (lower.contains("dialogue") || (lower.contains("full") && lower.contains("sub"))) return 0
        if (Regex("\\b(signs?|on[- ]?screen)\\b").containsMatchIn(lower)) return 18
        if (Regex("\\b(songs?|lyrics?)\\b").containsMatchIn(lower)) return 16
        return 5
    }

    /**
     * Classify a subtitle's container/payload format using every available metadata layer:
     * the derived codec label, the raw sample MIME type, the URL extension, and the track
     * title / declared format string. Returns one of ASS, SSA, SRT, VTT, TTML, PGS, SUP,
     * IDX, or `null` only when no signal exists (the UI then falls back to "Text").
     *
     * Layers are consulted in order of authority: MIME/codec first, then URL extension,
     * then free-form title/format text. This avoids the "English Text" mislabel the audit
     * found when only the language was known.
     */
    fun classifySubtitleFormat(
        codecLabel: String? = null,
        sampleMimeType: String? = null,
        codecs: String? = null,
        url: String? = null,
        trackTitle: String? = null,
        declaredFormat: String? = null
    ): String? {
        // Layer 1: derived codec label (already a human token like "ASS"/"SRT"/"PGS").
        codecLabel?.trim()?.takeIf { it.isNotBlank() }?.let { label ->
            normalizeFormatToken(label)?.let { return it }
        }
        // Layer 2: raw sample MIME type / codecs string.
        for (mime in listOfNotNull(sampleMimeType, codecs)) {
            normalizeFormatToken(mime)?.let { return it }
        }
        // Layer 3: URL extension.
        if (!url.isNullOrBlank()) {
            val withoutFragment = url.substringBefore('#')
            val pathExt = withoutFragment.substringBefore('?').trimEnd('/')
                .substringAfterLast('.', "").lowercase()
            extensionToFormat(pathExt)?.let { return it }

            // Subtitle services often put the real filename in a query parameter, e.g.
            // `...?file=episode.srt`, rather than in the URL path.
            withoutFragment.substringAfter('?', "")
                .split('&')
                .asSequence()
                .map { it.substringAfterLast('.', "").lowercase() }
                .mapNotNull(::extensionToFormat)
                .firstOrNull()
                ?.let { return it }
        }
        // Layer 4: track title / declared format string.
        val titleHaystack = listOfNotNull(trackTitle, declaredFormat)
            .joinToString(" ").lowercase()
        if (titleHaystack.isNotBlank()) {
            titleFormatMatch(titleHaystack)?.let { return it }
        }
        return null
    }

    private fun normalizeFormatToken(token: String): String? {
        val lower = token.trim().lowercase()
        if (lower.isBlank()) return null
        return when {
            // ASS / SSA
            lower == "ass" || lower.endsWith("/ass") || lower.endsWith("/x-ass") ||
                lower == "ssa" || lower.endsWith("/ssa") || lower.endsWith("/x-ssa") ||
                lower == "s_text/ass" || lower == "s_text/ssa" -> "ASS"
            // SRT / SubRip
            lower == "srt" || lower.endsWith("/srt") || lower.endsWith("/x-srt") ||
                lower.contains("subrip") -> "SRT"
            // VTT / WebVTT
            lower == "vtt" || lower.endsWith("/vtt") || lower.contains("webvtt") -> "VTT"
            // TTML / IMSC
            lower == "ttml" || lower.endsWith("/ttml") || lower.endsWith("+xml") ||
                lower.contains("ttml") || lower.contains("dfxp") -> "TTML"
            // PGS / SUP (HDMV presentation graphic stream)
            lower == "pgs" || lower.endsWith("/pgs") || lower.endsWith("/x-pgs") ||
                lower.contains("hdmv") || lower == "sup" -> "PGS"
            // IDX / VobSub
            lower == "idx" || lower.contains("vobsub") -> "IDX"
            lower == "sub" && !lower.contains("subrip") -> "IDX"
            // DVB
            lower.contains("dvb") -> "DVB"
            else -> null
        }
    }

    private fun extensionToFormat(ext: String): String? = when (ext) {
        "ass" -> "ASS"
        "ssa" -> "ASS"
        "srt" -> "SRT"
        "vtt", "webvtt" -> "VTT"
        "ttml", "dfxp" -> "TTML"
        "pgs" -> "PGS"
        "sup" -> "PGS"
        "idx" -> "IDX"
        "sub" -> "IDX"
        else -> null
    }

    private fun titleFormatMatch(haystack: String): String? = when {
        "ass" in haystack || "ssa" in haystack -> "ASS"
        "subrip" in haystack || " srt" in haystack || haystack.endsWith(" srt") ||
            haystack.startsWith("srt ") -> "SRT"
        "webvtt" in haystack || " vtt" in haystack || haystack.endsWith(" vtt") ||
            haystack.startsWith("vtt ") -> "VTT"
        "ttml" in haystack || "dfxp" in haystack -> "TTML"
        "pgs" in haystack || "hdmv" in haystack || " sup" in haystack ||
            haystack.endsWith(" sup") -> "PGS"
        "vobsub" in haystack || "idx" in haystack -> "IDX"
        else -> null
    }

    /**
     * Rank same-language subtitle candidates so a dialogue/full subtitle beats a
     * signs-and-songs track, with the forced flag respected. Used by persisted-preference
     * restore and engine-switch restore so the saved English dialogue track is preferred
     * over an English signs-and-songs track that happens to share the same language code.
     *
     * @param tracks the full subtitle track list (indexes reference this list)
     * @param candidates indexes within [tracks] that already match the target language
     * @param preferForced when non-null, narrow to tracks whose [TrackInfo.isForced] matches
     *   this value before ranking; `null` keeps the forced mix as-is
     * @return the best candidate index, or -1 when no candidate remains after narrowing
     */
    fun rankSameLanguageSubtitleCandidates(
        tracks: List<TrackInfo>,
        candidates: List<Int>,
        preferForced: Boolean? = null
    ): Int {
        if (candidates.isEmpty()) return -1
        val narrowed = if (preferForced != null) {
            candidates.filter { idx -> tracks.getOrNull(idx)?.isForced == preferForced }
        } else {
            candidates
        }
        if (narrowed.isEmpty()) return -1
        if (narrowed.size == 1) return narrowed.first()
        // Prefer dialogue/full (not signs-and-songs) over signs-and-songs.
        val dialogue = narrowed.filter { idx -> tracks.getOrNull(idx)?.isSignsAndSongs != true }
        return dialogue.firstOrNull() ?: narrowed.first()
    }

    /**
     * TV-friendly subtitle role tags derived from track metadata. Keeps Forced / SDH /
     * Dialogue / Signs & Songs / Sounds / CC instead of collapsing everything to language+codec.
     */
    fun formatSubtitleRoleLabels(
        name: String?,
        isForced: Boolean = false,
        isSignsAndSongs: Boolean = false,
    ): List<String> {
        val haystack = name.orEmpty()
        val lower = haystack.lowercase()
        val roles = linkedSetOf<String>()

        if (isForced || lower.contains("forced") || Regex("\\bforce[d]?\\b").containsMatchIn(lower)) {
            roles += "Forced"
        }
        if (isSignsAndSongs || isSignsAndSongsTrack(listOf(name))) {
            roles += "Signs & Songs"
        } else {
            when {
                lower.contains("dialogue") || (lower.contains("full") && lower.contains("sub")) ->
                    roles += "Dialogue"
                Regex("\\b(songs?|lyrics?)\\b").containsMatchIn(lower) &&
                    !lower.contains("sign") -> roles += "Songs"
                Regex("\\b(signs?|on[- ]?screen)\\b").containsMatchIn(lower) -> roles += "Signs"
                Regex("\\b(sounds?|sfx|sound effects?)\\b").containsMatchIn(lower) -> roles += "Sounds"
            }
        }
        if (
            lower.contains("sdh") ||
            lower.contains("hearing impaired") ||
            lower.contains("hard of hearing") ||
            Regex("\\bhoh\\b").containsMatchIn(lower)
        ) {
            roles += "SDH"
        }
        if (Regex("\\bcc\\b").containsMatchIn(lower) || lower.contains("closed caption")) {
            roles += "CC"
        }
        if (lower.contains("commentary")) roles += "Commentary"
        if (lower.contains("dubtitle") || lower.contains("dub title")) roles += "Dubtitles"

        // Preserve leftover descriptive title text that isn't just the language / role tags.
        val residual = residualSubtitleTitle(haystack, roles)
        if (!residual.isNullOrBlank()) roles += residual

        return roles.toList()
    }

    /**
     * Primary + secondary label for a subtitle row on TV.
     * @return Pair(title, detail) where title is "Language FORMAT" and detail holds role tags.
     */
    fun formatSubtitleTrackLabel(
        languageDisplay: String,
        formatTag: String?,
        name: String?,
        isForced: Boolean = false,
        isSignsAndSongs: Boolean = false,
    ): Pair<String, String?> {
        val title = listOfNotNull(
            languageDisplay.takeIf { it.isNotBlank() },
            formatTag?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { languageDisplay }
        val detail = formatSubtitleRoleLabels(
            name = name,
            isForced = isForced,
            isSignsAndSongs = isSignsAndSongs,
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        return title to detail
    }

    private fun residualSubtitleTitle(raw: String, knownRoles: Set<String>): String? {
        if (raw.isBlank()) return null
        var text = raw.trim()
        val dropPhrases = listOf(
            "closed captions", "closed caption", "hearing impaired", "hard of hearing",
            "signs and songs", "songs and signs", "signs & songs", "songs & signs",
            "signs/songs", "songs/signs", "signs only", "songs only", "sound effects",
            "dub titles", "dubtitles", "on-screen", "on screen", "full subs", "full sub",
        )
        dropPhrases.forEach { phrase ->
            text = text.replace(phrase, " ", ignoreCase = true)
        }
        val dropTokens = listOf(
            "und", "unknown", "subtitle", "subtitles", "subs", "sub",
            "forced", "force", "sdh", "cc", "hoh",
            "signs", "sign", "songs", "song", "lyrics", "lyric",
            "dialogue", "sounds", "sfx", "commentary", "dubtitle",
            "english", "eng", "japanese", "jpn", "jap", "spanish", "spa",
            "french", "fre", "fra", "german", "ger", "deu", "portuguese", "por",
            "italian", "ita", "korean", "kor", "chinese", "chi", "zho",
            "russian", "rus", "arabic", "ara",
        )
        dropTokens.forEach { token ->
            text = text.replace(Regex("(?i)\\b${Regex.escape(token)}\\b"), " ")
        }
        knownRoles.forEach { role ->
            text = text.replace(role, " ", ignoreCase = true)
        }
        text = text
            .replace(Regex("[\\[\\](){}|_./\\\\,:;\\-+]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.length < 2) return null
        if (text.equals("ass", true) || text.equals("srt", true) || text.equals("vtt", true) ||
            text.equals("pgs", true) || text.equals("ssa", true) || text.equals("sup", true)
        ) {
            return null
        }
        return text.take(48)
    }
}
