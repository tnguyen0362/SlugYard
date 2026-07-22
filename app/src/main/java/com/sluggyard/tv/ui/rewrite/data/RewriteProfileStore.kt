package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** A deliberately neutral profile model: names only, no inherited avatar or colour system. */
data class RewriteProfile(val id: String, val name: String)

data class PriorProfileSnapshot(val id: String, val name: String)

data class RewriteProfileState(
    val profiles: List<RewriteProfile> = listOf(RewriteProfile(DefaultProfileId, "Viewer")),
    val activeProfileId: String = DefaultProfileId,
    val rememberLastProfile: Boolean = true,
) {
    val activeProfile: RewriteProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    companion object { const val DefaultProfileId = "default" }
}

interface RewriteProfileRepository {
    val state: Flow<RewriteProfileState>
    suspend fun select(profileId: String)
    suspend fun create(name: String): RewriteProfile
    suspend fun rename(profileId: String, name: String)
    suspend fun remove(profileId: String)
    suspend fun setRememberLastProfile(enabled: Boolean)
    suspend fun migrateIfDefault(
        profiles: List<PriorProfileSnapshot>,
        activeProfileId: String,
        rememberLastProfile: Boolean,
    )
}

private val rewriteProfileKey = stringPreferencesKey("rewrite_profiles_v1")

class RewriteProfileStore(private val dataStore: DataStore<Preferences>) : RewriteProfileRepository {
    override val state: Flow<RewriteProfileState> = dataStore.data.map { preferences ->
        preferences[rewriteProfileKey]?.let(RewriteProfileCodec::decode) ?: RewriteProfileState()
    }

    override suspend fun select(profileId: String) = mutate { current ->
        current.takeIf { state -> state.profiles.any { it.id == profileId } }
            ?.copy(activeProfileId = profileId) ?: current
    }

    override suspend fun create(name: String): RewriteProfile {
        val profile = RewriteProfile(UUID.randomUUID().toString(), normalizedName(name))
        mutate { current -> current.copy(profiles = current.profiles + profile) }
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

    private suspend fun mutate(transform: (RewriteProfileState) -> RewriteProfileState) {
        dataStore.edit { preferences ->
            val current = preferences[rewriteProfileKey]?.let(RewriteProfileCodec::decode) ?: RewriteProfileState()
            preferences[rewriteProfileKey] = RewriteProfileCodec.encode(transform(current))
        }
    }
}

private fun normalizedName(value: String): String = value.trim().take(32).ifBlank { "Viewer" }

fun migrateProfileStateIfDefault(
    current: RewriteProfileState,
    prior: List<PriorProfileSnapshot>,
    activeProfileId: String,
    rememberLastProfile: Boolean,
): RewriteProfileState {
    if (current != RewriteProfileState()) return current
    val imported = prior.asSequence()
        .filter { it.id.isNotBlank() }
        .distinctBy(PriorProfileSnapshot::id)
        .map { RewriteProfile(it.id, normalizedName(it.name)) }
        .toList()
        .ifEmpty { current.profiles }
    return current.copy(
        profiles = imported,
        activeProfileId = activeProfileId.takeIf { id -> imported.any { it.id == id } } ?: imported.first().id,
        rememberLastProfile = rememberLastProfile,
    )
}

object RewriteProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(state: RewriteProfileState): String = json.encodeToString(StoredProfiles.from(state))
    fun decode(raw: String): RewriteProfileState = runCatching {
        val stored = json.decodeFromString<StoredProfiles>(raw)
        val profiles = stored.profiles.distinctBy(StoredProfile::id).map { RewriteProfile(it.id, normalizedName(it.name)) }
            .ifEmpty { listOf(RewriteProfile(RewriteProfileState.DefaultProfileId, "Viewer")) }
        RewriteProfileState(
            profiles = profiles,
            activeProfileId = stored.activeProfileId.takeIf { selected -> profiles.any { it.id == selected } } ?: profiles.first().id,
            rememberLastProfile = stored.rememberLastProfile,
        )
    }.getOrDefault(RewriteProfileState())
}

@Serializable
private data class StoredProfiles(
    val profiles: List<StoredProfile> = emptyList(),
    val activeProfileId: String = RewriteProfileState.DefaultProfileId,
    val rememberLastProfile: Boolean = true,
) {
    companion object {
        fun from(state: RewriteProfileState) = StoredProfiles(
            profiles = state.profiles.map { StoredProfile(it.id, it.name) },
            activeProfileId = state.activeProfileId,
            rememberLastProfile = state.rememberLastProfile,
        )
    }
}

@Serializable
private data class StoredProfile(val id: String, val name: String)
