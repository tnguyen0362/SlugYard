package com.sluggyard.tv.data.repository

/**
 * Compact episode descriptor used by the addon ↔ Trakt numbering reconciliation
 * layer. Kept deliberately small so it can be cached and diffed in bulk without
 * pulling the full [com.sluggyard.tv.domain.model.Video] graph into memory.
 */
internal data class EpisodeMappingEntry(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val videoId: String? = null
)

/**
 * Maps an addon episode coordinate to the equivalent Trakt episode. Title match
 * wins when unambiguous; otherwise positional indexing within the season is
 * used as a fallback.
 */
internal fun remapEpisodeByTitleOrIndex(
    requestedSeason: Int,
    requestedEpisode: Int,
    requestedVideoId: String?,
    requestedTitle: String? = null,
    addonEpisodes: List<EpisodeMappingEntry>,
    traktEpisodes: List<EpisodeMappingEntry>
): EpisodeMappingEntry? = remapBetweenSources(
    requestedSeason = requestedSeason,
    requestedEpisode = requestedEpisode,
    requestedVideoId = requestedVideoId,
    requestedTitle = requestedTitle,
    from = addonEpisodes,
    to = traktEpisodes
)

/**
 * Inverse of [remapEpisodeByTitleOrIndex]: maps a Trakt episode back into the
 * addon's numbering space. Used when surfacing Trakt-sourced history into addon
 * season/episode coordinates.
 */
internal fun reverseRemapEpisodeByTitleOrIndex(
    requestedSeason: Int,
    requestedEpisode: Int,
    requestedVideoId: String? = null,
    requestedTitle: String? = null,
    addonEpisodes: List<EpisodeMappingEntry>,
    traktEpisodes: List<EpisodeMappingEntry>
): EpisodeMappingEntry? = remapBetweenSources(
    requestedSeason = requestedSeason,
    requestedEpisode = requestedEpisode,
    requestedVideoId = requestedVideoId,
    requestedTitle = requestedTitle,
    from = traktEpisodes,
    to = addonEpisodes
)

private fun remapBetweenSources(
    requestedSeason: Int,
    requestedEpisode: Int,
    requestedVideoId: String?,
    requestedTitle: String?,
    from: List<EpisodeMappingEntry>,
    to: List<EpisodeMappingEntry>
): EpisodeMappingEntry? {
    if (from.isEmpty() || to.isEmpty()) return null

    val orderedFrom = from.sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))
    val orderedTo = to.sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))

    val anchor = requestedVideoId
        ?.takeIf { it.isNotBlank() }
        ?.let { vid -> orderedFrom.firstOrNull { it.videoId == vid } }
        ?: orderedFrom.firstOrNull { it.season == requestedSeason && it.episode == requestedEpisode }
        ?: return null

    val normalizedTitle = normalizeTitleForMatch(requestedTitle ?: anchor.title)
    if (isMeaningfulTitle(normalizedTitle)) {
        val titleMatches = orderedTo.filter { normalizeTitleForMatch(it.title) == normalizedTitle }
        if (titleMatches.size == 1) return titleMatches.first()
    }

    val sourceIndex = orderedFrom.indexOf(anchor)
    if (sourceIndex !in orderedTo.indices) return null
    return orderedTo[sourceIndex]
}

private val titleSeparatorRegex = Regex("[^a-z0-9]+")
private val collapsedWhitespaceRegex = Regex("\\s+")
private val titleNormalizeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun normalizeTitleForMatch(title: String?): String {
    if (title == null) return ""
    return titleNormalizeCache.getOrPut(title) {
        title.lowercase()
            .replace(titleSeparatorRegex, " ")
            .trim()
            .replace(collapsedWhitespaceRegex, " ")
    }
}

private fun isMeaningfulTitle(normalized: String): Boolean {
    if (normalized.isBlank()) return false
    val generic = listOf(
        Regex("episode \\d+"),
        Regex("ep \\d+"),
        Regex("e \\d+")
    )
    return generic.none { it.matches(normalized) }
}