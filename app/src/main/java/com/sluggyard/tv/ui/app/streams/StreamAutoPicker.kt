package com.sluggyard.tv.ui.app.streams

import android.util.Log
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.data.local.CachedStreamLink

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
    preferLastPlayed: CachedStreamLink? = null,
): StreamCandidate? {
    val candidates = groups
        .flatMap { group -> (group.state as? StreamGroupState.Content)?.streams.orEmpty() }
        .filter { it.isWellFormed() }
        .filterNot { it.id in excludedCandidateIds }
        .distinctBy { it.dedupeKey() }

    preferLastPlayed?.let { cached ->
        val match = candidates.firstOrNull { it.matchesLastPlayed(cached) && it.isAutoPlayEligibleForLog() }
        if (match != null) {
            Log.i(
                AUTO_PICK_TAG,
                "preferLastPlayed hit src=${match.sourceLabel} hash=${cached.infoHash} file=${cached.filename}",
            )
            return match
        }
        Log.i(
            AUTO_PICK_TAG,
            "preferLastPlayed miss hash=${cached.infoHash} file=${cached.filename} among=${candidates.size}",
        )
    }

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
    // Cap log spam — dumping dozens of lines per discovery tick froze leanback boxes.
    ranked.take(6).forEachIndexed { index, candidate ->
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

/** True when this listing is the same release we played last (hash / file / URL). */
internal fun StreamCandidate.matchesLastPlayed(cached: CachedStreamLink): Boolean {
    val cachedHash = cached.infoHash?.trim()?.lowercase().orEmpty()
    val thisHash = infoHash?.trim()?.lowercase().orEmpty()
    if (cachedHash.isNotBlank()) {
        // Hash identity wins; a conflicting fileIdx is a miss — never fall through to
        // shared filenames / generic stream titles (multi-file torrents collide easily).
        if (thisHash != cachedHash) return false
        val cachedIdx = cached.fileIdx
        val thisIdx = fileIndex
        return cachedIdx == null || thisIdx == null || cachedIdx == thisIdx
    }
    val cachedVideoHash = cached.videoHash?.trim()?.lowercase().orEmpty()
    val thisVideoHash = videoHash?.trim()?.lowercase().orEmpty()
    if (cachedVideoHash.isNotBlank()) {
        return thisVideoHash == cachedVideoHash
    }
    val cachedFile = cached.filename?.trim()?.lowercase().orEmpty()
    val thisFile = filename?.trim()?.lowercase().orEmpty()
    if (cachedFile.isNotBlank()) {
        return thisFile == cachedFile
    }
    val cachedUrl = cached.url.trim()
    val thisUrl = directUrl?.trim().orEmpty()
    if (cachedUrl.isNotBlank() && thisUrl.isNotBlank()) {
        return cachedUrl == thisUrl
    }
    return false
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

/**
 * True when auto-play already has a cached/instant winner with real softsub signal (ASS/softsubs).
 * Used to stop anime from idling on AIOStreams Loading when Comet/others already have playable ASS.
 */
internal fun hasEligibleSoftsubAutoPlay(
    groups: List<StreamGroup>,
    context: StreamScoringEngine.Context,
    minSoftsubFit: Int = 3,
): Boolean {
    val candidates = groups
        .flatMap { group -> (group.state as? StreamGroupState.Content)?.streams.orEmpty() }
        .filter { it.isWellFormed() }
    val winner = StreamScoringEngine.choose(candidates, context) ?: return false
    return StreamScoringEngine.rank(winner, context).softsubFit >= minSoftsubFit
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
