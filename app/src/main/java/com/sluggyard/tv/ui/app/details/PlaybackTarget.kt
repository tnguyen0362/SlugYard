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
 * Prefer the next unwatched episode, then retain a deterministic first-episode fallback.
 */
fun DetailsState.playbackTarget(parentType: String, parentId: String): PlaybackTarget? {
    if (!isSeries) return PlaybackTarget(parentType, parentId)
    val regularSeasons = seasons.filter { it.number > 0 }.ifEmpty { seasons }
    val selected = regularSeasons.firstOrNull { it.number == selectedSeason }
        ?: regularSeasons.firstOrNull()
    val episodes = selected?.episodes.orEmpty().asSequence()
    val target = episodes.firstOrNull { !it.watched } ?: episodes.firstOrNull()
    return target?.let { PlaybackTarget("series", it.id, selected?.number, it.number) }
}
