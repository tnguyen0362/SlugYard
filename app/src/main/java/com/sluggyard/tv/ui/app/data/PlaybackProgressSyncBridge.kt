package com.sluggyard.tv.ui.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.sync.ProgressSyncBridge
import com.sluggyard.tv.core.sync.SyncMutationRecorder
import com.sluggyard.tv.core.sync.model.CloudWatchProgress
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.remote.SyncMutation
import com.sluggyard.tv.core.watchstate.WatchStatePolicy
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PLAYBACK_PROGRESS_KEY_PREFIX = "app_playback_progress_v2_"
private const val PENDING_PROGRESS_KEY = "app_pending_progress_sync_v1"

/**
 * Connects the Rewrite progress DataStore to the account-bound Supabase outbox.
 *
 * The bridge deliberately keeps title/poster metadata local because the sync schema only stores
 * playback identity and timing. Existing local metadata is retained when a remote record is
 * applied; a remote-only record uses its content ID as a safe display title.
 */
class DataStorePlaybackProgressSyncBridge(
    private val dataStore: DataStore<Preferences>,
    private val mutationRecorder: SyncMutationRecorder,
) : ProgressSyncBridge {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun snapshot(): List<CloudWatchProgress> = dataStore.data.first()
        .asMap()
        .mapNotNull { (key, value) ->
            val profileId = key.name.removePrefix(PLAYBACK_PROGRESS_KEY_PREFIX).toIntOrNull()
                ?: return@mapNotNull null
            val encoded = value as? String ?: return@mapNotNull null
            PlaybackProgressCodec.decode(encoded)
                .filter(PlaybackCheckpoint::isResumable)
                .map { it.toCloudProgress(profileId) }
        }
        .flatten()

    override suspend fun apply(progress: List<CloudWatchProgress>) {
        val existing = localCheckpoints().associateBy {
            it.first to progressKey(it.second.contentId, it.second.season, it.second.episode)
        }
        val grouped = progress
            .filter { WatchStatePolicy.isStartedButIncomplete(it.position, it.duration) }
            .groupBy(CloudWatchProgress::profileId)

        dataStore.edit { preferences ->
            val liveLocal = preferences.asMap()
                .mapNotNull { (key, value) ->
                    val profileId = key.name.removePrefix(PLAYBACK_PROGRESS_KEY_PREFIX).toIntOrNull()
                        ?: return@mapNotNull null
                    val raw = value as? String ?: return@mapNotNull null
                    profileId to PlaybackProgressCodec.decode(raw)
                }
                .toMap()
            preferences.asMap().keys
                .filter {
                    it.name.startsWith(PLAYBACK_PROGRESS_KEY_PREFIX) &&
                        it.name.removePrefix(PLAYBACK_PROGRESS_KEY_PREFIX).toIntOrNull() != null
                }
                .forEach { preferences.remove(stringPreferencesKey(it.name)) }

            grouped.forEach { (profileId, records) ->
                val remoteByKey = records
                    .distinctBy(CloudWatchProgress::progressKey)
                    .associateBy(CloudWatchProgress::progressKey)
                val checkpoints = remoteByKey.values
                    .map { remote ->
                        val local = existing[remote.profileId to remote.progressKey]
                        PlaybackCheckpoint(
                            contentId = remote.contentId,
                            contentType = remote.contentType.ifBlank { "movie" },
                            title = local?.second?.title ?: remote.contentId,
                            posterUrl = local?.second?.posterUrl,
                            addonId = local?.second?.addonId,
                            parentId = local?.second?.parentId,
                            parentType = local?.second?.parentType,
                            positionMs = remote.position,
                            durationMs = remote.duration,
                            updatedAtEpochMs = remote.lastWatched,
                            season = remote.season,
                            episode = remote.episode,
                            contentGenres = local?.second?.contentGenres,
                            contentLanguage = local?.second?.contentLanguage,
                        )
                    }
                    .plus(
                        liveLocal[profileId].orEmpty().filter { local ->
                            val remote = remoteByKey[progressKey(local.contentId, local.season, local.episode)]
                            remote == null || local.updatedAtEpochMs > remote.lastWatched
                        },
                    )
                    .groupBy { progressKey(it.contentId, it.season, it.episode) }
                    .values
                    .map { candidates -> candidates.maxBy(PlaybackCheckpoint::updatedAtEpochMs) }
                    .filter(PlaybackCheckpoint::isSyncPersistable)
                if (checkpoints.isNotEmpty()) {
                    preferences[stringPreferencesKey(progressKey(profileId))] =
                        PlaybackProgressCodec.encode(checkpoints)
                }
            }
        }
    }

    override suspend fun record(progress: CloudWatchProgress) {
        when (mutationRecorder.record(SyncMutation.Progress(progress))) {
            is com.sluggyard.tv.core.sync.auth.SyncResult.Success -> removePending(progress.profileId, progress.progressKey)
            else -> addPending(progress)
        }
    }

    override suspend fun flushPending() {
        val pending = dataStore.data.first()[stringPreferencesKey(PENDING_PROGRESS_KEY)]
            ?.let { raw -> runCatching { json.decodeFromString<List<PendingProgress>>(raw) }.getOrDefault(emptyList()) }
            .orEmpty()
        pending.forEach { item ->
            val progress = item.toCloudProgress()
            if (mutationRecorder.record(SyncMutation.Progress(progress)) is com.sluggyard.tv.core.sync.auth.SyncResult.Success) {
                removePending(progress.profileId, progress.progressKey)
            }
        }
    }

    override suspend fun remove(profileId: Int, progressKey: String, changedAtEpochMs: Long) {
        removePending(profileId, progressKey)
        mutationRecorder.recordDelete(
            domain = SyncDomain.WATCH_PROGRESS,
            profileId = profileId,
            recordKey = progressKey,
            changedAtEpochMs = changedAtEpochMs,
        )
    }

    private suspend fun localCheckpoints(): List<Pair<Int, PlaybackCheckpoint>> = dataStore.data.first()
        .asMap()
        .mapNotNull { (key, value) ->
            val profileId = key.name.removePrefix(PLAYBACK_PROGRESS_KEY_PREFIX).toIntOrNull()
                ?: return@mapNotNull null
            val encoded = value as? String ?: return@mapNotNull null
            PlaybackProgressCodec.decode(encoded).map { profileId to it }
        }
        .flatten()

    private suspend fun addPending(progress: CloudWatchProgress) {
        dataStore.edit { preferences ->
            val key = stringPreferencesKey(PENDING_PROGRESS_KEY)
            val current = preferences[key]
                ?.let { raw -> runCatching { json.decodeFromString<List<PendingProgress>>(raw) }.getOrDefault(emptyList()) }
                .orEmpty()
                .associateBy { it.profileId to it.progressKey }
                .toMutableMap()
            current[progress.profileId to progress.progressKey] = PendingProgress.from(progress)
            preferences[key] = json.encodeToString(current.values.toList())
        }
    }

    private suspend fun removePending(profileId: Int, progressKey: String) {
        dataStore.edit { preferences ->
            val key = stringPreferencesKey(PENDING_PROGRESS_KEY)
            val remaining = preferences[key]
                ?.let { raw -> runCatching { json.decodeFromString<List<PendingProgress>>(raw) }.getOrDefault(emptyList()) }
                .orEmpty()
                .filterNot { it.profileId == profileId && it.progressKey == progressKey }
            if (remaining.isEmpty()) preferences.remove(key)
            else preferences[key] = json.encodeToString(remaining)
        }
    }
}

@Serializable
private data class PendingProgress(
    val profileId: Int,
    val progressKey: String,
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val changedAt: Long,
) {
    fun toCloudProgress() = CloudWatchProgress(
        profileId, progressKey, contentId, contentType, videoId, season, episode,
        position, duration, lastWatched, changedAt,
    )

    companion object {
        fun from(value: CloudWatchProgress) = PendingProgress(
            value.profileId, value.progressKey, value.contentId, value.contentType, value.videoId,
            value.season, value.episode, value.position, value.duration, value.lastWatched, value.changedAt,
        )
    }
}

private fun progressKey(profileId: Int): String = "$PLAYBACK_PROGRESS_KEY_PREFIX$profileId"

internal fun progressKey(contentId: String, season: Int?, episode: Int?): String = buildString {
    append(contentId)
    if (season != null || episode != null) {
        append("|s").append(season ?: 0)
        append("e").append(episode ?: 0)
    }
}

private fun PlaybackCheckpoint.toCloudProgress(profileId: Int) = CloudWatchProgress(
    profileId = profileId,
    progressKey = progressKey(contentId, season, episode),
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

private fun PlaybackCheckpoint.isSyncPersistable(): Boolean =
    contentId.isNotBlank() &&
        contentType.isNotBlank() &&
        positionMs > 0L &&
        isResumable
