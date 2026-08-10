package com.sluggyard.tv.ui.screens.player

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

internal fun resolveAudioTrackLanguageCode(language: String?, label: String?): String? {
    language
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { return it }
    val normalizedLabel = label?.lowercase(Locale.ENGLISH)?.takeIf(String::isNotBlank) ?: return null
    return audioLanguageNames.firstOrNull { (name, _) ->
        Regex("(?<![a-z])${Regex.escape(name)}(?![a-z])").containsMatchIn(normalizedLabel)
    }?.second
}
