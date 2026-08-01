package com.sluggyard.tv.ui.app.streams

import android.util.Log
import com.sluggyard.tv.core.streamresolution.StreamCacheState

private const val AUTO_PICK_TAG = "SlugYardAutoPick"

/**
 * Rewrite-owned auto-pick boundary. Candidate validation/deduplication stays here; quality,
 * reliability, and content-aware ranking live in [StreamScoringEngine].
 */
internal fun selectAutoPlayCandidate(
    groups: List<StreamGroup>,
    context: StreamScoringEngine.Context = StreamScoringEngine.Context(
        title = "",
        contentType = "",
    ),
    excludedCandidateIds: Set<String> = emptySet(),
): StreamCandidate? {
    val candidates = groups
        .flatMap { group -> (group.state as? StreamGroupState.Content)?.streams.orEmpty() }
        .filter { it.isWellFormed() }
        .filterNot { it.id in excludedCandidateIds }
        .distinctBy { it.dedupeKey() }

    val ranked = StreamScoringEngine.rankedCandidates(candidates, context)
    val winner = StreamScoringEngine.choose(candidates, context)

    val groupSummary = groups.joinToString(" | ") { group ->
        val content = (group.state as? StreamGroupState.Content)?.streams.orEmpty()
        val cached = content.count { it.cacheState == StreamCacheState.CACHED }
        val total = content.size
        "${group.addonName}:$total/${cached}c"
    }
    Log.i(
        AUTO_PICK_TAG,
        "pick title='${context.title}' type=${context.contentType} " +
            "kind=${StreamScoringEngine.contentKind(context)} " +
            "genres=${context.genres} lang=${context.language} " +
            "preferredSub=${context.preferredSubtitleLanguage} " +
            "candidates=${candidates.size} eligibleRanked=${ranked.count { it.isAutoPlayEligibleForLog() }} " +
            "groups=[$groupSummary]",
    )
    ranked.take(12).forEachIndexed { index, candidate ->
        val rank = StreamScoringEngine.rank(candidate, context)
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
    val winnerRank = winner?.let { StreamScoringEngine.rank(it, context) }
    Log.i(
        AUTO_PICK_TAG,
        "winner src=${winner?.sourceLabel} cache=${winner?.cacheState} " +
            "soft=${winnerRank?.softsubFit} dual=${winnerRank?.dualFit} seeds=${winnerRank?.seeders} " +
            "title='${winner?.title?.replace('\n', ' ')?.take(160)}' " +
            "release='${winner?.releaseTextForLog()?.replace('\n', ' ')?.take(80)}'",
    )

    return winner
}

private fun StreamCandidate.releaseTextForLog(): String =
    listOf(
        filename.orEmpty(),
        title,
        detailLabel.orEmpty(),
        streamDescription.orEmpty(),
    ).joinToString(" ").trim()

private fun StreamCandidate.isAutoPlayEligibleForLog(): Boolean {
    val hash = infoHash?.trim().orEmpty()
    if (hash.isNotBlank()) {
        return cacheState == StreamCacheState.CACHED ||
            cacheState == StreamCacheState.NOT_APPLICABLE
    }
    return !directUrl.isNullOrBlank()
}

internal fun hasPendingCacheChecks(groups: List<StreamGroup>): Boolean =
    groups.any { group ->
        (group.state as? StreamGroupState.Content)
            ?.streams
            ?.any { it.cacheState == StreamCacheState.CHECKING } == true
    }

/** Guards against candidates with no actionable source at all (neither a direct URL nor a
 * torrent hash) — these should never be selectable by auto-pick, manual or not. */
private fun StreamCandidate.isWellFormed(): Boolean =
    !directUrl.isNullOrBlank() || !infoHash.isNullOrBlank()

private fun StreamCandidate.dedupeKey(): String =
    when {
        !directUrl.isNullOrBlank() -> "url:$directUrl"
        !infoHash.isNullOrBlank() -> "hash:${infoHash.lowercase()}:${fileIndex ?: -1}"
        else -> id
    }
