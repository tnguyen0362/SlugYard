package com.sluggyard.tv.ui.rewrite.details

/** A Stremio stream request target derived from a rewritten Details state. */
data class RewritePlaybackTarget(val type: String, val id: String)

/**
 * Series stream endpoints accept a video/episode id, never the parent show id.
 * Prefer the next unwatched episode, then retain a deterministic first-episode fallback.
 */
fun RewriteDetailsState.playbackTarget(parentType: String, parentId: String): RewritePlaybackTarget {
    if (!isSeries) return RewritePlaybackTarget(parentType, parentId)
    val episodes = seasons.asSequence().flatMap { it.episodes.asSequence() }
    val target = episodes.firstOrNull { !it.watched } ?: episodes.firstOrNull()
    return target?.let { RewritePlaybackTarget("series", it.id) }
        ?: RewritePlaybackTarget(parentType, parentId)
}
