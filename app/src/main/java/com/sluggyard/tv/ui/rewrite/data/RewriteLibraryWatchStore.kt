package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.sync.RewriteLibraryWatchSyncBridge
import com.sluggyard.tv.core.sync.RewriteLibraryWatchSyncSnapshot
import com.sluggyard.tv.core.sync.SyncMutationRecorder
import com.sluggyard.tv.core.sync.model.CloudLibraryItem
import com.sluggyard.tv.core.sync.model.CloudWatchedItem
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.remote.SyncMutation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class RewriteLibraryWatchState(
    val libraryIds: Set<String> = emptySet(),
    val watchedIds: Set<String> = emptySet(),
    val libraryEntries: List<RewriteLibraryEntry> = emptyList(),
)

data class RewriteLibraryEntry(
    val id: String,
    val title: String,
    val imageUrl: String? = null,
    val contentType: String? = null,
    val addonId: String? = null,
    val changedAtEpochMs: Long = 0L,
)

interface RewriteLibraryWatchRepository {
    val state: Flow<RewriteLibraryWatchState>
    suspend fun setInLibrary(id: String, inLibrary: Boolean, entry: RewriteLibraryEntry? = null)
    suspend fun setWatched(
        id: String,
        watched: Boolean,
        contentType: String = "movie",
        title: String = id,
        season: Int? = null,
        episode: Int? = null,
    )
}

private fun rewriteLibraryWatchKey(profileId: String) = stringPreferencesKey("rewrite_library_watch_v2_$profileId")
private const val MAX_STORED_LIBRARY_ENTRIES = 500

/** Durable local state for independent library and watched actions. */
class RewriteLibraryWatchStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: RewriteProfileRepository = DefaultRewriteProfileRepository,
    private val mutationRecorder: SyncMutationRecorder? = null,
) : RewriteLibraryWatchRepository, RewriteLibraryWatchSyncBridge {
    override val state: Flow<RewriteLibraryWatchState> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        preferences[rewriteLibraryWatchKey(profileState.activeProfile.id)]?.let(RewriteLibraryWatchCodec::decode) ?: RewriteLibraryWatchState()
    }

    override suspend fun setInLibrary(id: String, inLibrary: Boolean, entry: RewriteLibraryEntry?) {
        val profileId = profiles.state.first().activeProfile.id
        mutate(profileId) { current ->
            val changedAt = System.currentTimeMillis()
            val entries = current.libraryEntries.filterNot { it.id == id }.toMutableList()
            if (inLibrary) entries += (entry ?: current.libraryEntries.firstOrNull { it.id == id }
                ?: RewriteLibraryEntry(id = id, title = id)).copy(changedAtEpochMs = changedAt)
            current.copy(
                libraryIds = current.libraryIds.withMembership(id, inLibrary),
                libraryEntries = entries
                    .sortedByDescending(RewriteLibraryEntry::changedAtEpochMs)
                    .take(MAX_STORED_LIBRARY_ENTRIES),
            )
        }
        recordLibraryMutation(profileId, id, inLibrary)
    }

    override suspend fun setWatched(
        id: String,
        watched: Boolean,
        contentType: String,
        title: String,
        season: Int?,
        episode: Int?,
    ) {
        val profileId = profiles.state.first().activeProfile.id
        mutate(profileId) { current ->
            current.copy(watchedIds = current.watchedIds.withMembership(id, watched))
        }
        recordWatchedMutation(profileId, id, watched, contentType, title, season, episode)
    }

    private suspend fun mutate(
        profileId: String,
        transform: (RewriteLibraryWatchState) -> RewriteLibraryWatchState,
    ) {
        // The caller owns this profile-scoped operation from read through outbox recording. A
        // concurrent selection therefore cannot attach the mutation to another profile.
        dataStore.edit { preferences ->
            val key = rewriteLibraryWatchKey(profileId)
            val current = preferences[key]?.let(RewriteLibraryWatchCodec::decode) ?: RewriteLibraryWatchState()
            preferences[key] = RewriteLibraryWatchCodec.encode(transform(current))
        }
    }

    override suspend fun snapshot() = dataStore.data.first().asMap()
        .mapNotNull { (key, value) ->
            val profileId = key.name.removePrefix("rewrite_library_watch_v2_").toIntOrNull() ?: return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val state = RewriteLibraryWatchCodec.decode(raw)
            profileId to state
        }
        .fold(RewriteLibraryWatchSyncSnapshot()) { snapshot, (profileId, state) ->
            snapshot.copy(
                library = snapshot.library + state.libraryEntries.map { it.toCloudLibraryItem(profileId) },
                watchedItems = snapshot.watchedItems + state.watchedIds.map { id ->
                    CloudWatchedItem(profileId, id, "movie", id, null, null, 0L)
                },
            )
        }

    override suspend fun apply(snapshot: RewriteLibraryWatchSyncSnapshot) {
        val groupedLibrary = snapshot.library.groupBy(CloudLibraryItem::profileId)
        val groupedWatched = snapshot.watchedItems.groupBy(CloudWatchedItem::profileId)
        dataStore.edit { preferences ->
            val profileIds = (groupedLibrary.keys + groupedWatched.keys).toSet()
            profileIds.forEach { profileId ->
                val entries = groupedLibrary[profileId].orEmpty().map { it.toLibraryEntry() }
                val watched = groupedWatched[profileId].orEmpty().mapTo(mutableSetOf(), CloudWatchedItem::contentId)
                preferences[rewriteLibraryWatchKey(profileId.toString())] = RewriteLibraryWatchCodec.encode(
                    RewriteLibraryWatchState(
                        libraryIds = entries.mapTo(mutableSetOf(), RewriteLibraryEntry::id),
                        watchedIds = watched,
                        libraryEntries = entries
                            .sortedByDescending(RewriteLibraryEntry::changedAtEpochMs)
                            .take(MAX_STORED_LIBRARY_ENTRIES),
                    ),
                )
            }
        }
    }

    private suspend fun recordLibraryMutation(profileId: String, id: String, inLibrary: Boolean) {
        val cloudProfileId = cloudLinkedProfileIdOrNull(profileId) ?: return
        if (mutationRecorder == null) return
        val state = dataStore.data.first()[rewriteLibraryWatchKey(profileId)]
            ?.let(RewriteLibraryWatchCodec::decode)
            ?: RewriteLibraryWatchState()
        val entry = state.libraryEntries.firstOrNull { it.id == id }
        if (inLibrary && entry != null) {
            mutationRecorder.record(SyncMutation.LibraryItem(entry.toCloudLibraryItem(cloudProfileId)))
        } else {
            mutationRecorder.recordDelete(SyncDomain.LIBRARY, cloudProfileId, id, System.currentTimeMillis())
        }
    }

    private suspend fun recordWatchedMutation(
        profileId: String,
        id: String,
        watched: Boolean,
        contentType: String,
        title: String,
        season: Int?,
        episode: Int?,
    ) {
        val cloudProfileId = cloudLinkedProfileIdOrNull(profileId) ?: return
        if (mutationRecorder == null) return
        if (watched) {
            val now = System.currentTimeMillis()
            mutationRecorder.record(
                SyncMutation.WatchedItem(
                    CloudWatchedItem(cloudProfileId, id, contentType, title, season, episode, now, now),
                ),
            )
        } else {
            mutationRecorder.recordDelete(SyncDomain.WATCHED_ITEMS, cloudProfileId, id, System.currentTimeMillis())
        }
    }
}

private object DefaultRewriteProfileRepository : RewriteProfileRepository {
    override val state = flowOf(RewriteProfileState())
    override suspend fun select(profileId: String) = Unit
    override suspend fun create(name: String) = RewriteProfile(RewriteProfileState.DefaultProfileId, "Viewer")
    override suspend fun rename(profileId: String, name: String) = Unit
    override suspend fun remove(profileId: String) = Unit
    override suspend fun setRememberLastProfile(enabled: Boolean) = Unit
    override suspend fun migrateIfDefault(profiles: List<PriorProfileSnapshot>, activeProfileId: String, rememberLastProfile: Boolean) = Unit
}

private fun Set<String>.withMembership(id: String, included: Boolean): Set<String> =
    toMutableSet().also { values -> if (included) values += id else values -= id }

object RewriteLibraryWatchCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: RewriteLibraryWatchState): String = json.encodeToString(
        StoredLibraryWatch(
            libraryIds = state.libraryIds.sorted(),
            watchedIds = state.watchedIds.sorted(),
            libraryEntries = state.libraryEntries.map(StoredLibraryEntry::from),
        ),
    )

    fun decode(raw: String): RewriteLibraryWatchState = decodeOrNull(raw) ?: RewriteLibraryWatchState()
    fun decodeOrNull(raw: String): RewriteLibraryWatchState? = runCatching {
        json.decodeFromString<StoredLibraryWatch>(raw).let { stored ->
            val entries = stored.libraryEntries.map(StoredLibraryEntry::toModel)
            RewriteLibraryWatchState(
                libraryIds = (stored.libraryIds + entries.map(RewriteLibraryEntry::id)).toSet(),
                watchedIds = stored.watchedIds.toSet(),
                libraryEntries = entries
                    .sortedByDescending(RewriteLibraryEntry::changedAtEpochMs)
                    .take(MAX_STORED_LIBRARY_ENTRIES),
            )
        }
    }.getOrNull()
}

@Serializable
private data class StoredLibraryWatch(
    val libraryIds: List<String> = emptyList(),
    val watchedIds: List<String> = emptyList(),
    val libraryEntries: List<StoredLibraryEntry> = emptyList(),
)

@Serializable
private data class StoredLibraryEntry(
    val id: String,
    val title: String,
    val imageUrl: String? = null,
    val contentType: String? = null,
    val addonId: String? = null,
    val changedAtEpochMs: Long = 0L,
) {
    fun toModel() = RewriteLibraryEntry(id, title, imageUrl, contentType, addonId, changedAtEpochMs)

    companion object {
        fun from(entry: RewriteLibraryEntry) = StoredLibraryEntry(
            entry.id,
            entry.title,
            entry.imageUrl,
            entry.contentType,
            entry.addonId,
            entry.changedAtEpochMs,
        )
    }
}

private fun RewriteLibraryEntry.toCloudLibraryItem(profileId: Int) = CloudLibraryItem(
    profileId = profileId,
    contentId = id,
    contentType = contentType ?: "movie",
    name = title,
    poster = imageUrl,
    addonBaseUrl = addonId,
    addedAt = changedAtEpochMs,
    changedAt = changedAtEpochMs,
)

private fun CloudLibraryItem.toLibraryEntry() = RewriteLibraryEntry(
    id = contentId,
    title = name.ifBlank { contentId },
    imageUrl = poster,
    contentType = contentType,
    addonId = addonBaseUrl,
    changedAtEpochMs = changedAt,
)
