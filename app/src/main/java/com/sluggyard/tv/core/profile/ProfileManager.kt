package com.sluggyard.tv.core.profile

import android.content.Context
import com.sluggyard.tv.R
import com.sluggyard.tv.data.local.ProfileDataStore
import com.sluggyard.tv.data.local.ProfileDataStoreFactory
import com.sluggyard.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val MAX_PROFILES = 6
        private const val LEGACY_NEUTRAL_COLOR = "#6B7280"
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(ioScope, SharingStarted.Eagerly, 1)

    val activeProfileReady: StateFlow<Boolean> = profileDataStore.activeProfileId
        .map { true }
        .stateIn(ioScope, SharingStarted.Eagerly, false)

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(ioScope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(ioScope, SharingStarted.Eagerly, false)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(
            ioScope, SharingStarted.Eagerly,
            listOf(UserProfile(id = 1, name = context.getString(R.string.profile_default_name, 1), avatarColorHex = LEGACY_NEUTRAL_COLOR)),
        )

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    suspend fun setActiveProfile(id: Int) {
        if (profiles.value.any { it.id == id }) profileDataStore.setActiveProfile(id)
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    suspend fun createProfile(
        name: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
    ): Boolean {
        val current = profiles.value
        if (current.size >= MAX_PROFILES) return false
        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return false
        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { context.getString(R.string.profile_default_name, nextId) },
            avatarColorHex = LEGACY_NEUTRAL_COLOR,
            usesPrimaryAddons = usesPrimaryAddons,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = null,
            avatarUrl = null,
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        return true
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        purgeProfileFiles(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        profileDataStore.upsertProfile(profile)
        return true
    }

    private suspend fun purgeProfileFiles(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext
        factory.clearProfile(profileId)
        val suffix = "_p${profileId}.preferences_pb"
        val datastoreDir = File(context.filesDir, "datastore")
        if (datastoreDir.exists()) {
            datastoreDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(suffix)) f.delete()
            }
        }
        val pluginDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginDir.exists()) pluginDir.deleteRecursively()
    }
}