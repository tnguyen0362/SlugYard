package com.sluggyard.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.ui.app.streams.StreamLinkCodecFilter
import com.sluggyard.tv.ui.app.streams.StreamLinkFilterSettings
import com.sluggyard.tv.ui.app.streams.StreamLinkMaxResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamLinkFilterDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    private companion object {
        const val FEATURE_NAME = "stream_link_filters"
        private const val SIZE_UNLIMITED = -1
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE_NAME)

    private val maxResolutionKey = stringPreferencesKey("max_resolution")
    private val maxSizeGbKey = intPreferencesKey("max_size_gb")
    private val allowRemuxKey = booleanPreferencesKey("allow_remux")
    private val allowDolbyVisionKey = booleanPreferencesKey("allow_dolby_vision")
    private val codecKey = stringPreferencesKey("codec")

    val settings: Flow<StreamLinkFilterSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE_NAME).data.map { prefs ->
            StreamLinkFilterSettings(
                maxResolution = prefs[maxResolutionKey].toMaxResolution(),
                maxSizeGb = prefs[maxSizeGbKey].toSizeGb(),
                allowRemux = prefs[allowRemuxKey] ?: true,
                allowDolbyVision = prefs[allowDolbyVisionKey] ?: true,
                codec = prefs[codecKey].toCodec(),
            )
        }
    }

    suspend fun setMaxResolution(value: StreamLinkMaxResolution) =
        store().edit { it[maxResolutionKey] = value.name }

    suspend fun setMaxSizeGb(value: Int?) =
        store().edit { it[maxSizeGbKey] = value ?: SIZE_UNLIMITED }

    suspend fun setAllowRemux(enabled: Boolean) =
        store().edit { it[allowRemuxKey] = enabled }

    suspend fun setAllowDolbyVision(enabled: Boolean) =
        store().edit { it[allowDolbyVisionKey] = enabled }

    suspend fun setCodec(value: StreamLinkCodecFilter) =
        store().edit { it[codecKey] = value.name }

    private fun String?.toMaxResolution(): StreamLinkMaxResolution =
        StreamLinkMaxResolution.entries.firstOrNull { it.name == this }
            ?: StreamLinkMaxResolution.UHD_4K

    private fun Int?.toSizeGb(): Int? = when (this) {
        null -> 15
        SIZE_UNLIMITED -> null
        else -> this
    }

    private fun String?.toCodec(): StreamLinkCodecFilter =
        StreamLinkCodecFilter.entries.firstOrNull { it.name == this }
            ?: StreamLinkCodecFilter.ANY
}
