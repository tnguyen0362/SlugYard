package com.sluggyard.tv.core.watchstate

import java.time.LocalDate

const val STARTED_PROGRESS_FRACTION = 0.02
const val COMPLETED_PROGRESS_FRACTION = 0.90
const val PASSIVE_COMPLETION_SEED_FRACTION = 0.95

/** Input needed to decide whether a movie belongs in Continue Watching. */
data class MovieResumeEvidence(
    val savedPositionMs: Long = 0,
    val durationMs: Long? = null,
    val explicitRemoteProgressFraction: Double? = null,
    val passiveRemoteHistoryOnly: Boolean = false,
    val manuallyMarkedWatched: Boolean = false,
)

/** A deliberately small, shared description of an episode's availability. */
data class EpisodeAvailability(
    val season: Int?,
    val episode: Int?,
    val releaseDate: LocalDate? = null,
    val explicitlyUnavailable: Boolean = false,
)

/**
 * Clean-room watch-state rules shared by Home, Details, badges, and Trakt reconciliation.
 *
 * Release dates are date-only values by the time they reach this policy. Callers must parse
 * dates in the device timezone; an absent or unparseable date is represented by null and remains
 * watchable, exactly as the product behavior requires.
 */
object WatchStatePolicy {
    fun isStartedButIncomplete(progressFraction: Double?): Boolean =
        progressFraction != null &&
            progressFraction >= STARTED_PROGRESS_FRACTION &&
            progressFraction < COMPLETED_PROGRESS_FRACTION

    fun isCompleted(progressFraction: Double?): Boolean =
        progressFraction != null && progressFraction >= COMPLETED_PROGRESS_FRACTION

    fun isTrustedPassiveCompletionSeed(progressFraction: Double?): Boolean =
        progressFraction != null && progressFraction >= PASSIVE_COMPLETION_SEED_FRACTION

    fun shouldResumeMovie(evidence: MovieResumeEvidence): Boolean {
        if (evidence.manuallyMarkedWatched) return false

        val localProgress = evidence.durationMs
            ?.takeIf { it > 0 }
            ?.let { evidence.savedPositionMs.toDouble() / it }
        val knownProgress = evidence.explicitRemoteProgressFraction ?: localProgress
        if (isStartedButIncomplete(knownProgress)) return true
        if (isCompleted(knownProgress)) return false

        // A direct local position or explicit remote in-progress value may be resumed even when a
        // duration is unavailable. Passive watch-history alone never manufactures a resume item.
        return !evidence.passiveRemoteHistoryOnly &&
            (evidence.savedPositionMs > 0 || (evidence.explicitRemoteProgressFraction ?: 0.0) > 0.0)
    }

    /**
     * Removes every episode in a season when its first numbered episode is unavailable or has a
     * future calendar date. This is a season-level cutoff, not a per-episode filter.
     */
    fun watchableEpisodes(
        episodes: List<EpisodeAvailability>,
        today: LocalDate,
    ): List<EpisodeAvailability> {
        val blockedSeasons = episodes
            .filter { it.season != null && it.episode != null }
            .groupBy { it.season!! }
            .filterValues { seasonEpisodes ->
                val first = seasonEpisodes.minByOrNull { it.episode!! } ?: return@filterValues false
                first.explicitlyUnavailable || first.releaseDate?.isAfter(today) == true
            }
            .keys

        return episodes.filter { episode -> episode.season !in blockedSeasons }
    }
}
