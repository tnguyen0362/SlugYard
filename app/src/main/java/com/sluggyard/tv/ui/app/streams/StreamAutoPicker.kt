package com.sluggyard.tv.ui.app.streams

import android.util.Log
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.data.local.CachedStreamLink

private const val AUTO_PICK_TAG = "SlugYardAutoPick"

/** Leanback boxes choke ranking 200+ streams; auto-play only needs a small cached pool. */
private const val MaxAutoPickScoreCandidates = 40

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

    // Instant/cached only — scoring NOT_CACHED torrents was burning ~20s on Onn for JJK/Mario.
    val cachedPool = candidates.filter { it.isAutoPlayEligibleForLog() }
    val scorePool = if (cachedPool.size <= MaxAutoPickScoreCandidates) {
        cachedPool
    } else {
        // Prefer seeded / higher-res rows before the hard cap so we don't score random first-N.
        cachedPool
            .sortedWith(
                compareByDescending<StreamCandidate> { it.seeders ?: 0 }
                    .thenByDescending { autoPickQualityHint(it.title) },
            )
            .take(MaxAutoPickScoreCandidates)
    }

    val winner = StreamScoringEngine.choose(scorePool, context)

    val groupSummary = groups.joinToString(" | ") { group ->
        val content = (group.state as? StreamGroupState.Content)?.streams.orEmpty()
        val cached = content.count { it.cacheState == StreamCacheState.CACHED }
        val total = content.size
        "${group.addonName}:$total/${cached}c"
    }
    val winnerRank = winner?.let { StreamScoringEngine.rank(it, context) }
    Log.i(
        AUTO_PICK_TAG,
        "pick title='${context.title}' type=${context.contentType} " +
            "kind=${StreamScoringEngine.contentKind(context)} " +
            "genres=${context.genres} lang=${context.language} " +
            "preferredSub=${context.preferredSubtitleLanguage} " +
            "candidates=${candidates.size} cachedPool=${cachedPool.size} scored=${scorePool.size} " +
            "groups=[$groupSummary]",
    )
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
 * Cheap softsub readiness for Finding gates — keyword scan only.
 * Full [StreamScoringEngine.rank] belongs in the single-flight pick, not every scrape tick.
 */
internal fun hasLikelySoftsubCachedHint(groups: List<StreamGroup>): Boolean =
    groups.asSequence()
        .flatMap { group -> (group.state as? StreamGroupState.Content)?.streams.orEmpty().asSequence() }
        .filter { it.isWellFormed() && it.isAutoPlayEligibleForLog() }
        .any { candidate ->
            val text = candidate.releaseTextForLog().lowercase()
            if (Regex("""\braw\b""").containsMatchIn(text)) return@any false
            Regex("""\b(?:soft\s*subs?|multi\s*subs?|ass|ssa|srt|subbed|subs)\b""")
                .containsMatchIn(text)
        }

/**
 * True when auto-play already has a cached/instant winner with a real softsub signal.
 *
 * Default floor is softsubFit >= 2 (any softsub/multi-sub/SRT signal). Preferred-lang
 * match scores 3–4, but with preferred=en many cached WEB-DL softsubs only score 2
 * (no explicit "Eng" tag) — requiring 3 left anime idling on AIOStreams for the full
 * hard ceiling every Play.
 *
 * Scans a capped cached pool only — formerly called [StreamScoringEngine.choose] on the
 * full addon dump every Compose tick, which froze Finding on Onn (200–300 candidates).
 */
internal fun hasEligibleSoftsubAutoPlay(
    groups: List<StreamGroup>,
    context: StreamScoringEngine.Context,
    minSoftsubFit: Int = 2,
): Boolean {
    // Fast path for readiness callers; full rank only when the cheap hint matches.
    if (!hasLikelySoftsubCachedHint(groups)) return false
    val cached = groups
        .asSequence()
        .flatMap { group -> (group.state as? StreamGroupState.Content)?.streams.orEmpty().asSequence() }
        .filter { it.isWellFormed() && it.isAutoPlayEligibleForLog() }
        .sortedWith(
            compareByDescending<StreamCandidate> { it.seeders ?: 0 }
                .thenByDescending { autoPickQualityHint(it.title) },
        )
        .take(MaxAutoPickScoreCandidates)
        .toList()
    if (cached.isEmpty()) return false
    return cached.any { StreamScoringEngine.rank(it, context).softsubFit >= minSoftsubFit }
}

/** True when any cached/instant candidate exists. Cheap O(n) — must NOT call
 * [StreamScoringEngine.choose] (that ranked the full dump on every streamGroups tick). */
@Suppress("UNUSED_PARAMETER")
internal fun hasEligibleCachedAutoPlay(
    groups: List<StreamGroup>,
    context: StreamScoringEngine.Context,
): Boolean =
    groups
        .asSequence()
        .flatMap { group -> (group.state as? StreamGroupState.Content)?.streams.orEmpty().asSequence() }
        .any { it.isWellFormed() && it.isAutoPlayEligibleForLog() }

/** Lightweight title quality hint used only to pick which rows enter the score cap. */
private fun autoPickQualityHint(title: String): Int {
    val t = title.lowercase()
    return when {
        "2160" in t || "4k" in t || "uhd" in t -> 4
        "1080" in t || "fhd" in t -> 3
        "720" in t -> 1
        else -> 2
    }
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
