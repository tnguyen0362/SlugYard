package com.sluggyard.tv.ui.rewrite.streams

import android.util.Log
import com.sluggyard.tv.core.streamresolution.StreamCacheState

private const val AUTO_PICK_TAG = "SlugYardAutoPick"

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

    val ranked = RewriteStreamScoringEngine.rankedCandidates(candidates, context)
    val winner = RewriteStreamScoringEngine.choose(candidates, context)

    val groupSummary = groups.joinToString(" | ") { group ->
        val content = (group.state as? RewriteStreamGroupState.Content)?.streams.orEmpty()
        val cached = content.count { it.cacheState == StreamCacheState.CACHED }
        val total = content.size
        "${group.addonName}:$total/${cached}c"
    }
    Log.i(
        AUTO_PICK_TAG,
        "pick title='${context.title}' type=${context.contentType} " +
            "kind=${RewriteStreamScoringEngine.contentKind(context)} " +
            "genres=${context.genres} lang=${context.language} " +
            "preferredSub=${context.preferredSubtitleLanguage} " +
            "candidates=${candidates.size} eligibleRanked=${ranked.count { it.isAutoPlayEligibleForLog() }} " +
            "groups=[$groupSummary]",
    )
    ranked.take(12).forEachIndexed { index, candidate ->
        val rank = RewriteStreamScoringEngine.rank(candidate, context)
        val label = candidate.title.replace('\n', ' ').take(120)
        val releaseSnippet = candidate.releaseTextForLog().replace('\n', ' ').take(80)
        Log.i(
            AUTO_PICK_TAG,
            "#$index src=${candidate.sourceLabel} cache=${candidate.cacheState} " +
                "soft=${rank.softsubFit} dual=${rank.dualFit} seeds=${rank.seeders} " +
                "dec=${rank.decodeFit} q=${rank.quality} sizeFit=${rank.sizeFit} " +
                "bytes=${candidate.videoSizeBytes ?: -1} " +
                "title='$label' release='$releaseSnippet'",
        )
    }
    val winnerRank = winner?.let { RewriteStreamScoringEngine.rank(it, context) }
    Log.i(
        AUTO_PICK_TAG,
        "winner src=${winner?.sourceLabel} cache=${winner?.cacheState} " +
            "soft=${winnerRank?.softsubFit} dual=${winnerRank?.dualFit} seeds=${winnerRank?.seeders} " +
            "title='${winner?.title?.replace('\n', ' ')?.take(160)}' " +
            "release='${winner?.releaseTextForLog()?.replace('\n', ' ')?.take(80)}'",
    )

    return winner
}

private fun RewriteStreamCandidate.releaseTextForLog(): String =
    listOf(
        filename.orEmpty(),
        title,
        detailLabel.orEmpty(),
        streamDescription.orEmpty(),
    ).joinToString(" ").trim()

private fun RewriteStreamCandidate.isAutoPlayEligibleForLog(): Boolean {
    val hash = infoHash?.trim().orEmpty()
    if (hash.isNotBlank()) {
        return cacheState == StreamCacheState.CACHED ||
            cacheState == StreamCacheState.NOT_APPLICABLE
    }
    return !directUrl.isNullOrBlank()
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
