package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.watchstate.WatchStatePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Rewrite-owned playback checkpoints. This deliberately stores only presentation-neutral state;
 * the retained player remains the source of decoder and playback-engine behavior.
 */
data class RewritePlaybackCheckpoint(
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
) {
    val progressFraction: Double?
        get() = durationMs.takeIf { it > 0 }?.let { positionMs.toDouble() / it }
}

interface RewritePlaybackProgressRepository {
    val checkpoints: Flow<List<RewritePlaybackCheckpoint>>
    suspend fun save(checkpoint: RewritePlaybackCheckpoint)
    suspend fun remove(contentId: String)
}

private fun rewritePlaybackProgressKey(profileId: String) = stringPreferencesKey("rewrite_playback_progress_v2_$profileId")
private const val MAX_STORED_CHECKPOINTS = 100

class RewritePlaybackProgressStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: RewriteProfileRepository = DefaultRewritePlaybackProfileRepository,
) : RewritePlaybackProgressRepository {
    override val checkpoints: Flow<List<RewritePlaybackCheckpoint>> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        preferences[rewritePlaybackProgressKey(profileState.activeProfile.id)]
            ?.let(RewritePlaybackProgressCodec::decode)
            .orEmpty()
            .asSequence()
            .filter { WatchStatePolicy.isStartedButIncomplete(it.progressFraction) }
            .sortedByDescending(RewritePlaybackCheckpoint::updatedAtEpochMs)
            .toList()
    }

    override suspend fun save(checkpoint: RewritePlaybackCheckpoint) {
        if (!checkpoint.isPersistable()) return
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            val key = rewritePlaybackProgressKey(profileId)
            val existing = preferences[key]
                ?.let(RewritePlaybackProgressCodec::decode)
                .orEmpty()
                .associateBy(RewritePlaybackCheckpoint::contentId)
                .toMutableMap()
            if (WatchStatePolicy.isCompleted(checkpoint.progressFraction)) {
                existing.remove(checkpoint.contentId)
            } else {
                existing[checkpoint.contentId] = checkpoint
            }
            val retained = existing.values
                .asSequence()
                .filter(RewritePlaybackCheckpoint::isPersistable)
                .sortedByDescending(RewritePlaybackCheckpoint::updatedAtEpochMs)
                .take(MAX_STORED_CHECKPOINTS)
                .toList()
            preferences[key] = RewritePlaybackProgressCodec.encode(retained)
        }
    }

    override suspend fun remove(contentId: String) {
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            val key = rewritePlaybackProgressKey(profileId)
            val remaining = preferences[key]
                ?.let(RewritePlaybackProgressCodec::decode)
                .orEmpty()
                .filterNot { it.contentId == contentId }
            preferences[key] = RewritePlaybackProgressCodec.encode(remaining)
        }
    }
}

private object DefaultRewritePlaybackProfileRepository : RewriteProfileRepository {
    override val state = flowOf(RewriteProfileState())
    override suspend fun select(profileId: String) = Unit
    override suspend fun create(name: String) = RewriteProfile(RewriteProfileState.DefaultProfileId, "Viewer")
    override suspend fun rename(profileId: String, name: String) = Unit
    override suspend fun remove(profileId: String) = Unit
    override suspend fun setRememberLastProfile(enabled: Boolean) = Unit
    override suspend fun migrateIfDefault(profiles: List<PriorProfileSnapshot>, activeProfileId: String, rememberLastProfile: Boolean) = Unit
}

object RewritePlaybackProgressCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(checkpoints: List<RewritePlaybackCheckpoint>): String = json.encodeToString(
        StoredPlaybackCheckpoints(checkpoints.map(StoredPlaybackCheckpoint::from)),
    )

    fun decode(raw: String): List<RewritePlaybackCheckpoint> = runCatching {
        json.decodeFromString<StoredPlaybackCheckpoints>(raw).items
            .asSequence()
            .map(StoredPlaybackCheckpoint::toModel)
            .filter(RewritePlaybackCheckpoint::isPersistable)
            .sortedByDescending(RewritePlaybackCheckpoint::updatedAtEpochMs)
            .take(MAX_STORED_CHECKPOINTS)
            .toList()
    }.getOrDefault(emptyList())
}

private fun RewritePlaybackCheckpoint.isPersistable(): Boolean =
    contentId.isNotBlank() &&
        contentType.isNotBlank() &&
        title.isNotBlank() &&
        positionMs >= 0L &&
        durationMs > 0L &&
        updatedAtEpochMs >= 0L

@Serializable
private data class StoredPlaybackCheckpoints(val items: List<StoredPlaybackCheckpoint> = emptyList())

@Serializable
private data class StoredPlaybackCheckpoint(
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
) {
    fun toModel() = RewritePlaybackCheckpoint(
        contentId, contentType, title, posterUrl, positionMs, durationMs, updatedAtEpochMs,
    )

    companion object {
        fun from(model: RewritePlaybackCheckpoint) = StoredPlaybackCheckpoint(
            model.contentId,
            model.contentType,
            model.title,
            model.posterUrl,
            model.positionMs,
            model.durationMs,
            model.updatedAtEpochMs,
        )
    }
}
