package com.sluggyard.tv.core.watchstate

enum class ProgressOrigin { LOCAL, TRAKT, PASSIVE_TRAKT }

data class ContinueWatchingEntry(
    val contentId: String,
    val contentType: String,
    val progressFraction: Double? = null,
    val savedPositionMs: Long = 0,
    val durationMs: Long? = null,
    val lastActivityMs: Long,
    val origin: ProgressOrigin,
    /** False for IDs Trakt cannot represent (for example Kitsu/MAL/AniList source IDs). */
    val traktRepresentable: Boolean = true,
    val manuallyMarkedWatched: Boolean = false,
)

data class EpisodeResumeSeed(
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val watchedAtMs: Long,
    val progressFraction: Double? = null,
    val origin: ProgressOrigin,
)

/**
 * Deterministic local/Trakt reconciliation. Network refresh and persistence are deliberately
 * outside this policy so a late remote result cannot cause a Compose-specific state race.
 */
object ContinueWatchingMergePolicy {
    fun mergeEntries(local: List<ContinueWatchingEntry>, trakt: List<ContinueWatchingEntry>, traktActive: Boolean): List<ContinueWatchingEntry> {
        if (!traktActive) return resumable(local).sortedByDescending(ContinueWatchingEntry::lastActivityMs)
        val remoteById = trakt.associateBy(ContinueWatchingEntry::contentId)
        return buildList {
            addAll(resumable(trakt))
            addAll(resumable(local).filter { !it.traktRepresentable && it.contentId !in remoteById })
        }.sortedByDescending(ContinueWatchingEntry::lastActivityMs)
    }

    fun selectEpisodeSeed(candidates: List<EpisodeResumeSeed>, resumeFromFurthest: Boolean): EpisodeResumeSeed? =
        candidates.filter(::trustedSeed).reduceOrNull { current, candidate ->
            when {
                resumeFromFurthest && episodeOrder(candidate) > episodeOrder(current) -> candidate
                resumeFromFurthest && episodeOrder(candidate) < episodeOrder(current) -> current
                candidate.watchedAtMs > current.watchedAtMs -> candidate
                else -> current
            }
        }

    fun isOptimisticSeedActive(completedAtMs: Long, nowMs: Long): Boolean =
        nowMs - completedAtMs in 0..(3 * 60_000L)

    private fun resumable(entries: List<ContinueWatchingEntry>): List<ContinueWatchingEntry> = entries.filter { entry ->
        WatchStatePolicy.shouldResumeMovie(
            MovieResumeEvidence(
                savedPositionMs = entry.savedPositionMs,
                durationMs = entry.durationMs,
                explicitRemoteProgressFraction = entry.progressFraction.takeIf { entry.origin != ProgressOrigin.PASSIVE_TRAKT },
                passiveRemoteHistoryOnly = entry.origin == ProgressOrigin.PASSIVE_TRAKT && entry.progressFraction == null,
                manuallyMarkedWatched = entry.manuallyMarkedWatched,
            ),
        )
    }

    private fun trustedSeed(seed: EpisodeResumeSeed): Boolean =
        seed.origin != ProgressOrigin.PASSIVE_TRAKT || WatchStatePolicy.isTrustedPassiveCompletionSeed(seed.progressFraction)

    private fun episodeOrder(seed: EpisodeResumeSeed): Long =
        (seed.season.toLong() shl 32) + seed.episode.toLong()
}
