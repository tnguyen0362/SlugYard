package com.sluggyard.tv.core.watchstate

import java.time.LocalDate

data class DetailsEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val watched: Boolean,
    val releaseDate: LocalDate? = null,
    val explicitlyUnavailable: Boolean = false,
)

/**
 * Watch-state actions for the rewritten Details screen.
 *
 * The old behavior differed between a Details season indicator and Home. The rewrite chooses one
 * coherent rule: only episodes in watchable seasons count toward a season-complete indicator,
 * matching the Home/hero/Continue Watching cutoff and avoiding an impossible-to-complete season
 * while a future episode has not aired.
 */
object DetailsWatchPolicy {
    /**
     * Marking an episode watched means "I am caught up to here": every earlier episode in its
     * season and every episode of the seasons before it are marked too. Specials (season 0) sit
     * outside the main run, so they only cascade among themselves.
     */
    fun markEpisodeWatched(
        episodes: List<DetailsEpisode>,
        targetEpisodeId: String,
    ): List<DetailsEpisode> {
        val target = episodes.firstOrNull { it.id == targetEpisodeId } ?: return episodes
        return episodes.map { episode ->
            if (isCaughtUpThrough(episode, target)) episode.copy(watched = true) else episode
        }
    }

    /** Marking a season complete also marks all prior seasons complete as a catch-up action. */
    fun markSeasonWatched(
        episodes: List<DetailsEpisode>,
        targetSeason: Int,
    ): List<DetailsEpisode> = episodes.map { episode ->
        val inRange = if (targetSeason == 0) {
            episode.season == 0
        } else {
            episode.season in 1..targetSeason
        }
        if (inRange) episode.copy(watched = true) else episode
    }

    /** Episodes whose [DetailsEpisode.watched] flipped between [before] and [after]. */
    fun newlyWatched(
        before: List<DetailsEpisode>,
        after: List<DetailsEpisode>,
    ): List<DetailsEpisode> {
        val wasWatched = before.filter(DetailsEpisode::watched).mapTo(HashSet()) { it.id }
        return after.filter { it.watched && it.id !in wasWatched }
    }

    private fun isCaughtUpThrough(episode: DetailsEpisode, target: DetailsEpisode): Boolean = when {
        target.season == 0 -> episode.season == 0 && episode.episode <= target.episode
        episode.season == 0 -> false
        else -> episode.season < target.season ||
            (episode.season == target.season && episode.episode <= target.episode)
    }

    fun isSeasonFullyWatched(
        episodes: List<DetailsEpisode>,
        season: Int,
        today: LocalDate,
    ): Boolean {
        val watchable = WatchStatePolicy.watchableEpisodes(
            episodes.map {
                EpisodeAvailability(
                    season = it.season,
                    episode = it.episode,
                    releaseDate = it.releaseDate,
                    explicitlyUnavailable = it.explicitlyUnavailable,
                )
            },
            today,
        )
        val watchableIds = watchable.mapTo(HashSet()) { availability ->
            episodes.first { it.season == availability.season && it.episode == availability.episode }.id
        }
        val relevant = episodes.filter { it.season == season && it.id in watchableIds }
        return relevant.isNotEmpty() && relevant.all(DetailsEpisode::watched)
    }
}
