package com.sluggyard.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE_NAME = "library_preferences"
        const val TAG = "LibraryPrefs"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE_NAME)

    private val gson = Gson()
    private val libraryItemsKey = stringSetPreferencesKey("library_items")
    private val sortOptionKey = stringPreferencesKey("library_sort_option")

    val sortOption: Flow<String?> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE_NAME).data.map { prefs -> prefs[sortOptionKey] }
    }

    suspend fun setSortOption(key: String) {
        store().edit { prefs -> prefs[sortOptionKey] = key }
    }

    val libraryItems: Flow<List<SavedLibraryItem>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE_NAME).data.map { prefs ->
            val raw = prefs[libraryItemsKey] ?: emptySet()
            raw.mapNotNull { json ->
                runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }.getOrNull()
            }
        }
    }

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> =
        libraryItems.map { items ->
            items.any { it.id == itemId && it.type.equals(itemType, ignoreCase = true) }
        }

    suspend fun addItem(item: SavedLibraryItem) {
        store().edit { prefs ->
            val current = prefs[libraryItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }
                    .getOrNull()?.let { saved ->
                        saved.id == item.id && saved.type.equals(item.type, ignoreCase = true)
                    } ?: false
            }
            val itemWithTimestamp = if (item.addedAt == 0L) item.copy(addedAt = System.currentTimeMillis()) else item
            prefs[libraryItemsKey] = filtered.toSet() + gson.toJson(itemWithTimestamp)
        }
    }

    suspend fun removeItem(itemId: String, itemType: String) {
        store().edit { prefs ->
            val current = prefs[libraryItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }
                    .getOrNull()?.let { saved ->
                        saved.id == itemId && saved.type.equals(itemType, ignoreCase = true)
                    } ?: false
            }
            prefs[libraryItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(): List<SavedLibraryItem> = libraryItems.first()

    suspend fun updateLogo(id: String, type: String, logo: String) {
        store().edit { prefs ->
            val current = prefs[libraryItemsKey] ?: emptySet()
            val updated = current.map { json ->
                val item = runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }
                    .getOrNull() ?: return@map json
                if (item.id == id && item.type.equals(type, ignoreCase = true)) {
                    gson.toJson(item.copy(logo = logo))
                } else json
            }.toSet()
            prefs[libraryItemsKey] = updated
        }
    }

    suspend fun mergeRemoteItems(remoteItems: List<SavedLibraryItem>) {
        store().edit { prefs ->
            val current = prefs[libraryItemsKey] ?: emptySet()
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "mergeRemoteItems: remote list empty while local has ${current.size} entries; preserving local library")
                return@edit
            }
            val dedupedRemote = linkedMapOf<Pair<String, String>, SavedLibraryItem>()
            remoteItems.forEach { item ->
                dedupedRemote[item.id to item.type.lowercase()] = item
            }
            prefs[libraryItemsKey] = dedupedRemote.values.map { gson.toJson(it) }.toSet()
        }
    }
}