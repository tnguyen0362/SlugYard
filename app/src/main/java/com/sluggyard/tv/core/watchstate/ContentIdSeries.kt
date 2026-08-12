package com.sluggyard.tv.core.watchstate

/**
 * Stremio-style ids often encode the episode as `showId:season:episode`
 * (`tt123:2:9`, `kitsu:45823:1:12`). Watched / progress stores must key the
 * **show** so Details `completedEpisodeKeys(metadata.id)` can find them.
 */
fun seriesContentId(contentId: String): String {
    val trimmed = contentId.trim()
    if (trimmed.isEmpty()) return trimmed
    val parts = trimmed.split(':')
    if (parts.size < 3) return trimmed
    val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return trimmed
    val episode = parts.last().toIntOrNull() ?: return trimmed
    if (season < 0 || episode <= 0) return trimmed
    return parts.dropLast(2).joinToString(":")
}

/** Trailing `:season:episode` from Stremio-style video ids. */
fun parseSeasonEpisodeFromVideoId(videoId: String): Pair<Int, Int>? {
    val parts = videoId.trim().split(':')
    if (parts.size < 3) return null
    val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
    val episode = parts.last().toIntOrNull() ?: return null
    if (season < 0 || episode <= 0) return null
    return season to episode
}

/**
 * True when episode numbers in a season look like continuous series-wide counters
 * (e.g. S2 is 13..24) rather than season-local (1..N).
 */
fun seasonEpisodeLooksSeriesAbsolute(episodeNumbers: List<Int>): Boolean {
    // Need at least two numbers to tell absolute (13..24) from season-local (1..N).
    // A lone ep>1 must not renumber to 1 (e.g. sparse S2E2-only lists).
    if (episodeNumbers.size < 2) return false
    val min = episodeNumbers.min()
    val max = episodeNumbers.max()
    // Season-local packs usually start near 1; continuous packs jump high (S1 max + 1).
    return min > 1 && max > episodeNumbers.size
}

/**
 * Season-local number for UI badges / TorBox-facing SxEy.
 * Prefer video-id `:season:episode`, then index renumber when meta is absolute, else meta.
 */
fun seasonLocalDisplayNumber(
    videoId: String,
    season: Int,
    metaEpisode: Int,
    seasonIndex0: Int,
    looksSeriesAbsolute: Boolean,
): Int {
    val fromId = parseSeasonEpisodeFromVideoId(videoId)
    if (fromId != null && fromId.first == season && fromId.second > 0) {
        return fromId.second
    }
    if (looksSeriesAbsolute) return seasonIndex0 + 1
    return metaEpisode.takeIf { it > 0 } ?: (seasonIndex0 + 1)
}
