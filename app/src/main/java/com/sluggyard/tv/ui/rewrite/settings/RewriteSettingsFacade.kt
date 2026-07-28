package com.sluggyard.tv.ui.rewrite.settings

import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.data.local.AddonPreferences
import com.sluggyard.tv.data.local.AudioOutputChannels
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
import com.sluggyard.tv.data.local.WatchProgressSource
import com.sluggyard.tv.data.local.MoreLikeThisSourcePreference
import com.sluggyard.tv.data.repository.TraktAuthService
import com.sluggyard.tv.data.repository.TraktTokenPollResult
import com.sluggyard.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.domain.model.LibrarySourceMode
import com.sluggyard.tv.domain.model.MDBListSettings
import com.sluggyard.tv.domain.model.TmdbSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI boundary for the locked-down settings surface. It delegates persistence to the existing
 * profile-scoped stores and deliberately omits playback policy knobs that SlugYard owns.
 */
interface RewriteSettingsFacade {
    val categories: List<RewriteSettingsCategory>
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

    val auth: SupabaseAuthGateway
    val sessions: SupabaseSessionStore

    suspend fun startTraktDeviceAuth(): Result<TraktDeviceCodeResponseDto>
    suspend fun pollTraktDeviceAuth(): TraktTokenPollResult
    suspend fun disconnectTrakt()

    suspend fun setWatchProgressSource(source: WatchProgressSource)
    suspend fun setLibrarySourceMode(mode: LibrarySourceMode)
    suspend fun setMoreLikeThisSource(source: MoreLikeThisSourcePreference)
    suspend fun setTmdbEnabled(enabled: Boolean)
    suspend fun setTmdbEnrichContinueWatching(enabled: Boolean)
    suspend fun setMdblistEnabled(enabled: Boolean)
    suspend fun setMdblistApiKey(apiKey: String)
    suspend fun setDebridEnabled(enabled: Boolean)
    suspend fun setDebridProviderApiKey(providerId: String, apiKey: String)
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
    suspend fun setPreferredAudioLanguage(language: String)
    suspend fun setSecondaryPreferredAudioLanguage(language: String?)
    suspend fun setDownmixEnabled(enabled: Boolean)
    suspend fun setMaintainOriginalAudioOnDownmix(enabled: Boolean)
    suspend fun setTunnelingEnabled(enabled: Boolean)
    suspend fun setForceOpticalPassthrough(enabled: Boolean)
    suspend fun setOpenSubtitlesEnabled(enabled: Boolean)
    suspend fun setOpenSubtitlesApiKey(apiKey: String)
    suspend fun setOpenSubtitlesAutoDownload(enabled: Boolean)
}

@Singleton
class DefaultRewriteSettingsFacade @Inject constructor(
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
) : RewriteSettingsFacade {
    override val categories: List<RewriteSettingsCategory> = RewriteSettingsPolicy.categories
    override val traktAuth: Flow<TraktAuthState> = traktAuthDataStore.state
    override val tmdbSettings: Flow<TmdbSettings> = tmdbStore.settings
    override val mdblistSettings: Flow<MDBListSettings> = mdblistStore.settings
    override val debridSettings: Flow<DebridSettings> = debridStore.settings
    override val playerSettings: Flow<PlayerSettings> = playerStore.playerSettings
    override val openSubtitlesEnabled: Flow<Boolean> = playerStore.openSubtitlesEnabled
    override val openSubtitlesApiKey: Flow<String> = playerStore.openSubtitlesApiKey
    override val openSubtitlesAutoDownload: Flow<Boolean> = playerStore.openSubtitlesAutoDownload

    override suspend fun startTraktDeviceAuth(): Result<TraktDeviceCodeResponseDto> =
        traktAuthService.startDeviceAuth()

    override suspend fun pollTraktDeviceAuth(): TraktTokenPollResult =
        traktAuthService.pollDeviceToken()

    override suspend fun disconnectTrakt() {
        traktAuthDataStore.clearAuth()
    }

    override suspend fun setWatchProgressSource(source: WatchProgressSource) {
        traktSettings.setWatchProgressSource(source)
    }

    override suspend fun setLibrarySourceMode(mode: LibrarySourceMode) {
        traktSettings.setLibrarySourceMode(mode)
    }

    override suspend fun setMoreLikeThisSource(source: MoreLikeThisSourcePreference) {
        traktSettings.setMoreLikeThisSource(source)
    }

    override suspend fun setTmdbEnabled(enabled: Boolean) {
        tmdbStore.setEnabled(enabled)
    }

    override suspend fun setTmdbEnrichContinueWatching(enabled: Boolean) {
        tmdbStore.setEnrichContinueWatching(enabled)
    }

    override suspend fun setMdblistEnabled(enabled: Boolean) {
        mdblistStore.setEnabled(enabled)
    }

    override suspend fun setMdblistApiKey(apiKey: String) {
        mdblistStore.setApiKey(apiKey)
    }

    override suspend fun setDebridEnabled(enabled: Boolean) {
        debridStore.setEnabled(enabled)
    }

    override suspend fun setDebridProviderApiKey(providerId: String, apiKey: String) {
        debridStore.setProviderApiKey(providerId, apiKey)
    }

    override suspend fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        playerStore.setFrameRateMatchingMode(mode)
    }

    override suspend fun setResolutionMatchingEnabled(enabled: Boolean) {
        playerStore.setResolutionMatchingEnabled(enabled)
    }

    override suspend fun setAudioOutputChannels(channels: AudioOutputChannels) {
        playerStore.setAudioOutputChannels(channels)
    }

    override suspend fun setResizeMode(mode: Int) {
        playerStore.setResizeMode(mode)
    }

    override suspend fun setSubtitlePreferredLanguage(language: String) {
        playerStore.setSubtitlePreferredLanguage(language)
    }

    override suspend fun setSubtitleSecondaryLanguage(language: String?) {
        playerStore.setSubtitleSecondaryLanguage(language)
    }

    override suspend fun setUseForcedSubtitles(enabled: Boolean) {
        playerStore.setUseForcedSubtitles(enabled)
    }

    override suspend fun setSubtitleShowOnlyPreferredLanguages(enabled: Boolean) {
        playerStore.setSubtitleShowOnlyPreferredLanguages(enabled)
    }

    override suspend fun setSubtitleSize(size: Int) {
        playerStore.setSubtitleSize(size)
    }

    override suspend fun setSubtitleVerticalOffset(offset: Int) {
        playerStore.setSubtitleVerticalOffset(offset)
    }

    override suspend fun setSubtitleBold(enabled: Boolean) {
        playerStore.setSubtitleBold(enabled)
    }

    override suspend fun setSubtitleOutlineEnabled(enabled: Boolean) {
        playerStore.setSubtitleOutlineEnabled(enabled)
    }

    override suspend fun setSubtitleOutlineWidth(width: Int) {
        playerStore.setSubtitleOutlineWidth(width)
    }

    override suspend fun setPreferredAudioLanguage(language: String) {
        playerStore.setPreferredAudioLanguage(language)
    }

    override suspend fun setSecondaryPreferredAudioLanguage(language: String?) {
        playerStore.setSecondaryPreferredAudioLanguage(language)
    }

    override suspend fun setDownmixEnabled(enabled: Boolean) {
        playerStore.setDownmixEnabled(enabled)
    }

    override suspend fun setMaintainOriginalAudioOnDownmix(enabled: Boolean) {
        playerStore.setMaintainOriginalAudioOnDownmix(enabled)
    }

    override suspend fun setTunnelingEnabled(enabled: Boolean) {
        playerStore.setTunnelingEnabled(enabled)
    }

    override suspend fun setForceOpticalPassthrough(enabled: Boolean) {
        playerStore.setForceOpticalPassthrough(enabled)
    }

    override suspend fun setOpenSubtitlesEnabled(enabled: Boolean) {
        playerStore.setOpenSubtitlesEnabled(enabled)
    }

    override suspend fun setOpenSubtitlesApiKey(apiKey: String) {
        playerStore.setOpenSubtitlesApiKey(apiKey)
    }

    override suspend fun setOpenSubtitlesAutoDownload(enabled: Boolean) {
        playerStore.setOpenSubtitlesAutoDownload(enabled)
    }
}
