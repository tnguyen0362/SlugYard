package com.sluggyard.tv.ui.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.sync.LibraryWatchSyncBridge
import com.sluggyard.tv.core.sync.LibraryWatchSyncSnapshot
import com.sluggyard.tv.core.sync.SupabaseSyncScheduler
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

data class LibraryWatchState(
    val libraryIds: Set<String> = emptySet(),
    val watchedIds: Set<String> = emptySet(),
    val libraryEntries: List<LibraryEntry> = emptyList(),
)

data class LibraryEntry(
    val id: String,
    val title: String,
    val imageUrl: String? = null,
    val contentType: String? = null,
    val addonId: String? = null,
    val changedAtEpochMs: Long = 0L,
)

interface LibraryWatchRepository {
    val state: Flow<LibraryWatchState>
    suspend fun setInLibrary(id: String, inLibrary: Boolean, entry: LibraryEntry? = null)
    suspend fun setWatched(
        id: String,
        watched: Boolean,
        contentType: String = "movie",
        title: String = id,
        season: Int? = null,
        episode: Int? = null,
    )
}

private fun libraryWatchKey(profileId: String) = stringPreferencesKey("app_library_watch_v2_$profileId")
private const val MAX_STORED_LIBRARY_ENTRIES = 500
private const val TAG = "LibraryWatch"

/** Durable local state for independent library and watched actions. */
class LibraryWatchStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: ProfileRepository = DefaultProfileRepository,
    private val mutationRecorder: SyncMutationRecorder? = null,
    private val appContext: Context? = null,
) : LibraryWatchRepository, LibraryWatchSyncBridge {
    override val state: Flow<LibraryWatchState> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        preferences[libraryWatchKey(profileState.activeProfile.id)]?.let(LibraryWatchCodec::decode) ?: LibraryWatchState()
    }

    override suspend fun setInLibrary(id: String, inLibrary: Boolean, entry: LibraryEntry?) {
        val profileId = profiles.state.first().activeProfile.id
        Log.i(TAG, "setInLibrary profile=$profileId id=$id inLibrary=$inLibrary")
        mutate(profileId) { current ->
            val changedAt = System.currentTimeMillis()
            val entries = current.libraryEntries.filterNot { it.id == id }.toMutableList()
            if (inLibrary) entries += (entry ?: current.libraryEntries.firstOrNull { it.id == id }
                ?: LibraryEntry(id = id, title = id)).copy(changedAtEpochMs = changedAt)
            current.copy(
                libraryIds = current.libraryIds.withMembership(id, inLibrary),
                libraryEntries = entries
                    .sortedByDescending(LibraryEntry::changedAtEpochMs)
                    .take(MAX_STORED_LIBRARY_ENTRIES),
            )
        }
        recordLibraryMutation(profileId, id, inLibrary)
        requestWatchlistSync()
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
        Log.i(TAG, "setWatched profile=$profileId id=$id watched=$watched")
        mutate(profileId) { current ->
            current.copy(watchedIds = current.watchedIds.withMembership(id, watched))
        }
        recordWatchedMutation(profileId, id, watched, contentType, title, season, episode)
        requestWatchlistSync()
    }

    private suspend fun mutate(
        profileId: String,
        transform: (LibraryWatchState) -> LibraryWatchState,
    ) {
        // The caller owns this profile-scoped operation from read through outbox recording. A
        // concurrent selection therefore cannot attach the mutation to another profile.
        dataStore.edit { preferences ->
            val key = libraryWatchKey(profileId)
            val current = preferences[key]?.let(LibraryWatchCodec::decode) ?: LibraryWatchState()
            preferences[key] = LibraryWatchCodec.encode(transform(current))
        }
    }

    override suspend fun snapshot() = dataStore.data.first().asMap()
        .mapNotNull { (key, value) ->
            val profileId = key.name.removePrefix("app_library_watch_v2_").toIntOrNull() ?: return@mapNotNull null
            val raw = value as? String ?: return@mapNotNull null
            val state = LibraryWatchCodec.decode(raw)
            profileId to state
        }
        .fold(LibraryWatchSyncSnapshot()) { snapshot, (profileId, state) ->
            snapshot.copy(
                library = snapshot.library + state.libraryEntries.map { it.toCloudLibraryItem(profileId) },
                watchedItems = snapshot.watchedItems + state.watchedIds.map { id ->
                    CloudWatchedItem(profileId, id, "movie", id, null, null, 0L)
                },
            )
        }

    override suspend fun apply(snapshot: LibraryWatchSyncSnapshot) {
        val groupedLibrary = snapshot.library.groupBy(CloudLibraryItem::profileId)
        val groupedWatched = snapshot.watchedItems.groupBy(CloudWatchedItem::profileId)
        dataStore.edit { preferences ->
            val profileIds = (groupedLibrary.keys + groupedWatched.keys).toSet()
            profileIds.forEach { profileId ->
                val entries = groupedLibrary[profileId].orEmpty().map { it.toLibraryEntry() }
                val watched = groupedWatched[profileId].orEmpty().mapTo(mutableSetOf(), CloudWatchedItem::contentId)
                preferences[libraryWatchKey(profileId.toString())] = LibraryWatchCodec.encode(
                    LibraryWatchState(
                        libraryIds = entries.mapTo(mutableSetOf(), LibraryEntry::id),
                        watchedIds = watched,
                        libraryEntries = entries
                            .sortedByDescending(LibraryEntry::changedAtEpochMs)
                            .take(MAX_STORED_LIBRARY_ENTRIES),
                    ),
                )
            }
        }
        Log.i(
            TAG,
            "apply sync profiles=${groupedLibrary.keys + groupedWatched.keys} " +
                "library=${snapshot.library.size} watched=${snapshot.watchedItems.size}",
        )
    }

    private fun requestWatchlistSync() {
        val context = appContext ?: return
        runCatching { SupabaseSyncScheduler.requestImmediate(context) }
            .onFailure { Log.w(TAG, "watchlist sync request failed: ${it.message}") }
    }

    private suspend fun recordLibraryMutation(profileId: String, id: String, inLibrary: Boolean) {
        val cloudProfileId = cloudLinkedProfileIdOrNull(profileId) ?: return
        if (mutationRecorder == null) return
        val state = dataStore.data.first()[libraryWatchKey(profileId)]
            ?.let(LibraryWatchCodec::decode)
            ?: LibraryWatchState()
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

private object DefaultProfileRepository : ProfileRepository {
    override val state = flowOf(ProfileState())
    override suspend fun select(profileId: String) = Unit
    override suspend fun create(name: String) = Profile(ProfileState.DefaultProfileId, "Viewer")
    override suspend fun rename(profileId: String, name: String) = Unit
    override suspend fun remove(profileId: String) = Unit
    override suspend fun setRememberLastProfile(enabled: Boolean) = Unit
    override suspend fun migrateIfDefault(profiles: List<PriorProfileSnapshot>, activeProfileId: String, rememberLastProfile: Boolean) = Unit
}

private fun Set<String>.withMembership(id: String, included: Boolean): Set<String> =
    toMutableSet().also { values -> if (included) values += id else values -= id }

object LibraryWatchCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: LibraryWatchState): String = json.encodeToString(
        StoredLibraryWatch(
            libraryIds = state.libraryIds.sorted(),
            watchedIds = state.watchedIds.sorted(),
            libraryEntries = state.libraryEntries.map(StoredLibraryEntry::from),
        ),
    )

    fun decode(raw: String): LibraryWatchState = decodeOrNull(raw) ?: LibraryWatchState()
    fun decodeOrNull(raw: String): LibraryWatchState? = runCatching {
        json.decodeFromString<StoredLibraryWatch>(raw).let { stored ->
            val entries = stored.libraryEntries.map(StoredLibraryEntry::toModel)
            LibraryWatchState(
                libraryIds = (stored.libraryIds + entries.map(LibraryEntry::id)).toSet(),
                watchedIds = stored.watchedIds.toSet(),
                libraryEntries = entries
                    .sortedByDescending(LibraryEntry::changedAtEpochMs)
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
    fun toModel() = LibraryEntry(id, title, imageUrl, contentType, addonId, changedAtEpochMs)

    companion object {
        fun from(entry: LibraryEntry) = StoredLibraryEntry(
            entry.id,
            entry.title,
            entry.imageUrl,
            entry.contentType,
            entry.addonId,
            entry.changedAtEpochMs,
        )
    }
}

private fun LibraryEntry.toCloudLibraryItem(profileId: Int) = CloudLibraryItem(
    profileId = profileId,
    contentId = id,
    contentType = contentType ?: "movie",
    name = title,
    poster = imageUrl,
    addonBaseUrl = addonId,
    addedAt = changedAtEpochMs,
    changedAt = changedAtEpochMs,
)

private fun CloudLibraryItem.toLibraryEntry() = LibraryEntry(
    id = contentId,
    title = name.ifBlank { contentId },
    imageUrl = poster,
    contentType = contentType,
    addonId = addonBaseUrl,
    changedAtEpochMs = changedAt,
)
