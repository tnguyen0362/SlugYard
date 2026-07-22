package com.sluggyard.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.sluggyard.tv.ui.screens.player.AspectMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local (profile-agnostic) player preferences. These never sync across
 * profiles or devices. Currently tracks the player aspect-ratio mode.
 */
@Singleton
class DeviceLocalPlayerPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("device_local_player_prefs")
    }

    private val aspectModeKey = stringPreferencesKey("aspect_mode")

    val aspectMode: Flow<AspectMode> = store.data.map { prefs ->
        prefs[aspectModeKey]?.let { runCatching { AspectMode.valueOf(it) }.getOrDefault(AspectMode.ORIGINAL) }
            ?: AspectMode.ORIGINAL
    }

    suspend fun setAspectMode(mode: AspectMode) {
        store.edit { prefs -> prefs[aspectModeKey] = mode.name }
    }
}