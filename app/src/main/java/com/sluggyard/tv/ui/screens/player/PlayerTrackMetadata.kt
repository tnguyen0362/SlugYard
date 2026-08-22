package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.ui.util.LANGUAGE_OVERRIDES
import com.sluggyard.tv.ui.util.languageCodeToName
import java.util.Locale

private val audioLanguageNames: List<Pair<String, String>> by lazy {
    Locale.getAvailableLocales()
        .asSequence()
        .map(Locale::getLanguage)
        .filter(String::isNotBlank)
        .distinct()
        .mapNotNull { code ->
            val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
                .takeIf { it.length >= 4 && it.none(Char::isDigit) }
                ?: return@mapNotNull null
            name.lowercase(Locale.ENGLISH) to code
        }
        .sortedByDescending { (name, _) -> name.length }
        .toList()
}

private val ISO_TOKEN_IN_LABEL = Regex("(?<![a-z0-9])([a-z]{2,3})(?![a-z0-9])")

/** Media3 / Matroska placeholder languages that must not surface as a real locale. */
internal fun isUnsetLanguageCode(code: String?): Boolean {
    val normalized = code?.trim()?.lowercase(Locale.ENGLISH) ?: return true
    return normalized.isEmpty() ||
        normalized == "und" ||
        normalized == "unknown" ||
        normalized == "null" ||
        normalized == "zxx"
}

/**
 * Resolve a BCP-47 / ISO language code from container language or track title.
 * Titles like `English`, `[ENG]`, `jpn` are recovered when Media3 reports `und`.
 */
internal fun resolveTrackLanguageCode(language: String?, label: String?): String? {
    language
        ?.trim()
        ?.takeIf { !isUnsetLanguageCode(it) }
        ?.let { return normalizeLanguageToken(it) }

    val normalizedLabel = label
        ?.trim()
        ?.takeIf { it.isNotBlank() && !isUnsetLanguageCode(it) }
        ?.lowercase(Locale.ENGLISH)
        ?: return null

    ISO_TOKEN_IN_LABEL.findAll(normalizedLabel).forEach { match ->
        normalizeLanguageToken(match.groupValues[1])?.let { return it }
    }

    return audioLanguageNames.firstOrNull { (name, _) ->
        Regex("(?<![a-z])${Regex.escape(name)}(?![a-z])").containsMatchIn(normalizedLabel)
    }?.second
}

/** @deprecated Prefer [resolveTrackLanguageCode]; kept for call-site compatibility. */
internal fun resolveAudioTrackLanguageCode(language: String?, label: String?): String? =
    resolveTrackLanguageCode(language, label)

/**
 * Viewer-facing language for audio/subtitle rows.
 * Never returns Locale's "Unknown language" for `und` — falls back to a track ordinal.
 */
internal fun trackLanguageDisplayName(
    language: String?,
    label: String?,
    ordinal1Based: Int,
): String {
    resolveTrackLanguageCode(language, label)?.let { return languageCodeToName(it) }
    val cleaned = label?.trim()?.takeIf { it.isNotBlank() && !isUnsetLanguageCode(it) && !isGenericTrackLabel(it) }
    if (cleaned != null) {
        if (cleaned.matches(Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,4})?"))) {
            return languageCodeToName(cleaned)
        }
        return cleaned
    }
    return "Track $ordinal1Based"
}

private fun isGenericTrackLabel(label: String): Boolean {
    val lower = label.trim().lowercase(Locale.ENGLISH)
    return lower.matches(Regex("audio\\s*\\d*")) ||
        lower.matches(Regex("subtitle\\s*\\d*")) ||
        lower.matches(Regex("track\\s*\\d*")) ||
        lower == "und" ||
        lower == "unknown"
}

private fun normalizeLanguageToken(raw: String): String? {
    val lower = raw.trim().lowercase(Locale.ENGLISH).replace('_', '-')
    if (isUnsetLanguageCode(lower)) return null
    LANGUAGE_OVERRIDES[lower]?.let { return it }
    val primary = lower.substringBefore('-')
    val region = lower.substringAfter('-', missingDelimiterValue = "")
    LANGUAGE_OVERRIDES[primary]?.let { mapped ->
        return if (region.isNotBlank()) "$mapped-${region.uppercase(Locale.ENGLISH)}" else mapped
    }
    if (primary.length == 2 && primary.all { it.isLetter() }) {
        return if (region.isNotBlank()) "$primary-${region.uppercase(Locale.ENGLISH)}" else primary
    }
    return null
}
