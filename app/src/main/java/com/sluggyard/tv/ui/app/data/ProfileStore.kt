package com.sluggyard.tv.ui.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A deliberately neutral profile model: names only, no inherited avatar or colour system. */
data class Profile(val id: String, val name: String)

data class PriorProfileSnapshot(val id: String, val name: String)

data class ProfileState(
    val profiles: List<Profile> = listOf(Profile(DefaultProfileId, "Viewer")),
    val activeProfileId: String = DefaultProfileId,
    val rememberLastProfile: Boolean = true,
) {
    val activeProfile: Profile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    companion object {
        /** Matches Supabase / legacy ProfileManager integer profile_index identity. */
        const val DefaultProfileId = "1"
    }
}

interface ProfileRepository {
    val state: Flow<ProfileState>
    suspend fun select(profileId: String)
    suspend fun create(name: String): Profile
    suspend fun rename(profileId: String, name: String)
    suspend fun remove(profileId: String)
    suspend fun setRememberLastProfile(enabled: Boolean)
    suspend fun migrateIfDefault(
        profiles: List<PriorProfileSnapshot>,
        activeProfileId: String,
        rememberLastProfile: Boolean,
    )
}

private val profileKey = stringPreferencesKey("app_profiles_v1")

class ProfileStore(private val dataStore: DataStore<Preferences>) : ProfileRepository {
    override val state: Flow<ProfileState> = dataStore.data.map { preferences ->
        preferences[profileKey]?.let(ProfileCodec::decode) ?: ProfileState()
    }

    override suspend fun select(profileId: String) = mutate { current ->
        current.takeIf { state -> state.profiles.any { it.id == profileId } }
            ?.copy(activeProfileId = profileId) ?: current
    }

    override suspend fun create(name: String): Profile {
        lateinit var profile: Profile
        mutate { current ->
            val nextId = nextCloudProfileId(current.profiles.map { it.id })
            profile = Profile(nextId, normalizedName(name))
            current.copy(profiles = current.profiles + profile)
        }
        return profile
    }

    suspend fun addExternalProfile(id: String, name: String): Profile {
        val profile = Profile(id, normalizedName(name))
        mutate { current ->
            if (current.profiles.any { it.id == id }) {
                current.copy(profiles = current.profiles.map { existing ->
                    if (existing.id == id) profile else existing
                })
            } else {
                current.copy(profiles = current.profiles + profile)
            }
        }
        return profile
    }

    override suspend fun rename(profileId: String, name: String) = mutate { current ->
        current.copy(profiles = current.profiles.map { profile ->
            if (profile.id == profileId) profile.copy(name = normalizedName(name)) else profile
        })
    }

    override suspend fun remove(profileId: String) = mutate { current ->
        if (current.profiles.size <= 1 || current.profiles.none { it.id == profileId }) current else {
            val remaining = current.profiles.filterNot { it.id == profileId }
            current.copy(
                profiles = remaining,
                activeProfileId = current.activeProfileId.takeIf { it != profileId } ?: remaining.first().id,
            )
        }
    }

    override suspend fun setRememberLastProfile(enabled: Boolean) = mutate { current ->
        current.copy(rememberLastProfile = enabled)
    }

    override suspend fun migrateIfDefault(
        profiles: List<PriorProfileSnapshot>,
        activeProfileId: String,
        rememberLastProfile: Boolean,
    ) = mutate { current -> migrateProfileStateIfDefault(current, profiles, activeProfileId, rememberLastProfile) }

    private suspend fun mutate(transform: (ProfileState) -> ProfileState) {
        dataStore.edit { preferences ->
            val current = preferences[profileKey]?.let(ProfileCodec::decode) ?: ProfileState()
            preferences[profileKey] = ProfileCodec.encode(transform(current))
        }
    }
}

private fun normalizedName(value: String): String = value.trim().take(32).ifBlank { "Viewer" }

/** Next integer profile id as a string — required by Supabase profile_id columns. */
internal fun nextCloudProfileId(existingIds: Collection<String>): String {
    val maxUsed = existingIds.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0
    return (maxOf(maxUsed, 0) + 1).toString()
}

/**
 * Remaps non-integer Rewrite profile ids (legacy "default", UUIDs) onto integer ids so
 * progress/library/watched can enter the cloud sync contract.
 */
internal fun remapNonIntegerProfileIds(state: ProfileState): Pair<ProfileState, Map<String, String>> {
    val remaps = linkedMapOf<String, String>()
    var next = (state.profiles.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1
    val remappedProfiles = state.profiles.map { profile ->
        if (profile.id.toIntOrNull() != null) {
            profile
        } else {
            val assigned = when {
                profile.id == "default" && state.profiles.none { it.id == "1" } &&
                    remaps.values.none { it == "1" } -> {
                    if (next <= 1) next = 2
                    "1"
                }
                else -> (next++).toString()
            }
            remaps[profile.id] = assigned
            profile.copy(id = assigned)
        }
    }
    if (remaps.isEmpty()) return state to emptyMap()
    val active = remaps[state.activeProfileId] ?: state.activeProfileId
    return state.copy(
        profiles = remappedProfiles.distinctBy { it.id },
        activeProfileId = active.takeIf { id -> remappedProfiles.any { it.id == id } }
            ?: remappedProfiles.first().id,
    ) to remaps
}

fun migrateProfileStateIfDefault(
    current: ProfileState,
    prior: List<PriorProfileSnapshot>,
    activeProfileId: String,
    rememberLastProfile: Boolean,
): ProfileState {
    if (current != ProfileState()) return current
    val imported = prior.asSequence()
        .filter { it.id.isNotBlank() }
        .distinctBy(PriorProfileSnapshot::id)
        .map { Profile(it.id, normalizedName(it.name)) }
        .toList()
        .ifEmpty { current.profiles }
    return current.copy(
        profiles = imported,
        activeProfileId = activeProfileId.takeIf { id -> imported.any { it.id == id } } ?: imported.first().id,
        rememberLastProfile = rememberLastProfile,
    )
}

object ProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(state: ProfileState): String = json.encodeToString(StoredProfiles.from(state))
    fun decode(raw: String): ProfileState = decodeOrNull(raw) ?: ProfileState()
    fun decodeOrNull(raw: String): ProfileState? = runCatching {
        val stored = json.decodeFromString<StoredProfiles>(raw)
        val profiles = stored.profiles.distinctBy(StoredProfile::id).map { Profile(it.id, normalizedName(it.name)) }
            .ifEmpty { listOf(Profile(ProfileState.DefaultProfileId, "Viewer")) }
        ProfileState(
            profiles = profiles,
            activeProfileId = stored.activeProfileId.takeIf { selected -> profiles.any { it.id == selected } } ?: profiles.first().id,
            rememberLastProfile = stored.rememberLastProfile,
        )
    }.getOrNull()
}

@Serializable
private data class StoredProfiles(
    val profiles: List<StoredProfile> = emptyList(),
    val activeProfileId: String = ProfileState.DefaultProfileId,
    val rememberLastProfile: Boolean = true,
) {
    companion object {
        fun from(state: ProfileState) = StoredProfiles(
            profiles = state.profiles.map { StoredProfile(it.id, it.name) },
            activeProfileId = state.activeProfileId,
            rememberLastProfile = state.rememberLastProfile,
        )
    }
}

@Serializable
private data class StoredProfile(val id: String, val name: String)
