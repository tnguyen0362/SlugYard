package com.sluggyard.tv.core.player

import com.sluggyard.tv.domain.model.WatchProgress
import com.sluggyard.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.flow.Flow

/** Playback-only progress surface exposed to the retained player runtime. */
interface PlaybackProgressSink {
    suspend fun isTraktProgressActive(): Boolean
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?>
    fun getProgress(contentId: String): Flow<WatchProgress?>
    suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean)
    suspend fun markAsCompleted(progress: WatchProgress, syncRemoteToTrakt: Boolean)
}

internal class RepositoryPlaybackProgressSink(
    private val repository: WatchProgressRepository,
) : PlaybackProgressSink {
    override suspend fun isTraktProgressActive(): Boolean = repository.isTraktProgressActive()

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        repository.getAllEpisodeProgress(contentId)

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> =
        repository.getEpisodeProgress(contentId, season, episode)

    override fun getProgress(contentId: String): Flow<WatchProgress?> = repository.getProgress(contentId)

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) =
        repository.saveProgress(progress, syncRemote)

    override suspend fun markAsCompleted(progress: WatchProgress, syncRemoteToTrakt: Boolean) =
        repository.markAsCompleted(progress, syncRemoteToTrakt)
}
