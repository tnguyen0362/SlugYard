package com.sluggyard.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE_NAME = "track_preference"
        const val FIELD_SUB_TYPE = "sub_type"
        const val FIELD_SUB_LANG = "sub_lang"
        const val FIELD_SUB_NAME = "sub_name"
        const val FIELD_SUB_TRACK_ID = "sub_track_id"
        const val FIELD_SUB_IS_FORCED = "sub_is_forced"
        const val FIELD_SUB_ADDON_ID = "sub_addon_id"
        const val FIELD_SUB_ADDON_URL = "sub_addon_url"
        const val FIELD_SUB_ADDON_NAME = "sub_addon_name"
        const val FIELD_AUDIO_LANG = "audio_lang"
        const val FIELD_AUDIO_NAME = "audio_name"
        const val FIELD_AUDIO_TRACK_ID = "audio_track_id"
        // Keyed per-videoId (not per-contentId) so a delay calibrated for one
        // episode is not blindly reapplied to the next episode where it is
        // almost certainly wrong.
        const val FIELD_SUB_DELAY_MS = "sub_delay_ms"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE_NAME)

    private fun stringKey(field: String, id: String) = stringPreferencesKey("$field|$id")
    private fun intKey(field: String, id: String) = intPreferencesKey("$field|$id")

    suspend fun save(contentId: String, pref: PersistedTrackPreference) {
        store().edit { prefs ->
            fun write(field: String, value: String?) {
                val k = stringKey(field, contentId)
                if (value != null) prefs[k] = value else prefs.remove(k)
            }
            write(FIELD_SUB_TYPE, pref.subtitleType)
            write(FIELD_SUB_LANG, pref.subtitleLanguage)
            write(FIELD_SUB_NAME, pref.subtitleName)
            write(FIELD_SUB_TRACK_ID, pref.subtitleTrackId)
            write(FIELD_SUB_IS_FORCED, pref.subtitleIsForced?.toString())
            write(FIELD_SUB_ADDON_ID, pref.addonSubtitleId)
            write(FIELD_SUB_ADDON_URL, pref.addonSubtitleUrl)
            write(FIELD_SUB_ADDON_NAME, pref.addonSubtitleAddonName)
            write(FIELD_AUDIO_LANG, pref.audioLanguage)
            write(FIELD_AUDIO_NAME, pref.audioName)
            write(FIELD_AUDIO_TRACK_ID, pref.audioTrackId)
        }
    }

    suspend fun load(contentId: String): PersistedTrackPreference? {
        val prefs = store().data.first()
        val subType = prefs[stringKey(FIELD_SUB_TYPE, contentId)]
        val audioLang = prefs[stringKey(FIELD_AUDIO_LANG, contentId)]
        val audioName = prefs[stringKey(FIELD_AUDIO_NAME, contentId)]
        val audioTrackId = prefs[stringKey(FIELD_AUDIO_TRACK_ID, contentId)]
        if (subType == null && audioLang == null && audioName == null && audioTrackId == null) {
            return null
        }
        return PersistedTrackPreference(
            subtitleType = subType,
            subtitleLanguage = prefs[stringKey(FIELD_SUB_LANG, contentId)],
            subtitleName = prefs[stringKey(FIELD_SUB_NAME, contentId)],
            subtitleTrackId = prefs[stringKey(FIELD_SUB_TRACK_ID, contentId)],
            subtitleIsForced = prefs[stringKey(FIELD_SUB_IS_FORCED, contentId)]?.toBooleanStrictOrNull(),
            addonSubtitleId = prefs[stringKey(FIELD_SUB_ADDON_ID, contentId)],
            addonSubtitleUrl = prefs[stringKey(FIELD_SUB_ADDON_URL, contentId)],
            addonSubtitleAddonName = prefs[stringKey(FIELD_SUB_ADDON_NAME, contentId)],
            audioLanguage = audioLang,
            audioName = audioName,
            audioTrackId = audioTrackId
        )
    }

    /**
     * Subtitle delay is persisted separately from audio/subtitle track selection
     * because it has different locality: tracks sensibly apply to every episode
     * of a series (same preferred language), but a delay calibrated against one
     * release/encode rarely transfers to the next episode. Keying by videoId
     * scopes the delay to exactly the video it was synced against. See #1063.
     */
    suspend fun saveSubtitleDelayMs(videoId: String, delayMs: Int?) {
        store().edit { prefs ->
            val k = intKey(FIELD_SUB_DELAY_MS, videoId)
            if (delayMs != null && delayMs != 0) prefs[k] = delayMs else prefs.remove(k)
        }
    }

    suspend fun loadSubtitleDelayMs(videoId: String): Int? =
        store().data.first()[intKey(FIELD_SUB_DELAY_MS, videoId)]
}

data class PersistedTrackPreference(
    val subtitleType: String?,
    val subtitleLanguage: String?,
    val subtitleName: String?,
    val subtitleTrackId: String?,
    val subtitleIsForced: Boolean? = null,
    val addonSubtitleId: String?,
    val addonSubtitleUrl: String?,
    val addonSubtitleAddonName: String?,
    val audioLanguage: String?,
    val audioName: String?,
    val audioTrackId: String?
)

internal fun PersistedTrackPreference.toTrackPreference(): com.sluggyard.tv.ui.screens.player.PlayerRuntimeController.TrackPreference? {
    val audio = if (audioLanguage != null || audioName != null || audioTrackId != null) {
        com.sluggyard.tv.ui.screens.player.PlayerRuntimeController.RememberedTrackSelection(
            language = audioLanguage,
            name = audioName,
            trackId = audioTrackId
        )
    } else null

    val subtitle = when (subtitleType) {
        "INTERNAL" -> com.sluggyard.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = com.sluggyard.tv.ui.screens.player.PlayerRuntimeController.RememberedTrackSelection(
                language = subtitleLanguage,
                name = subtitleName,
                trackId = subtitleTrackId,
                isForcedHint = subtitleIsForced
            )
        )
        "ADDON" -> com.sluggyard.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = addonSubtitleId ?: "",
            url = addonSubtitleUrl ?: "",
            language = subtitleLanguage ?: "",
            addonName = addonSubtitleAddonName ?: ""
        )
        "DISABLED" -> com.sluggyard.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        else -> null
    }

    if (audio == null && subtitle == null) return null
    return com.sluggyard.tv.ui.screens.player.PlayerRuntimeController.TrackPreference(
        audio = audio,
        subtitle = subtitle
    )
}