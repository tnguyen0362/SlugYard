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
    fun markEpisodeWatched(
        episodes: List<DetailsEpisode>,
        targetEpisodeId: String,
    ): List<DetailsEpisode> {
        val target = episodes.firstOrNull { it.id == targetEpisodeId } ?: return episodes
        return episodes.map { episode ->
            if (episode.season == target.season && episode.episode <= target.episode) {
                episode.copy(watched = true)
            } else {
                episode
            }
        }
    }

    /** Marking a season complete also marks all prior seasons complete as a catch-up action. */
    fun markSeasonWatched(
        episodes: List<DetailsEpisode>,
        targetSeason: Int,
    ): List<DetailsEpisode> = episodes.map { episode ->
        if (episode.season <= targetSeason) episode.copy(watched = true) else episode
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
