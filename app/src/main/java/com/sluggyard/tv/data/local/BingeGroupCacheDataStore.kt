package com.sluggyard.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile cache that remembers the last-used binge group for a series so
 * subsequent episode launches (Continue Watching, Details, Next Episode) can
 * prefer the same source group. Local-only; never synced.
 */
@Singleton
class BingeGroupCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE_NAME = "binge_group_cache"
        const val KEY_PREFIX = "bg_"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE_NAME)

    private fun keyFor(contentId: String) = stringPreferencesKey("$KEY_PREFIX$contentId")

    suspend fun save(contentId: String, bingeGroup: String) {
        store().edit { prefs -> prefs[keyFor(contentId)] = bingeGroup }
    }

    suspend fun get(contentId: String): String? =
        store().data.first()[keyFor(contentId)]

    suspend fun remove(contentId: String) {
        store().edit { prefs -> prefs.remove(keyFor(contentId)) }
    }
}