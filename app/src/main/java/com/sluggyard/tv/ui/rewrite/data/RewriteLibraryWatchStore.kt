package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
)

interface RewriteLibraryWatchRepository {
    val state: Flow<RewriteLibraryWatchState>
    suspend fun setInLibrary(id: String, inLibrary: Boolean)
    suspend fun setWatched(id: String, watched: Boolean)
}

private fun rewriteLibraryWatchKey(profileId: String) = stringPreferencesKey("rewrite_library_watch_v2_$profileId")

/** Durable local state for independent library and watched actions. */
class RewriteLibraryWatchStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: RewriteProfileRepository = DefaultRewriteProfileRepository,
) : RewriteLibraryWatchRepository {
    override val state: Flow<RewriteLibraryWatchState> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        preferences[rewriteLibraryWatchKey(profileState.activeProfile.id)]?.let(RewriteLibraryWatchCodec::decode) ?: RewriteLibraryWatchState()
    }

    override suspend fun setInLibrary(id: String, inLibrary: Boolean) = mutate { current ->
        current.copy(libraryIds = current.libraryIds.withMembership(id, inLibrary))
    }

    override suspend fun setWatched(id: String, watched: Boolean) = mutate { current ->
        current.copy(watchedIds = current.watchedIds.withMembership(id, watched))
    }

    private suspend fun mutate(transform: (RewriteLibraryWatchState) -> RewriteLibraryWatchState) {
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            val key = rewriteLibraryWatchKey(profileId)
            val current = preferences[key]?.let(RewriteLibraryWatchCodec::decode) ?: RewriteLibraryWatchState()
            preferences[key] = RewriteLibraryWatchCodec.encode(transform(current))
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
        StoredLibraryWatch(state.libraryIds.sorted(), state.watchedIds.sorted()),
    )

    fun decode(raw: String): RewriteLibraryWatchState = runCatching {
        json.decodeFromString<StoredLibraryWatch>(raw).let { stored ->
            RewriteLibraryWatchState(stored.libraryIds.toSet(), stored.watchedIds.toSet())
        }
    }.getOrDefault(RewriteLibraryWatchState())
}

@Serializable
private data class StoredLibraryWatch(
    val libraryIds: List<String> = emptyList(),
    val watchedIds: List<String> = emptyList(),
)
