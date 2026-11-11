package com.sluggyard.tv.ui.app

import android.util.Log
import com.sluggyard.tv.core.watchstate.DetailsEpisode
import com.sluggyard.tv.core.watchstate.DetailsWatchPolicy
import com.sluggyard.tv.domain.model.WatchProgress
import com.sluggyard.tv.domain.repository.WatchProgressRepository
import com.sluggyard.tv.ui.app.data.LibraryWatchRepository
import com.sluggyard.tv.ui.app.data.WatchedMark

private const val TAG = "DetailsWatch"

/** Series identity the Details screen persists watched episodes under. */
data class DetailsWatchedShow(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
)

/**
 * Persists a Details "mark watched" action. Only the episodes that actually flipped from
 * unwatched are written (re-writing the already-watched ones used to cost a Trakt history
 * entry, an outbox row and a cloud sync each), earlier episodes go as one batch, and
 * [lastEpisodeId] is written last so it carries the newest lastWatched for Continue Watching.
 *
 * A failure on the remote/progress side never stops the local watched marks: those are written
 * first, in one batch, so the checkmarks the viewer just saw always stick.
 */
suspend fun persistNewlyWatchedEpisodes(
    show: DetailsWatchedShow,
    before: List<DetailsEpisode>,
    after: List<DetailsEpisode>,
    lastEpisodeId: String?,
    libraryWatch: LibraryWatchRepository,
    progress: WatchProgressRepository,
) {
    val flipped = DetailsWatchPolicy.newlyWatched(before, after)
    if (flipped.isEmpty()) return

    runCatching {
        libraryWatch.setWatchedBatch(
            flipped.map { episode ->
                WatchedMark(
                    id = episode.id,
                    contentType = "series",
                    title = show.title,
                    season = episode.season,
                    episode = episode.episode,
                )
            },
            watched = true,
        )
    }.onFailure { Log.w(TAG, "library watched batch failed show=${show.id}", it) }

    val earlier = flipped.filter { it.id != lastEpisodeId }
    val last = flipped.firstOrNull { it.id == lastEpisodeId }

    if (earlier.isNotEmpty()) {
        runCatching { progress.markAsCompletedBatch(earlier.map { show.toCompletedProgress(it) }) }
            .onFailure { Log.w(TAG, "earlier-episode batch failed show=${show.id} count=${earlier.size}", it) }
    }
    if (last != null) {
        runCatching { progress.markAsCompleted(show.toCompletedProgress(last), syncRemoteToTrakt = true) }
            .onFailure { Log.w(TAG, "mark watched failed show=${show.id} episode=${last.id}", it) }
    }
}

private fun DetailsWatchedShow.toCompletedProgress(episode: DetailsEpisode) = WatchProgress(
    contentId = id,
    contentType = "series",
    name = title,
    poster = posterUrl,
    backdrop = backdropUrl,
    logo = null,
    videoId = episode.id,
    season = episode.season,
    episode = episode.episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = System.currentTimeMillis(),
    progressPercent = 100f,
)
