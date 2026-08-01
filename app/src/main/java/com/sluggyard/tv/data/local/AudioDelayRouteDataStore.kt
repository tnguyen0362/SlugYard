package com.sluggyard.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.sluggyard.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioDelayRouteDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE_NAME = "audio_delay_route_preference"
        const val DELAY_FIELD = "delay_ms"
    }

    private fun store(): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
        factory.get(profileManager.activeProfileId.value, FEATURE_NAME)

    private fun keyFor(routeKey: String) = intPreferencesKey("$DELAY_FIELD|$routeKey")

    suspend fun saveDelayMs(routeKey: String, delayMs: Int?) {
        store().edit { prefs ->
            val key = keyFor(routeKey)
            if (delayMs != null && delayMs != 0) prefs[key] = delayMs else prefs.remove(key)
        }
    }

    suspend fun loadDelayMs(routeKey: String): Int? =
        store().data.first()[keyFor(routeKey)]
}