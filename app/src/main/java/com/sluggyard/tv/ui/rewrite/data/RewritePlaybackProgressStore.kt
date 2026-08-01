package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.data.repository.StreamMergeUtils
import com.sluggyard.tv.core.sync.RewriteProgressSyncBridge
import com.sluggyard.tv.core.sync.model.CloudWatchProgress
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
    val addonId: String? = null,
    val parentId: String? = null,
    val parentType: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val contentGenres: String? = null,
    val contentLanguage: String? = null,
    /**
     * Explicit 0..1 progress for Trakt (and similar) entries that only have a percent.
     * Not persisted — display-only for Home Continue Watching.
     */
    val remoteProgressFraction: Double? = null,
) {
    val progressFraction: Double?
        get() = remoteProgressFraction?.coerceIn(0.0, 1.0)
            ?: durationMs.takeIf { it > 0 }?.let { positionMs.toDouble() / it }

    /** Continue Watching membership: percentage threshold or the absolute ~10s floor. */
    val isResumable: Boolean
        get() = remoteProgressFraction?.let { it in 0.02..0.90 }
            ?: WatchStatePolicy.isStartedButIncomplete(positionMs, durationMs)
}

/** Keep one Continue Watching tile per series while retaining the newest episode checkpoint. */
internal fun groupRewritePlaybackCheckpoints(
    checkpoints: List<RewritePlaybackCheckpoint>,
): List<RewritePlaybackCheckpoint> = checkpoints
    .groupBy { checkpoint ->
        val isEpisode = checkpoint.season != null ||
            checkpoint.episode != null ||
            checkpoint.parentType.equals("series", ignoreCase = true)
        if (!isEpisode) {
            checkpoint.contentId
        } else {
            checkpoint.parentId?.takeIf { it.isNotBlank() }
                ?: StreamMergeUtils.deriveInlineMetaId(checkpoint.contentId)
        }
    }
    .values
    .mapNotNull { group ->
        group.maxWithOrNull(
            compareBy<RewritePlaybackCheckpoint> { it.updatedAtEpochMs }
                .thenBy { it.season ?: -1 }
                .thenBy { it.episode ?: -1 }
                .thenBy { it.contentId },
        )
    }
    .sortedByDescending(RewritePlaybackCheckpoint::updatedAtEpochMs)

interface RewritePlaybackProgressRepository {
    val checkpoints: Flow<List<RewritePlaybackCheckpoint>>
    suspend fun save(checkpoint: RewritePlaybackCheckpoint)
    suspend fun remove(contentId: String, season: Int? = null, episode: Int? = null)
}

private fun rewritePlaybackProgressKey(profileId: String) = stringPreferencesKey("rewrite_playback_progress_v2_$profileId")
private const val MAX_STORED_CHECKPOINTS = 100

class RewritePlaybackProgressStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: RewriteProfileRepository = DefaultRewritePlaybackProfileRepository,
    private val syncBridge: RewriteProgressSyncBridge? = null,
) : RewritePlaybackProgressRepository {
    override val checkpoints: Flow<List<RewritePlaybackCheckpoint>> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        preferences[rewritePlaybackProgressKey(profileState.activeProfile.id)]
            ?.let(RewritePlaybackProgressCodec::decode)
            .orEmpty()
            .asSequence()
            .filter(RewritePlaybackCheckpoint::isResumable)
            .distinctBy { rewriteProgressKey(it.contentId, it.season, it.episode) }
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
                .associateBy { rewriteProgressKey(it.contentId, it.season, it.episode) }
                .toMutableMap()
            val checkpointKey = rewriteProgressKey(checkpoint.contentId, checkpoint.season, checkpoint.episode)
            if (!checkpoint.isResumable) {
                existing.remove(checkpointKey)
            } else {
                existing[checkpointKey] = checkpoint
            }
            val retained = existing.values
                .asSequence()
                .filter(RewritePlaybackCheckpoint::isPersistable)
                .sortedByDescending(RewritePlaybackCheckpoint::updatedAtEpochMs)
                .take(MAX_STORED_CHECKPOINTS)
                .toList()
            preferences[key] = RewritePlaybackProgressCodec.encode(retained)
        }
        val numericProfileId = cloudLinkedProfileIdOrNull(profileId) ?: return
        if (!checkpoint.isResumable) {
            syncBridge?.remove(
                profileId = numericProfileId,
                progressKey = rewriteProgressKey(checkpoint.contentId, checkpoint.season, checkpoint.episode),
                changedAtEpochMs = checkpoint.updatedAtEpochMs,
            )
        } else {
            syncBridge?.record(checkpoint.toCloudProgress(numericProfileId))
        }
    }

    override suspend fun remove(contentId: String, season: Int?, episode: Int?) {
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            val key = rewritePlaybackProgressKey(profileId)
            val remaining = preferences[key]
                ?.let(RewritePlaybackProgressCodec::decode)
                .orEmpty()
                .filterNot {
                    it.contentId == contentId &&
                        (season == null || it.season == season) &&
                        (episode == null || it.episode == episode)
                }
            preferences[key] = RewritePlaybackProgressCodec.encode(remaining)
        }
        syncBridge?.remove(
            profileId = cloudLinkedProfileIdOrNull(profileId) ?: return,
            progressKey = rewriteProgressKey(contentId, season, episode),
            changedAtEpochMs = System.currentTimeMillis(),
        )
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

    fun decode(raw: String): List<RewritePlaybackCheckpoint> = decodeOrNull(raw).orEmpty()
    fun decodeOrNull(raw: String): List<RewritePlaybackCheckpoint>? = runCatching {
        json.decodeFromString<StoredPlaybackCheckpoints>(raw).items
            .asSequence()
            .map(StoredPlaybackCheckpoint::toModel)
            .filter(RewritePlaybackCheckpoint::isPersistable)
            .sortedByDescending(RewritePlaybackCheckpoint::updatedAtEpochMs)
            .take(MAX_STORED_CHECKPOINTS)
            .toList()
    }.getOrNull()
}

private fun RewritePlaybackCheckpoint.isPersistable(): Boolean =
    contentId.isNotBlank() &&
        contentType.isNotBlank() &&
        title.isNotBlank() &&
        positionMs >= 0L &&
        durationMs > 0L &&
        updatedAtEpochMs >= 0L

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

@Serializable
private data class StoredPlaybackCheckpoints(val items: List<StoredPlaybackCheckpoint> = emptyList())

@Serializable
private data class StoredPlaybackCheckpoint(
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String? = null,
    val addonId: String? = null,
    val parentId: String? = null,
    val parentType: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtEpochMs: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val contentGenres: String? = null,
    val contentLanguage: String? = null,
) {
    fun toModel() = RewritePlaybackCheckpoint(
        contentId = contentId,
        contentType = contentType,
        title = title,
        posterUrl = posterUrl,
        addonId = addonId,
        parentId = parentId,
        parentType = parentType,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAtEpochMs = updatedAtEpochMs,
        season = season,
        episode = episode,
        contentGenres = contentGenres,
        contentLanguage = contentLanguage,
    )

    companion object {
        fun from(model: RewritePlaybackCheckpoint) = StoredPlaybackCheckpoint(
            contentId = model.contentId,
            contentType = model.contentType,
            title = model.title,
            posterUrl = model.posterUrl,
            addonId = model.addonId,
            parentId = model.parentId,
            parentType = model.parentType,
            positionMs = model.positionMs,
            durationMs = model.durationMs,
            updatedAtEpochMs = model.updatedAtEpochMs,
            season = model.season,
            episode = model.episode,
            contentGenres = model.contentGenres,
            contentLanguage = model.contentLanguage,
        )
    }
}
