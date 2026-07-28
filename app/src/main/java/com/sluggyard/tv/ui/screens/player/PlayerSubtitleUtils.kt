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
}
