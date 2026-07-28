package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.sync.RewriteProgressSyncBridge
import com.sluggyard.tv.core.sync.SyncMutationRecorder
import com.sluggyard.tv.core.sync.model.CloudWatchProgress
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.remote.SyncMutation
import com.sluggyard.tv.core.watchstate.WatchStatePolicy
import kotlinx.coroutines.flow.first

private const val PLAYBACK_PROGRESS_KEY_PREFIX = "rewrite_playback_progress_v2_"

/**
 * Connects the Rewrite progress DataStore to the account-bound Supabase outbox.
 *
 * The bridge deliberately keeps title/poster metadata local because the sync schema only stores
 * playback identity and timing. Existing local metadata is retained when a remote record is
 * applied; a remote-only record uses its content ID as a safe display title.
 */
class DataStoreRewritePlaybackProgressSyncBridge(
    private val dataStore: DataStore<Preferences>,
    private val mutationRecorder: SyncMutationRecorder,
) : RewriteProgressSyncBridge {
    override suspend fun snapshot(): List<CloudWatchProgress> = dataStore.data.first()
        .asMap()
        .mapNotNull { (key, value) ->
            val profileId = key.name.removePrefix(PLAYBACK_PROGRESS_KEY_PREFIX).toIntOrNull()
                ?: return@mapNotNull null
            val encoded = value as? String ?: return@mapNotNull null
            RewritePlaybackProgressCodec.decode(encoded)
                .filter { WatchStatePolicy.isStartedButIncomplete(it.progressFraction) }
                .map { it.toCloudProgress(profileId) }
        }
        .flatten()

    override suspend fun apply(progress: List<CloudWatchProgress>) {
        val existing = localCheckpoints().associateBy {
            it.first to rewriteProgressKey(it.second.contentId, it.second.season, it.second.episode)
        }
        val grouped = progress
            .filter {
                it.duration > 0L &&
                    WatchStatePolicy.isStartedButIncomplete(it.position.toDouble() / it.duration)
            }
            .groupBy(CloudWatchProgress::profileId)

        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(PLAYBACK_PROGRESS_KEY_PREFIX) }
                .forEach { preferences.remove(stringPreferencesKey(it.name)) }

            grouped.forEach { (profileId, records) ->
                val checkpoints = records
                    .distinctBy(CloudWatchProgress::progressKey)
                    .map { remote ->
                        val local = existing[remote.profileId to remote.progressKey]
                        RewritePlaybackCheckpoint(
                            contentId = remote.contentId,
                            contentType = remote.contentType.ifBlank { "movie" },
                            title = local?.second?.title ?: remote.contentId,
                            posterUrl = local?.second?.posterUrl,
                            positionMs = remote.position,
                            durationMs = remote.duration,
                            updatedAtEpochMs = remote.lastWatched,
                            season = remote.season,
                            episode = remote.episode,
                        )
                    }
                    .filter(RewritePlaybackCheckpoint::isSyncPersistable)
                if (checkpoints.isNotEmpty()) {
                    preferences[stringPreferencesKey(progressKey(profileId))] =
                        RewritePlaybackProgressCodec.encode(checkpoints)
                }
            }
        }
    }

    override suspend fun record(progress: CloudWatchProgress) {
        mutationRecorder.record(SyncMutation.Progress(progress))
    }

    override suspend fun remove(profileId: Int, progressKey: String, changedAtEpochMs: Long) {
        mutationRecorder.recordDelete(
            domain = SyncDomain.WATCH_PROGRESS,
            profileId = profileId,
            recordKey = progressKey,
            changedAtEpochMs = changedAtEpochMs,
        )
    }

    private suspend fun localCheckpoints(): List<Pair<Int, RewritePlaybackCheckpoint>> = dataStore.data.first()
        .asMap()
        .mapNotNull { (key, value) ->
            val profileId = key.name.removePrefix(PLAYBACK_PROGRESS_KEY_PREFIX).toIntOrNull()
                ?: return@mapNotNull null
            val encoded = value as? String ?: return@mapNotNull null
            RewritePlaybackProgressCodec.decode(encoded).map { profileId to it }
        }
        .flatten()
}

private fun progressKey(profileId: Int): String = "$PLAYBACK_PROGRESS_KEY_PREFIX$profileId"

internal fun rewriteProgressKey(contentId: String, season: Int?, episode: Int?): String = buildString {
    append(contentId)
    if (season != null || episode != null) {
        append("|s").append(season ?: 0)
        append("e").append(episode ?: 0)
    }
}

private fun RewritePlaybackCheckpoint.toCloudProgress(profileId: Int) = CloudWatchProgress(
    profileId = profileId,
    progressKey = rewriteProgressKey(contentId, season, episode),
    contentId = contentId,
    contentType = contentType,
    videoId = contentId,
    season = season,
    episode = episode,
    position = positionMs,
    duration = durationMs,
    lastWatched = updatedAtEpochMs,
    changedAt = updatedAtEpochMs,
)

private fun RewritePlaybackCheckpoint.isSyncPersistable(): Boolean =
    contentId.isNotBlank() &&
        contentType.isNotBlank() &&
        positionMs > 0L &&
        durationMs > 0L &&
        WatchStatePolicy.isStartedButIncomplete(progressFraction)
