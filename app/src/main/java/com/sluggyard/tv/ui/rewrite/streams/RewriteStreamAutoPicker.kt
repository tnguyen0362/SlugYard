package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState

/**
 * Bulletproof auto-pick: dedupes duplicate sources, prefers higher perceived quality within each
 * playability tier, and never lets a single malformed candidate (blank hash/url) crash the pick.
 * Tier order stays: direct URL > cached torrent > any other auto-playable torrent.
 */
internal fun selectRewriteAutoPlayCandidate(groups: List<RewriteStreamGroup>): RewriteStreamCandidate? {
    val candidates = groups
        .flatMap { group -> (group.state as? RewriteStreamGroupState.Content)?.streams.orEmpty() }
        .filter { it.isWellFormed() }
        .distinctBy { it.dedupeKey() }

    val direct = candidates.filter { !it.directUrl.isNullOrBlank() }
    if (direct.isNotEmpty()) return direct.bestByQuality()

    val cached = candidates.filter { it.cacheState == StreamCacheState.CACHED && it.hasTorrentHash() }
    if (cached.isNotEmpty()) return cached.bestByQuality()

    val playable = candidates.filter { it.isAutoPlayableTorrent() }
    if (playable.isNotEmpty()) return playable.bestByQuality()

    return null
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

private fun RewriteStreamCandidate.hasTorrentHash(): Boolean = !infoHash.isNullOrBlank()

private fun RewriteStreamCandidate.isAutoPlayableTorrent(): Boolean =
    hasTorrentHash() && cacheState != StreamCacheState.CHECKING && cacheState != StreamCacheState.NOT_CACHED

private fun List<RewriteStreamCandidate>.bestByQuality(): RewriteStreamCandidate? =
    maxByOrNull { it.qualityScore() }

/** Cheap text-based quality heuristic parsed from title/detail labels (resolution + HDR hints).
 * Never throws on unexpected text -- unmatched text simply scores 0 and falls back to original
 * list order via [maxByOrNull]'s stable-first-match behavior. */
private fun RewriteStreamCandidate.qualityScore(): Int {
    val text = "$title ${detailLabel.orEmpty()}".lowercase()
    var score = 0
    score += when {
        Regex("\\b(2160p|4k|uhd)\\b").containsMatchIn(text) -> 400
        Regex("\\b1080p\\b").containsMatchIn(text) -> 300
        Regex("\\b720p\\b").containsMatchIn(text) -> 200
        Regex("\\b(480p|sd)\\b").containsMatchIn(text) -> 50
        else -> 100 // unknown resolution: treat as mid-tier rather than worst, avoids starving valid streams
    }
    if (Regex("\\b(hdr10\\+?|dolby.?vision|dv)\\b").containsMatchIn(text)) score += 20
    if (Regex("\\bremux\\b").containsMatchIn(text)) score += 10
    if (Regex("\\bcam\\b|\\btelesync\\b|\\bts\\b|\\bhdcam\\b").containsMatchIn(text)) score -= 250
    return score
}
