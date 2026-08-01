package com.sluggyard.tv.ui.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val corruptionKey = stringPreferencesKey("app_data_corruption_v1")

/** Detects malformed Rewrite blobs without replacing them or silently losing the user's data. */
class DataIntegrityStore(
    private val dataStore: DataStore<Preferences>,
) {
    val notices: Flow<List<String>> = dataStore.data.map { preferences ->
        preferences[corruptionKey].orEmpty().split('|').filter(String::isNotBlank)
    }

    suspend fun scan() {
        dataStore.edit { preferences ->
            val notices = preferences[corruptionKey].orEmpty().split('|').filter(String::isNotBlank).toMutableSet()
            preferences.asMap().forEach { (key, value) ->
                val raw = value as? String ?: return@forEach
                when {
                    key.name == "app_profiles_v1" && ProfileCodec.decodeOrNull(raw) == null -> notices += "profiles"
                    key.name.startsWith("app_library_watch_v2_") && LibraryWatchCodec.decodeOrNull(raw) == null -> notices += "library"
                    key.name.startsWith("app_playback_progress_v2_") && PlaybackProgressCodec.decodeOrNull(raw) == null -> notices += "progress"
                    key.name.startsWith("app_home_settings_v2_") && HomeSettingsCodec.decodeOrNull(raw) == null -> notices += "home"
                    key.name == "app_addon_registry_v1" && AddonRegistryCodec.decodeOrNull(raw) == null -> notices += "sources"
                }
            }
            if (notices.isEmpty()) preferences.remove(corruptionKey)
            else preferences[corruptionKey] = notices.sorted().joinToString("|")
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(corruptionKey) }
    }
}
