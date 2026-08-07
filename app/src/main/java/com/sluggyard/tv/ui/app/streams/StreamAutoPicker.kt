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
    /**
     * After TorBox probes settle with zero Instant hits, allow a forced-debrid
     * start of the best NOT_CACHED / UNKNOWN release instead of sending the user
     * to an empty cached Sources state.
     */
    allowUncachedFallback: Boolean = false,
): StreamCandidate? {
    val candidates = groups
        .flatMap { group -> (group.state as? StreamGroupState.Content)?.streams.orEmpty() }
        .filter { it.isWellFormed() }
        .filterNot { it.id in excludedCandidateIds }
        .distinctBy { it.dedupeKey() }

    preferLastPlayed?.let { cached ->
        val match = candidates.firstOrNull { candidate ->
            candidate.matchesLastPlayed(cached) &&
                (
                    candidate.isAutoPlayEligibleForLog() ||
                        (allowUncachedFallback && candidate.isUncachedFallbackEligibleForLog())
                    )
        }
        if (match != null) {
            Log.i(
                AUTO_PICK_TAG,
                "preferLastPlayed hit src=${match.sourceLabel} cache=${match.cacheState} " +
                    "hash=${cached.infoHash} file=${cached.filename}",
            )
            return match
        }
        Log.i(
            AUTO_PICK_TAG,
            "preferLastPlayed miss hash=${cached.infoHash} file=${cached.filename} among=${candidates.size}",
        )
    }

    // Mainstream: Instant-only until probes settle. Anime: once uncached fallback is open, score
    // Instant + download-start rows together so Dual/ASS can beat Instant Eng-PGS mono.
    val kind = StreamScoringEngine.contentKind(context)
    val cachedPool = candidates.filter { it.isAutoPlayEligibleForLog() }
    val uncachedPool = candidates.filter { it.isUncachedFallbackEligibleForLog() }
    val rankingPool = when {
        kind == StreamScoringEngine.ContentKind.ANIME && allowUncachedFallback -> {
            val combined = (cachedPool + uncachedPool).distinctBy { it.dedupeKey() }
            if (combined.isNotEmpty()) combined else emptyList()
        }
        cachedPool.isNotEmpty() -> cachedPool
        allowUncachedFallback -> uncachedPool
        else -> emptyList()
    }
    val scorePool = if (rankingPool.size <= MaxAutoPickScoreCandidates) {
        rankingPool
    } else {
        // Cap without melting leanback: never score 200+ rows.
        // Anime: dual/soft/curator/memory before seeders so Eng-PGS Instant doesn't crowd out
        // SeaDex duals. Mainstream: seeder + res (survivability / popularity).
        when (kind) {
            StreamScoringEngine.ContentKind.ANIME ->
                rankingPool
                    .sortedWith(animeScorePoolAdmissionOrder(context))
                    .take(MaxAutoPickScoreCandidates)
            else ->
                rankingPool
                    .sortedWith(
                        compareByDescending<StreamCandidate> { it.seeders ?: 0 }
                            .thenByDescending { autoPickQualityHint(it.title) },
                    )
                    .take(MaxAutoPickScoreCandidates)
        }
    }

    val winner = StreamScoringEngine.choose(
        scorePool,
        context,
        // Mainstream: if Instant exists, stay Instant-only (old gate).
        // Anime: Instant is ranked after dual/soft — open uncached when the shell says so.
        allowUncachedFallback = when (kind) {
            StreamScoringEngine.ContentKind.ANIME -> allowUncachedFallback
            else -> allowUncachedFallback && cachedPool.isEmpty()
        },
    )

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
            "candidates=${candidates.size} cachedPool=${cachedPool.size} " +
            "uncachedFallback=$allowUncachedFallback scored=${scorePool.size} " +
            "groups=[$groupSummary]",
    )
    Log.i(
        AUTO_PICK_TAG,
        "winner src=${winner?.sourceLabel} cache=${winner?.cacheState} " +
            "soft=${winnerRank?.softsubFit} dual=${winnerRank?.dualFit} seeds=${winnerRank?.seeders} " +
            "curator=${winnerRank?.curatorFit} observed=${winnerRank?.usedObservedTracks} " +
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

/** Torrent not Instant in TorBox but startable via debrid download. */
private fun StreamCandidate.isUncachedFallbackEligibleForLog(): Boolean {
    val hash = infoHash?.trim().orEmpty()
    if (hash.isBlank()) return !directUrl.isNullOrBlank()
    return cacheState == StreamCacheState.NOT_CACHED || cacheState == StreamCacheState.UNKNOWN
}

internal fun hasPendingCacheChecks(groups: List<StreamGroup>): Boolean =
    groups.any { group ->
        (group.state as? StreamGroupState.Content)
            ?.streams
            ?.any { it.cacheState == StreamCacheState.CHECKING } == true
    }

private val RAW_HINT = Regex("""\braw\b""")
private val SOFTSUB_HINT = Regex("""\b(?:soft\s*subs?|multi\s*subs?|ass|ssa|srt|subbed|subs)\b""")

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
            if (RAW_HINT.containsMatchIn(text)) return@any false
            SOFTSUB_HINT.containsMatchIn(text)
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

/**
 * Cheap admission sort for the anime score cap — keyword + memory only, never full [StreamScoringEngine.rank]
 * (that path used to freeze Finding on Onn when applied to whole dumps every tick).
 *
 * Order: observed dual/ASS memory → Dual-Audio token → softsub tokens → SeaDex curator →
 * Instant → seeders (survivability) → res.
 */
private fun animeScorePoolAdmissionOrder(
    context: StreamScoringEngine.Context,
): Comparator<StreamCandidate> {
    val dualHint = Regex("""\b(dual.?audio|multi.?audio)\b""", RegexOption.IGNORE_CASE)
    val softHint = Regex(
        """\b(?:soft\s*subs?|multi\s*subs?|ass|ssa|srt|subbed|subs|pgs)\b""",
        RegexOption.IGNORE_CASE,
    )
    fun releaseBlob(c: StreamCandidate): String =
        listOf(c.filename.orEmpty(), c.title, c.detailLabel.orEmpty(), c.streamDescription.orEmpty())
            .joinToString(" ")
            .lowercase()

    fun memoryBoost(c: StreamCandidate): Int {
        val hash = c.infoHash?.trim()?.lowercase().orEmpty()
        if (hash.isEmpty()) return 0
        val obs = context.observedTracksByHash[hash] ?: return 0
        var b = 2
        if (obs.dualAudio) b += 4
        if (obs.hasAss) b += 2
        if (obs.hasSoftsubTrack) b += 1
        return b
    }

    return compareByDescending<StreamCandidate> { memoryBoost(it) }
        .thenByDescending { dualHint.containsMatchIn(releaseBlob(it)) }
        .thenByDescending { softHint.containsMatchIn(releaseBlob(it)) }
        .thenByDescending { it.isCuratedSeaDexSource() }
        .thenByDescending {
            it.cacheState == StreamCacheState.CACHED || it.cacheState == StreamCacheState.NOT_APPLICABLE
        }
        .thenByDescending { it.seeders ?: 0 }
        .thenByDescending { autoPickQualityHint(it.title) }
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
