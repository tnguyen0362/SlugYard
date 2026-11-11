package com.sluggyard.tv.core.debrid

/**
 * Parses a seeder count out of free-text stream metadata. Torrentio (and addons that copy its
 * layout) send no structured seeders field; the count only appears in the title as
 * "👤 21 💾 3.69 GB ⚙️ ...". Without this every such row ranked as "unknown seeders", so the
 * auto-picker could not tell a 4-seed upload from a 400-seed release.
 *
 * Deliberately narrow: an emoji marker or an explicit "seeders"/"seeds" word must sit right
 * before the number. Resolutions, years and sizes ("2160p", "2009", "26.46 GB") never match.
 */
object StreamTextSeedersParser {

    private val SEEDERS_REGEX = Regex(
        """(?:👤|👥|🌱|\bseeders?\b|\bseeds?\b)\s*[:=]?\s*(\d{1,6})(?![\d.,]*\s*(?:[kmgt]i?b|p)\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun seedersFromText(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return SEEDERS_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun seedersFromTexts(vararg texts: String?): Int? =
        texts.firstNotNullOfOrNull { seedersFromText(it) }
}
