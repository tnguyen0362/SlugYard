package com.sluggyard.tv.ui.app.details

/** A Stremio stream request target derived from a rewritten Details state. */
data class PlaybackTarget(
    val type: String,
    val id: String,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Series stream endpoints accept a video/episode id, never the parent show id.
 * Prefer the next unwatched episode in series order (all regular seasons), then
 * fall back to the first episode of the first regular season.
 */
fun DetailsState.playbackTarget(parentType: String, parentId: String): PlaybackTarget? {
    if (!isSeries) return PlaybackTarget(parentType, parentId)
    val regularSeasons = seasons.filter { it.number > 0 }.ifEmpty { seasons }.sortedBy { it.number }
    for (season in regularSeasons) {
        val next = season.episodes.firstOrNull { !it.watched } ?: continue
        return PlaybackTarget("series", next.id, season.number, next.number)
    }
    val firstSeason = regularSeasons.firstOrNull() ?: return null
    val firstEpisode = firstSeason.episodes.firstOrNull() ?: return null
    return PlaybackTarget("series", firstEpisode.id, firstSeason.number, firstEpisode.number)
}
