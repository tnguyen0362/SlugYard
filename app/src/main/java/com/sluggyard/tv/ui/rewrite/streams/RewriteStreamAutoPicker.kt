package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState

internal fun selectRewriteAutoPlayCandidate(groups: List<RewriteStreamGroup>): RewriteStreamCandidate? {
    val candidates = groups.flatMap { group ->
        (group.state as? RewriteStreamGroupState.Content)?.streams.orEmpty()
    }
    return candidates.firstOrNull { !it.directUrl.isNullOrBlank() }
        ?: candidates.firstOrNull { it.cacheState == StreamCacheState.CACHED && it.hasTorrentHash() }
        ?: candidates.firstOrNull { it.isAutoPlayableTorrent() }
}

private fun RewriteStreamCandidate.hasTorrentHash(): Boolean = !infoHash.isNullOrBlank()

private fun RewriteStreamCandidate.isAutoPlayableTorrent(): Boolean =
    hasTorrentHash() && cacheState != StreamCacheState.CHECKING && cacheState != StreamCacheState.NOT_CACHED
