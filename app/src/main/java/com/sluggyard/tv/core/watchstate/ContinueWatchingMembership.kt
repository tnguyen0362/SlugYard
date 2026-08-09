package com.sluggyard.tv.core.watchstate

import java.time.LocalDate

/** A normalized mid-stream resume candidate (movie or a specific episode). */
data class CwResumeInput(
    val seriesKey: String,
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String? = null,
    val addonId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
    val remoteProgressFraction: Double? = null,
    val contentGenres: String? = null,
    val contentLanguage: String? = null,
)

/** A "last completed episode" seed that can advance to the next unwatched episode. */
data class CwNextUpSeed(
    val seriesKey: String,
    val showId: String,
    val season: Int,
    val episode: Int,
    val watchedAtMs: Long,
    val progressFraction: Double? = null,
    val origin: ProgressOrigin = ProgressOrigin.TRAKT,
    val title: String? = null,
)

/** One entry of a show's episode order; [releaseDate] gates unaired next-up when present. */
data class CwEpisodeOrderItem(
    val season: Int,
    val episode: Int,
    val releaseDate: LocalDate? = null,
)

typealias CwEpisodeOrder = Map<String, List<CwEpisodeOrderItem>>

data class CwMembershipSettings(
    val showUnaired: Boolean = true,
)

sealed interface CwRailRow {
    val seriesKey: String
    val lastActivityMs: Long
    val contentType: String
    val title: String
    val posterUrl: String?
    val addonId: String?
    val season: Int?
    val episode: Int?

    data class Resume(
        override val seriesKey: String,
        override val contentType: String,
        override val title: String,
        override val posterUrl: String?,
        override val addonId: String?,
        val contentId: String,
        override val season: Int?,
        override val episode: Int?,
        val positionMs: Long,
        val durationMs: Long,
        val progressFraction: Double?,
        override val lastActivityMs: Long,
        val contentGenres: String? = null,
        val contentLanguage: String? = null,
    ) : CwRailRow

    data class NextUp(
        override val seriesKey: String,
        val showId: String,
        override val contentType: String,
        override val title: String,
        override val posterUrl: String? = null,
        override val addonId: String? = null,
        override val season: Int?,
        override val episode: Int?,
        override val lastActivityMs: Long,
    ) : CwRailRow
}

/**
 * Pure Home Continue Watching membership. No Compose, no flows, no network: adapters normalize
 * inputs and the UI renders [CwRailRow]s. This is the single place that decides what lands on the
 * rail for both Trakt and SlugYard Sync sources.
 *
 * Rules (must be unit-tested):
 *  1. Seeds advance to the next unwatched episode via episode order. No next (or only unaired
 *     when unaired-off) => the show is dropped.
 *  2. Every true mid-stream resume is kept while started and below the 98% completion constant.
 *  3. Same series key: Resume wins over NextUp.
 *  4. Movies: resume only; complete >= 98% is gone; no next-up.
 *  5. Rows sort by last activity.
 *  6. Raw 100% history rows are never emitted as resumes.
 */
object ContinueWatchingMembership {
    fun build(
        resumeInputs: List<CwResumeInput>,
        seeds: List<CwNextUpSeed>,
        episodeOrder: CwEpisodeOrder,
        dismissedShowIds: Set<String>,
        settings: CwMembershipSettings = CwMembershipSettings(),
        today: LocalDate = LocalDate.now(),
        cap: Int = 32,
    ): List<CwRailRow> {
        val resumeByKey = resumeInputs
            .asSequence()
            .filter(::isResumable)
            .groupBy(CwResumeInput::seriesKey)
            .mapValues { (_, group) -> group.maxByOrNull(CwResumeInput::updatedAtEpochMs)!! }
        val resumeKeys = resumeByKey.keys

        val nextUpRows = seeds
            .asSequence()
            .filter(::isTrustedSeed)
            .groupBy(CwNextUpSeed::seriesKey)
            .mapValues { (_, group) -> group.maxByOrNull(CwNextUpSeed::watchedAtMs)!! }
            .values
            .filter { seed -> seed.seriesKey !in resumeKeys && seed.seriesKey !in dismissedShowIds }
            .mapNotNull { seed ->
                val order = episodeOrder[seed.showId].orEmpty()
                val next = nextEpisodeAfter(
                    order = order,
                    season = seed.season,
                    episode = seed.episode,
                    showUnaired = settings.showUnaired,
                    today = today,
                ) ?: return@mapNotNull null
                CwRailRow.NextUp(
                    seriesKey = seed.seriesKey,
                    showId = seed.showId,
                    contentType = "series",
                    title = seed.title ?: seed.seriesKey,
                    season = next.season,
                    episode = next.episode,
                    lastActivityMs = seed.watchedAtMs,
                )
            }
            .toList()

        val resumeRows = resumeByKey.values.map { input ->
            CwRailRow.Resume(
                seriesKey = input.seriesKey,
                contentType = input.contentType,
                title = input.title,
                posterUrl = input.posterUrl,
                addonId = input.addonId,
                contentId = input.contentId,
                season = input.season,
                episode = input.episode,
                positionMs = input.positionMs,
                durationMs = input.durationMs,
                progressFraction = input.remoteProgressFraction ?: input.durationMs
                    .takeIf { it > 0L }
                    ?.let { input.positionMs.toDouble() / it },
                lastActivityMs = input.updatedAtEpochMs,
                contentGenres = input.contentGenres,
                contentLanguage = input.contentLanguage,
            )
        }

        return (resumeRows + nextUpRows)
            .sortedByDescending(CwRailRow::lastActivityMs)
            .take(cap)
    }

    /**
     * The first episode strictly after the seed in [order]. If that episode is unaired and
     * [showUnaired] is off, the show is dropped rather than skipped ahead (fail-open).
     */
    fun nextEpisodeAfter(
        order: List<CwEpisodeOrderItem>,
        season: Int,
        episode: Int,
        showUnaired: Boolean,
        today: LocalDate = LocalDate.now(),
    ): CwEpisodeOrderItem? {
        val next = order.firstOrNull { item ->
            item.season > season || (item.season == season && item.episode > episode)
        } ?: return null
        val unaired = next.releaseDate != null && next.releaseDate.isAfter(today)
        return if (unaired && !showUnaired) null else next
    }

    private fun isResumable(input: CwResumeInput): Boolean {
        if (input.remoteProgressFraction != null) {
            return input.remoteProgressFraction >= STARTED_PROGRESS_FRACTION &&
                input.remoteProgressFraction < COMPLETED_PROGRESS_FRACTION
        }
        if (input.durationMs > 0L) {
            return WatchStatePolicy.isStartedButIncomplete(input.positionMs, input.durationMs)
        }
        return input.positionMs >= STARTED_MIN_POSITION_MS
    }

    /** Passive Trakt history seeds need a trusted completion fraction before they advance. */
    private fun isTrustedSeed(seed: CwNextUpSeed): Boolean =
        seed.origin != ProgressOrigin.PASSIVE_TRAKT ||
            WatchStatePolicy.isTrustedPassiveCompletionSeed(seed.progressFraction)
}
