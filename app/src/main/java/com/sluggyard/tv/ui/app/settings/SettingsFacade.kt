package com.sluggyard.tv.ui.app.settings

import android.os.SystemClock
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.data.local.AddonPreferences
import com.sluggyard.tv.data.local.AudioOutputChannels
import com.sluggyard.tv.data.local.CrashReportingPreferences
import com.sluggyard.tv.data.local.DebridSettingsDataStore
import com.sluggyard.tv.data.local.FrameRateMatchingMode
import com.sluggyard.tv.data.local.LayoutPreferenceDataStore
import com.sluggyard.tv.data.local.MDBListSettingsDataStore
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.PlayerSettingsDataStore
import com.sluggyard.tv.data.local.TmdbSettingsDataStore
import com.sluggyard.tv.data.local.TraktAuthDataStore
import com.sluggyard.tv.data.local.TraktAuthState
import com.sluggyard.tv.data.local.TraktSettingsDataStore
import com.sluggyard.tv.data.local.MoreLikeThisSourcePreference
import com.sluggyard.tv.data.local.WatchProgressSource
import com.sluggyard.tv.data.repository.TraktAuthService
import com.sluggyard.tv.data.repository.TraktTokenPollResult
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics
import com.sluggyard.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.domain.model.MDBListSettings
import com.sluggyard.tv.domain.model.TmdbSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI boundary for the locked-down settings surface. It delegates persistence to the existing
 * profile-scoped stores and deliberately omits playback policy knobs that SlugYard owns.
 */
interface SettingsFacade {
    val categories: List<SettingsCategory>
    val traktAuth: Flow<TraktAuthState>
    val traktSettings: TraktSettingsDataStore
    val tmdbSettings: Flow<TmdbSettings>
    val mdblistSettings: Flow<MDBListSettings>
    val debridSettings: Flow<DebridSettings>
    val addonPreferences: AddonPreferences
    val layoutPreferences: LayoutPreferenceDataStore
    val playerSettings: Flow<PlayerSettings>
    val openSubtitlesEnabled: Flow<Boolean>
    val openSubtitlesApiKey: Flow<String>
    val openSubtitlesAutoDownload: Flow<Boolean>
    val streamBadgeSettings: Flow<com.sluggyard.tv.core.streams.StreamBadgeSettings>
    val crashReportingEnabled: Flow<Boolean>

    val auth: SupabaseAuthGateway
    val sessions: SupabaseSessionStore

    suspend fun startTraktDeviceAuth(): Result<TraktDeviceCodeResponseDto>
    suspend fun pollTraktDeviceAuth(): TraktTokenPollResult
    suspend fun disconnectTrakt()

    suspend fun setMoreLikeThisSource(source: MoreLikeThisSourcePreference)
    suspend fun setWatchProgressSource(source: WatchProgressSource)
    suspend fun setTmdbEnabled(enabled: Boolean)
    suspend fun setTmdbEnrichContinueWatching(enabled: Boolean)
    suspend fun setMdblistEnabled(enabled: Boolean)
    suspend fun setMdblistApiKey(apiKey: String)
    suspend fun setDebridEnabled(enabled: Boolean)
    suspend fun setCommunityAddonEnabled(enabled: Boolean)
    suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode)
    suspend fun setResolutionMatchingEnabled(enabled: Boolean)
    suspend fun setAudioOutputChannels(channels: AudioOutputChannels)
    suspend fun setResizeMode(mode: Int)
    suspend fun setSubtitlePreferredLanguage(language: String)
    suspend fun setSubtitleSecondaryLanguage(language: String?)
    suspend fun setUseForcedSubtitles(enabled: Boolean)
    suspend fun setSubtitleShowOnlyPreferredLanguages(enabled: Boolean)
    suspend fun setSubtitleSize(size: Int)
    suspend fun setSubtitleVerticalOffset(offset: Int)
    suspend fun setSubtitleBold(enabled: Boolean)
    suspend fun setSubtitleOutlineEnabled(enabled: Boolean)
    suspend fun setSubtitleOutlineWidth(width: Int)
    suspend fun setSubtitleTextColor(color: Int)
    suspend fun setPreferredAudioLanguage(language: String)
    suspend fun setSecondaryPreferredAudioLanguage(language: String?)
    suspend fun setDownmixEnabled(enabled: Boolean)
    suspend fun setMaintainOriginalAudioOnDownmix(enabled: Boolean)
    suspend fun setTunnelingEnabled(enabled: Boolean)
    suspend fun setForceOpticalPassthrough(enabled: Boolean)
    suspend fun setOpenSubtitlesEnabled(enabled: Boolean)
    suspend fun setOpenSubtitlesApiKey(apiKey: String)
    suspend fun setOpenSubtitlesAutoDownload(enabled: Boolean)

    suspend fun setCrashReportingEnabled(enabled: Boolean)

    suspend fun ensureDefaultStreamBadgePack(): com.sluggyard.tv.core.streams.StreamBadgeImportResult?
    suspend fun refreshStreamBadgePack(): com.sluggyard.tv.core.streams.StreamBadgeImportResult
    suspend fun clearStreamBadgePacks()
}

@Singleton
class DefaultSettingsFacade @Inject constructor(
    override val auth: SupabaseAuthGateway,
    override val sessions: SupabaseSessionStore,
    private val traktAuthService: TraktAuthService,
    private val traktAuthDataStore: TraktAuthDataStore,
    override val traktSettings: TraktSettingsDataStore,
    private val tmdbStore: TmdbSettingsDataStore,
    private val mdblistStore: MDBListSettingsDataStore,
    private val debridStore: DebridSettingsDataStore,
    override val addonPreferences: AddonPreferences,
    override val layoutPreferences: LayoutPreferenceDataStore,
    private val playerStore: PlayerSettingsDataStore,
    private val streamBadgeStore: com.sluggyard.tv.data.local.StreamBadgeSettingsDataStore,
    private val streamBadgeImporter: com.sluggyard.tv.core.streams.StreamBadgeImporter,
    private val crashReportingPreferences: CrashReportingPreferences,
) : SettingsFacade {
    override val categories: List<SettingsCategory> = SettingsPolicy.categories
    override val traktAuth: Flow<TraktAuthState> = traktAuthDataStore.state
    override val tmdbSettings: Flow<TmdbSettings> = tmdbStore.settings
    override val mdblistSettings: Flow<MDBListSettings> = mdblistStore.settings
    override val debridSettings: Flow<DebridSettings> = debridStore.settings
    override val playerSettings: Flow<PlayerSettings> = playerStore.playerSettings
    override val openSubtitlesEnabled: Flow<Boolean> = playerStore.openSubtitlesEnabled
    override val openSubtitlesApiKey: Flow<String> = playerStore.openSubtitlesApiKey
    override val openSubtitlesAutoDownload: Flow<Boolean> = playerStore.openSubtitlesAutoDownload
    override val streamBadgeSettings: Flow<com.sluggyard.tv.core.streams.StreamBadgeSettings> =
        streamBadgeStore.settings
    override val crashReportingEnabled: Flow<Boolean> = crashReportingPreferences.enabled

    private suspend fun <T> trackSetting(
        name: String,
        value: Any? = null,
        operation: suspend () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val result = operation()
            ExperimentalDiagnostics.settingMutation(
                setting = name,
                success = true,
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                value = value,
            )
            result
        } catch (failure: Throwable) {
            ExperimentalDiagnostics.failure(
                "settings",
                "mutation_failed",
                failure,
                "setting" to name,
                "durationMs" to SystemClock.elapsedRealtime() - startedAt,
            )
            throw failure
        }
    }

    override suspend fun startTraktDeviceAuth(): Result<TraktDeviceCodeResponseDto> =
        trackSetting("trakt.start_device_auth") { traktAuthService.startDeviceAuth() }

    override suspend fun pollTraktDeviceAuth(): TraktTokenPollResult =
        trackSetting("trakt.poll_device_auth") { traktAuthService.pollDeviceToken() }

    override suspend fun disconnectTrakt() = trackSetting("trakt.disconnect") {
        traktAuthDataStore.clearAuth()
    }

    override suspend fun setMoreLikeThisSource(source: MoreLikeThisSourcePreference) =
        trackSetting("trakt.more_like_this_source", source) { traktSettings.setMoreLikeThisSource(source) }

    override suspend fun setWatchProgressSource(source: WatchProgressSource) =
        trackSetting("trakt.watch_progress_source", source) { traktSettings.setWatchProgressSource(source) }

    override suspend fun setTmdbEnabled(enabled: Boolean) {
        trackSetting("tmdb.enabled", enabled) { tmdbStore.setEnabled(enabled) }
    }

    override suspend fun setTmdbEnrichContinueWatching(enabled: Boolean) {
        trackSetting("tmdb.enrich_continue_watching", enabled) { tmdbStore.setEnrichContinueWatching(enabled) }
    }

    override suspend fun setMdblistEnabled(enabled: Boolean) {
        trackSetting("mdblist.enabled", enabled) { mdblistStore.setEnabled(enabled) }
    }

    override suspend fun setMdblistApiKey(apiKey: String) {
        trackSetting("mdblist.api_key", "<redacted>") { mdblistStore.setApiKey(apiKey) }
    }

    override suspend fun setDebridEnabled(enabled: Boolean) =
        trackSetting("debrid.enabled", enabled) { debridStore.setEnabled(enabled) }

    override suspend fun setCommunityAddonEnabled(enabled: Boolean) =
        trackSetting("community_addons.enabled", enabled) { debridStore.setCommunityAddonEnabled(enabled) }

    override suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) =
        trackSetting("player.frame_rate_matching", mode) { playerStore.setFrameRateMatchingMode(mode) }

    override suspend fun setResolutionMatchingEnabled(enabled: Boolean) =
        trackSetting("player.resolution_matching", enabled) { playerStore.setResolutionMatchingEnabled(enabled) }

    override suspend fun setAudioOutputChannels(channels: AudioOutputChannels) =
        trackSetting("player.audio_output_channels", channels) { playerStore.setAudioOutputChannels(channels) }

    override suspend fun setResizeMode(mode: Int) =
        trackSetting("player.resize_mode", mode) { playerStore.setResizeMode(mode) }

    override suspend fun setSubtitlePreferredLanguage(language: String) =
        trackSetting("player.subtitle_preferred_language", language) { playerStore.setSubtitlePreferredLanguage(language) }

    override suspend fun setSubtitleSecondaryLanguage(language: String?) =
        trackSetting("player.subtitle_secondary_language", language) { playerStore.setSubtitleSecondaryLanguage(language) }

    override suspend fun setUseForcedSubtitles(enabled: Boolean) =
        trackSetting("player.forced_subtitles", enabled) { playerStore.setUseForcedSubtitles(enabled) }

    override suspend fun setSubtitleShowOnlyPreferredLanguages(enabled: Boolean) =
        trackSetting("player.subtitle_preferred_only", enabled) { playerStore.setSubtitleShowOnlyPreferredLanguages(enabled) }

    override suspend fun setSubtitleSize(size: Int) =
        trackSetting("player.subtitle_size", size) { playerStore.setSubtitleSize(size) }

    override suspend fun setSubtitleVerticalOffset(offset: Int) =
        trackSetting("player.subtitle_vertical_offset", offset) { playerStore.setSubtitleVerticalOffset(offset) }

    override suspend fun setSubtitleBold(enabled: Boolean) =
        trackSetting("player.subtitle_bold", enabled) { playerStore.setSubtitleBold(enabled) }

    override suspend fun setSubtitleOutlineEnabled(enabled: Boolean) =
        trackSetting("player.subtitle_outline", enabled) { playerStore.setSubtitleOutlineEnabled(enabled) }

    override suspend fun setSubtitleOutlineWidth(width: Int) =
        trackSetting("player.subtitle_outline_width", width) { playerStore.setSubtitleOutlineWidth(width) }

    override suspend fun setSubtitleTextColor(color: Int) =
        trackSetting("player.subtitle_text_color", color) { playerStore.setSubtitleTextColor(color) }

    override suspend fun setPreferredAudioLanguage(language: String) =
        trackSetting("player.audio_preferred_language", language) { playerStore.setPreferredAudioLanguage(language) }

    override suspend fun setSecondaryPreferredAudioLanguage(language: String?) =
        trackSetting("player.audio_secondary_language", language) { playerStore.setSecondaryPreferredAudioLanguage(language) }

    override suspend fun setDownmixEnabled(enabled: Boolean) =
        trackSetting("player.downmix", enabled) { playerStore.setDownmixEnabled(enabled) }

    override suspend fun setMaintainOriginalAudioOnDownmix(enabled: Boolean) =
        trackSetting("player.maintain_original_mix", enabled) { playerStore.setMaintainOriginalAudioOnDownmix(enabled) }

    override suspend fun setTunnelingEnabled(enabled: Boolean) =
        trackSetting("player.tunneling", enabled) { playerStore.setTunnelingEnabled(enabled) }

    override suspend fun setForceOpticalPassthrough(enabled: Boolean) =
        trackSetting("player.optical_passthrough", enabled) { playerStore.setForceOpticalPassthrough(enabled) }

    override suspend fun setOpenSubtitlesEnabled(enabled: Boolean) =
        trackSetting("opensubtitles.enabled", enabled) { playerStore.setOpenSubtitlesEnabled(enabled) }

    override suspend fun setOpenSubtitlesApiKey(apiKey: String) =
        trackSetting("opensubtitles.api_key", "<redacted>") { playerStore.setOpenSubtitlesApiKey(apiKey) }

    override suspend fun setOpenSubtitlesAutoDownload(enabled: Boolean) =
        trackSetting("opensubtitles.auto_download", enabled) { playerStore.setOpenSubtitlesAutoDownload(enabled) }

    override suspend fun setCrashReportingEnabled(enabled: Boolean) =
        trackSetting("crash_reporting.enabled", enabled) { crashReportingPreferences.setEnabled(enabled) }

    override suspend fun ensureDefaultStreamBadgePack() =
        trackSetting("stream_badges.ensure_default") { streamBadgeImporter.ensureDefaultPackInstalled() }

    override suspend fun refreshStreamBadgePack() =
        trackSetting("stream_badges.refresh") { streamBadgeImporter.refreshActiveOrDefault() }

    override suspend fun clearStreamBadgePacks() =
        trackSetting("stream_badges.clear") { streamBadgeImporter.clearAll() }
}
