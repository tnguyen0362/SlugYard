package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState

/**
 * Rewrite-owned auto-pick boundary. Candidate validation/deduplication stays here; quality,
 * reliability, and content-aware ranking live in [RewriteStreamScoringEngine].
 */
internal fun selectRewriteAutoPlayCandidate(
    groups: List<RewriteStreamGroup>,
    context: RewriteStreamScoringEngine.Context = RewriteStreamScoringEngine.Context(
        title = "",
        contentType = "",
    ),
    excludedCandidateIds: Set<String> = emptySet(),
): RewriteStreamCandidate? {
    val candidates = groups
        .flatMap { group -> (group.state as? RewriteStreamGroupState.Content)?.streams.orEmpty() }
        .filter { it.isWellFormed() }
        .filterNot { it.id in excludedCandidateIds }
        .distinctBy { it.dedupeKey() }

    return RewriteStreamScoringEngine.choose(
        candidates,
        context,
    )
}

internal fun hasRewritePendingCacheChecks(groups: List<RewriteStreamGroup>): Boolean =
    groups.any { group ->
        (group.state as? RewriteStreamGroupState.Content)
            ?.streams
            ?.any { it.cacheState == StreamCacheState.CHECKING } == true
    }

/** Guards against candidates with no actionable source at all (neither a direct URL nor a
 * torrent hash) — these should never be selectable by auto-pick, manual or not. */
private fun RewriteStreamCandidate.isWellFormed(): Boolean =
    !directUrl.isNullOrBlank() || !infoHash.isNullOrBlank()

private fun RewriteStreamCandidate.dedupeKey(): String =
    when {
        !directUrl.isNullOrBlank() -> "url:$directUrl"
        !infoHash.isNullOrBlank() -> "hash:${infoHash.lowercase()}:${fileIndex ?: -1}"
        else -> id
    }
