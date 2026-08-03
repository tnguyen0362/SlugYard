@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sluggyard.tv.data.local.AutoSkipSegmentType
import com.sluggyard.tv.data.local.FrameRateMatchingMode
import com.sluggyard.tv.data.local.InternalPlayerEngine
import com.sluggyard.tv.data.local.MpvHardwareDecodeMode
import com.sluggyard.tv.data.local.NextEpisodeThresholdMode
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.StreamAutoPlayMode
import com.sluggyard.tv.data.repository.SkipInterval
import com.sluggyard.tv.domain.model.Subtitle
import com.sluggyard.tv.domain.model.WatchProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.Locale
import com.sluggyard.tv.core.player.OpenSubtitlesHasher

/**
 * Payload describing the addon subtitle lookup for the current item. `type` is
 * lowercased so downstream providers can compare against canonical keys without
 * re-normalizing on every call.
 */
internal data class SubtitleFetchRequest(
    val type: String,
    val id: String,
    val videoId: String?
)

// ---------------------------------------------------------------------------
// Subtitle fetching
// ---------------------------------------------------------------------------

/**
 * Builds the subtitle lookup request from current navigation state. Returns
 * null when the content id or content type is missing, which signals callers
 * to skip the addon subtitle pipeline entirely.
 */
internal fun PlayerRuntimeController.buildSubtitleFetchRequest(): SubtitleFetchRequest? {
    val rawType = contentType?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val rawId = contentId?.takeIf { it.isNotBlank() } ?: return null
    return SubtitleFetchRequest(type = rawType, id = rawId, videoId = currentVideoId)
}

/**
 * Fetches addon subtitles synchronously within the calling coroutine context.
 * Performs OpenSubtitles hashing when configured, and falls back to an
 * OpenSubtitles language search only when addon results do not already cover
 * the user's preferred language.
 */
internal suspend fun PlayerRuntimeController.fetchAddonSubtitlesNow(
    onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)? = null
): List<Subtitle> {
    val request = buildSubtitleFetchRequest() ?: return emptyList()
    if (subtitleMode) return addonSubtitles

    // Persist the installed addon order into UI state so subtitle badges can
    // render provider attribution even before results arrive.
    val installedAddons = runCatching { addonRepository.getInstalledAddons().firstOrNull() }
        .getOrNull()
        .orEmpty()
    _uiState.update { it.copy(installedSubtitleAddonOrder = installedAddons.map { a -> a.name }) }

    val openSubtitlesConfigured = playerSettingsDataStore.openSubtitlesEnabled.first()
    val openSubtitlesApiKey = playerSettingsDataStore.openSubtitlesApiKey.first()

    // Compute the OpenSubtitles hash up front when enabled: it doubles as the
    // video fingerprint we persist into the stream link cache for reuse.
    if (openSubtitlesConfigured && openSubtitlesApiKey.isNotBlank() && currentVideoHash == null) {
        runCatching {
            OpenSubtitlesHasher.compute(currentStreamUrl, currentHeaders)?.let { result ->
                currentVideoHash = result.hash
                currentVideoSize = result.fileSize
                persistStreamLinkCacheWithHash()
            }
        }
    }

    val addonSubs = runCatching {
        subtitleRepository.getSubtitles(
            type = request.type,
            id = request.id,
            videoId = request.videoId,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            filename = currentFilename,
            onProgress = onProgress
        )
    }.getOrDefault(emptyList())

    if (!openSubtitlesConfigured || openSubtitlesApiKey.isBlank()) return addonSubs

    // Skip the OpenSubtitles network search when an addon already covers the
    // preferred language — avoids redundant traffic and duplicate entries.
    val settings = currentPlayerSettingsForReport
    val preferred = settings.subtitleStyle.preferredLanguage
    if (preferred.isNotBlank() && preferred != "none") {
        val covered = addonSubs.any { sub ->
            PlayerSubtitleUtils.matchesLanguageCode(sub.lang, preferred)
        }
        if (covered) return addonSubs
    }

    val osLanguages = listOfNotNull(preferred.takeIf { it.isNotBlank() && it != "none" })
    if (osLanguages.isEmpty()) return addonSubs

    val osSubs = runCatching {
        openSubtitlesRepository.searchSubtitles(
            movieHash = currentVideoHash,
            imdbId = null,
            query = null,
            languages = osLanguages,
            type = request.type,
            season = currentSeason,
            episode = currentEpisode
        )
    }.getOrDefault(emptyList())

    return (addonSubs + osSubs).distinctBy { it.id }
}

internal fun PlayerRuntimeController.enableSubtitleMode() {
    subtitleMode = true
}

internal fun PlayerRuntimeController.setAddonSubtitles(subtitles: List<Subtitle>) {
    addonSubtitles = subtitles
    _uiState.update {
        it.copy(
            addonSubtitles = filterToVisibleAddonSubtitles(subtitles),
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null,
        )
    }
}

/**
 * Writes the current stream link cache entry together with the freshly
 * computed video hash. Torrent streams are keyed by info hash + file index +
 * sources; everything else is keyed by URL.
 */
private fun PlayerRuntimeController.persistStreamLinkCacheWithHash() {
    val key = streamCacheKey ?: return
    val scope = scope
    scope.launch {
        runCatching {
            streamLinkCacheDataStore.save(
                contentKey = key,
                url = currentStreamUrl,
                streamName = streamName ?: "",
                headers = currentHeaders,
                filename = currentFilename,
                videoHash = currentVideoHash,
                videoSize = currentVideoSize,
                infoHash = if (isTorrentStream) currentInfoHash else null,
                fileIdx = if (isTorrentStream) currentFileIdx else null,
                sources = if (isTorrentStream) currentTorrentSources else null,
                bingeGroup = if (isTorrentStream) currentStreamBingeGroup else null,
                contentLanguage = contentLanguage
            )
        }
    }
}

/**
 * Fire-and-forget subtitle fetch. Updates UI state with the visible subset,
 * then either restores a pending addon selection or applies the persisted track
 * preference with auto-selection.
 */
internal fun PlayerRuntimeController.fetchAddonSubtitles() {
    if (buildSubtitleFetchRequest() == null) return
    scope.launch {
        _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }
        try {
            val fetched = fetchAddonSubtitlesNow()
            val visible = filterToVisibleAddonSubtitles(fetched)
            _uiState.update {
                it.copy(
                    addonSubtitles = visible,
                    isLoadingAddonSubtitles = false
                )
            }
            val restored = pendingRestoredAddonSubtitle
            if (restored != null) {
                val matchById = visible.firstOrNull { s -> s.id == restored.id }
                val matchByLang = matchById
                    ?: visible.firstOrNull { s ->
                        PlayerSubtitleUtils.matchesLanguageCode(s.lang, restored.lang)
                    }
                if (matchByLang != null) {
                    _uiState.update { it.copy(selectedAddonSubtitle = matchByLang) }
                    autoSubtitleSelected = true
                }
            } else {
                applyPersistedTrackPreference(
                    audioTracks = _uiState.value.audioTracks,
                    subtitleTracks = _uiState.value.subtitleTracks
                )
                tryAutoSelectPreferredSubtitleFromAvailableTracks()
            }
        } catch (t: Throwable) {
            Log.e(PlayerRuntimeController.TAG, "Addon subtitle fetch failed", t)
            _uiState.update {
                it.copy(
                    isLoadingAddonSubtitles = false,
                    addonSubtitlesError = t.message ?: "Failed to load subtitles"
                )
            }
        }
    }
}

/**
 * Clears all addon-subtitle state and re-fetches from scratch for the current
 * episode. Used by the manual refresh action.
 */
internal fun PlayerRuntimeController.refreshSubtitlesForCurrentEpisode() {
    resetAddonSubtitleStateForNewStream()
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            addonSubtitlesError = null
        )
    }
    fetchAddonSubtitles()
}

/**
 * Narrows the subtitle list to the user's preferred languages when "show only
 * preferred languages" is enabled. Forced-subtitle mode derives the target
 * from the currently selected audio track; if no audio track is selected yet
 * the full list is returned so resolution can be deferred.
 */
internal fun PlayerRuntimeController.filterToVisibleAddonSubtitles(
    subtitles: List<Subtitle>
): List<Subtitle> {
    val settings = currentPlayerSettingsForReport
    if (!settings.subtitleStyle.showOnlyPreferredLanguages) return subtitles

    val preferred = settings.subtitleStyle.preferredLanguage
    val secondary = settings.subtitleStyle.secondaryPreferredLanguage
    val forced = settings.subtitleStyle.useForcedSubtitles

    val targets = mutableSetOf<String>().apply {
        if (preferred.isNotBlank() && preferred != "none") add(preferred)
        secondary?.takeIf { it.isNotBlank() && it != "none" }?.let { add(it) }
    }

    if (forced && preferred == "none") {
        val audioTrack = selectedAudioTrackForSubtitleMatching(_uiState.value)
        val audioTarget = audioTrack?.let { selectedAudioLanguageTarget(it) }
        if (audioTarget != null) {
            targets.add(audioTarget)
        } else {
            // No audio track selected yet — defer filtering so the user still
            // sees the full list until audio selection resolves.
            return subtitles
        }
    }

    if (targets.isEmpty()) return subtitles
    return subtitles.filter { sub ->
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(sub.lang, target) }
    }
}

// ---------------------------------------------------------------------------
// Episode / watch progress observers
// ---------------------------------------------------------------------------

/** Mirrors the "blur unwatched episodes" layout preference into UI state. */
internal fun PlayerRuntimeController.observeBlurUnwatchedEpisodes() {
    scope.launch {
        layoutPreferenceDataStore.blurUnwatchedEpisodes.collectLatest { enabled ->
            _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
        }
    }
}

/**
 * Observes per-episode watch progress and watched flags for series content,
 * pushing both into UI state so the episodes panel can render progress and
 * blur unwatched entries.
 */
internal fun PlayerRuntimeController.observeEpisodeWatchProgress() {
    val rawContentId = contentId ?: return
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) return
    scope.launch {
        playbackProgressSink.getAllEpisodeProgress(rawContentId).collect { progressMap ->
            _uiState.update { it.copy(episodeWatchProgressMap = progressMap) }
        }
    }
    scope.launch {
        watchedItemsPreferences.getWatchedEpisodesForContent(rawContentId).collect { watched ->
            _uiState.update { it.copy(watchedEpisodeKeys = watched) }
        }
    }
}

// ---------------------------------------------------------------------------
// Reactive settings pipeline
// ---------------------------------------------------------------------------

/**
 * Reactive settings pipeline. Every emission of [PlayerSettings] is reconciled
 * against live player/MPV state and UI state. Runs for the lifetime of the
 * controller.
 */
internal fun PlayerRuntimeController.observeSubtitleSettings() {
    scope.launch {
        playerSettingsDataStore.playerSettings.collect { settings ->
            reconcilePlayerSettings(settings)
        }
    }
}

/**
 * Pure reconciliation of a single settings emission against live player state.
 * Handles audio delay tracking, frame-rate matching, pause overlay, VOD cache,
 * MPV hardware decode, audio language preferences, subtitle preferences, skip
 * intro, parental guide, and auto-skip segment types.
 */
private fun PlayerRuntimeController.reconcilePlayerSettings(settings: PlayerSettings) {
    val previous = currentPlayerSettingsForReport
    val firstEmission = !playerSettingsInitialized
    playerSettingsInitialized = true
    currentPlayerSettingsForReport = settings

    val showOnlyPreferredChanged =
        previous.subtitleStyle.showOnlyPreferredLanguages != settings.subtitleStyle.showOnlyPreferredLanguages

    val resolvedEngine = runtimeInternalPlayerEngineOverride
        ?: resolvedAutoPlayerEngine
        ?: settings.internalPlayerEngine.let { if (it == InternalPlayerEngine.AUTO) resolveAutoInternalPlayerEngine() else it }

    val resolvedAudioAmpDb = resolveAudioAmplificationDb(settings, _uiState.value.audioAmplificationDb)
    val resolvedCenterMixDb = resolveCenterMixLevelDb(settings, _uiState.value.centerMixLevelDb)

    pushSettingsToUiState(settings, resolvedEngine, resolvedAudioAmpDb, resolvedCenterMixDb)

    if (resolvedAudioAmpDb != _uiState.value.audioAmplificationDb) applyAudioAmplification(resolvedAudioAmpDb)
    if (resolvedCenterMixDb != _uiState.value.centerMixLevelDb) applyCenterMixLevel(resolvedCenterMixDb)
    updateAudioControlAvailability()

    reconcileAudioDelayRouteTracking(settings, rememberAudioDelayPerDeviceEnabled)
    rememberAudioDelayPerDeviceEnabled = settings.rememberAudioDelayPerDevice

    bufferLogsEnabled = settings.enableBufferLogs

    // Frame-rate matching: cancel any in-flight probe when the mode goes OFF.
    if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        frameRateProbeJob?.cancel()
        frameRateProbeJob = null
    }

    reconcilePauseOverlay(settings)

    // Copy settings flags into controller fields for synchronous reads.
    streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled
    autoSwitchInternalPlayerOnErrorEnabled = settings.autoSwitchInternalPlayerOnError
    streamAutoPlayModeSetting = settings.streamAutoPlayMode
    streamAutoPlayNextEpisodeEnabledSetting = settings.streamAutoPlayNextEpisodeEnabled
    streamAutoPlayPreferBingeGroupForNextEpisodeSetting = settings.streamAutoPlayPreferBingeGroupForNextEpisode
    nextEpisodeThresholdModeSetting = settings.nextEpisodeThresholdMode
    nextEpisodeThresholdPercentSetting = settings.nextEpisodeThresholdPercent
    nextEpisodeThresholdMinutesBeforeEndSetting = settings.nextEpisodeThresholdMinutesBeforeEnd
    stillWatchingEnabledSetting = settings.stillWatchingEnabled
    stillWatchingEpisodeThresholdSetting = settings.stillWatchingEpisodeThreshold
    skipIntroEnabled = settings.skipIntroEnabled
    parentalGuideEnabled = settings.parentalGuideEnabled
    autoSkipSegmentTypes = settings.autoSkipSegmentTypes
    cachedDecoderPriority = settings.decoderPriority

    // VOD cache + parallel network configuration, only while the buffer engine
    // is enabled — otherwise the defaults remain in place.
    if (settings.bufferEngineEnabled) {
        mediaSourceFactory.vodCacheEnabled = settings.vodCacheEnabled
        mediaSourceFactory.vodCacheSizeMode = settings.vodCacheSizeMode
        mediaSourceFactory.vodCacheSizeMb = settings.vodCacheSizeMb
        mediaSourceFactory.useParallelConnections = settings.useParallelConnections
        mediaSourceFactory.parallelConnectionCount = settings.parallelConnectionCount
        mediaSourceFactory.parallelChunkSizeKb = settings.parallelChunkSizeKb
    }
    mediaSourceFactory.exoPerformanceModeEnabled = settings.exoPerformanceModeEnabled
    ExoPlayerPerformanceHelper.updateSettings(settings, context)

    reconcileMpvHardwareDecodeMode(settings)
    reconcilePreferredAudioLanguages(settings)
    reconcileMpvAudioOutputSettings(settings)

    // Subtitle preferences: re-run auto-select when language/forced changed.
    val subStyle = settings.subtitleStyle
    val subtitlePrefsChanged =
        lastSubtitlePreferredLanguage != subStyle.preferredLanguage ||
            lastSubtitleSecondaryLanguage != subStyle.secondaryPreferredLanguage ||
            lastUseForcedSubtitles != subStyle.useForcedSubtitles
    lastSubtitlePreferredLanguage = subStyle.preferredLanguage
    lastSubtitleSecondaryLanguage = subStyle.secondaryPreferredLanguage
    lastUseForcedSubtitles = subStyle.useForcedSubtitles
    if (subtitlePrefsChanged) {
        applySubtitlePreferences(subStyle.preferredLanguage, subStyle.secondaryPreferredLanguage)
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
    }

    if (showOnlyPreferredChanged) reconcileShowOnlyPreferredLanguages(settings)

    reconcileSkipIntervals(settings, previous.skipIntroEnabled, firstEmission)

    // First emission: kick off parental guide fetch if enabled.
    if (firstEmission && settings.parentalGuideEnabled) {
        fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    }
}

/** Resolves the audio amplification dB on first session init vs subsequent changes. */
private fun PlayerRuntimeController.resolveAudioAmplificationDb(
    settings: PlayerSettings,
    currentDb: Int
): Int {
    if (!hasInitializedAudioAmplificationForSession) {
        hasInitializedAudioAmplificationForSession = true
        return settings.audioAmplificationDb
    }
    return if (settings.audioAmplificationDb != currentDb) settings.audioAmplificationDb else currentDb
}

/** Resolves the center mix level dB on first session init vs subsequent changes. */
private fun PlayerRuntimeController.resolveCenterMixLevelDb(
    settings: PlayerSettings,
    currentDb: Int
): Int {
    if (!hasInitializedCenterMixForSession) {
        hasInitializedCenterMixForSession = true
        return settings.centerMixLevelDb
    }
    return if (settings.centerMixLevelDb != currentDb) settings.centerMixLevelDb else currentDb
}

/** Writes resolved settings values into _uiState (subtitle style, overlays, OSD). */
private fun PlayerRuntimeController.pushSettingsToUiState(
    settings: PlayerSettings,
    resolvedEngine: InternalPlayerEngine,
    resolvedAudioAmpDb: Int,
    resolvedCenterMixDb: Int
) {
    _uiState.update { s ->
        s.copy(
            subtitleStyle = settings.subtitleStyle,
            loadingOverlayEnabled = settings.loadingOverlayEnabled,
            showPlayerLoadingStatus = settings.showPlayerLoadingStatus,
            playbackIssueReportsEnabled = settings.playbackIssueReportsEnabled,
            pauseOverlayEnabled = settings.pauseOverlayEnabled,
            osdClockEnabled = settings.osdClockEnabled,
            audioAmplificationDb = resolvedAudioAmpDb,
            persistAudioAmplification = settings.persistAudioAmplification,
            centerMixLevelDb = resolvedCenterMixDb,
            isAudioAmplificationAvailable = settings.audioAmplificationDb != 0 || resolvedAudioAmpDb != 0,
            isCenterMixAvailable = settings.centerMixLevelDb != 0 || resolvedCenterMixDb != 0,
            // MPV has no ExoPlayer tunneling path; expose the effective value,
            // not the persisted preference, to controls and diagnostics.
            tunnelingEnabled = settings.tunnelingEnabled && resolvedEngine == InternalPlayerEngine.EXOPLAYER,
            internalPlayerEngine = resolvedEngine,
            useLibass = settings.useLibass,
            libassRenderType = settings.libassRenderType,
            frameRateMatchingMode = settings.frameRateMatchingMode,
            resizeMode = settings.resizeMode,
            streamAutoPlayMode = settings.streamAutoPlayMode,
            streamAutoPlayNextEpisodeEnabled = settings.streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayPreferBingeGroupForNextEpisode = settings.streamAutoPlayPreferBingeGroupForNextEpisode
        )
    }
}

/** Applies persisted channel-layout/downmix settings to the active MPV backend. */
private fun PlayerRuntimeController.reconcileMpvAudioOutputSettings(settings: PlayerSettings) {
    if (!isUsingMpvEngine()) return
    mpvView?.applyAudioOutputSettings(
        downmixEnabled = settings.downmixEnabled,
        outputChannels = settings.audioOutputChannels,
        maintainOriginalMix = settings.maintainOriginalAudioOnDownmix,
    )
}

/** Registers/unregisters the audio route callback when the per-device setting changes. */
private fun PlayerRuntimeController.reconcileAudioDelayRouteTracking(
    settings: PlayerSettings,
    wasRemembering: Boolean
) {
    val nowRemembering = settings.rememberAudioDelayPerDevice
    if (nowRemembering == wasRemembering) return
    if (nowRemembering) {
        registerAudioDelayRouteCallback()
        scope.launch { applyStoredAudioDelayForCurrentRouteIfEnabled() }
    } else {
        unregisterAudioDelayRouteCallback()
    }
}

/** Cancels the pause overlay when disabled; schedules it when paused with first frame. */
private fun PlayerRuntimeController.reconcilePauseOverlay(settings: PlayerSettings) {
    if (!settings.pauseOverlayEnabled) {
        cancelPauseOverlay()
        return
    }
    if (userPausedManually && hasRenderedFirstFrame && !_uiState.value.isPlaying) {
        schedulePauseOverlay()
    }
}

/** Updates the MPV hardware decode mode setting and applies it live to mpvView. */
private fun PlayerRuntimeController.reconcileMpvHardwareDecodeMode(settings: PlayerSettings) {
    val previous = mpvHardwareDecodeModeSetting
    mpvHardwareDecodeModeSetting = settings.mpvHardwareDecodeMode
    if (previous != settings.mpvHardwareDecodeMode && isUsingMpvEngine()) {
        mpvView?.applyHardwareDecodeMode(settings.mpvHardwareDecodeMode)
    }
}

/** Re-resolves preferred audio languages from settings and applies them to MPV. */
private fun PlayerRuntimeController.reconcilePreferredAudioLanguages(settings: PlayerSettings) {
    val deviceLanguages = resolveDeviceAudioLanguages()
    val resolved = resolvePreferredAudioLanguages(
        preferredAudioLanguage = settings.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
        deviceLanguages = deviceLanguages,
        contentOriginalLanguage = contentLanguage
    )
    mpvPreferredAudioLanguages = resolved
    if (isUsingMpvEngine()) {
        mpvView?.applySubtitleLanguagePreferences(
            preferred = settings.subtitleStyle.preferredLanguage,
            secondary = settings.subtitleStyle.secondaryPreferredLanguage,
            preferAss = shouldPreferAssSubtitles(),
        )
    }
}

/** Filters visible addon subtitles when show-only is enabled; re-fetches when disabled. */
private fun PlayerRuntimeController.reconcileShowOnlyPreferredLanguages(settings: PlayerSettings) {
    if (!settings.subtitleStyle.showOnlyPreferredLanguages) {
        if (_uiState.value.addonSubtitles.isEmpty()) fetchAddonSubtitles()
        return
    }
    val filtered = filterToVisibleAddonSubtitles(_uiState.value.addonSubtitles)
    _uiState.update { it.copy(addonSubtitles = filtered) }
}

/** Clears skip intervals when the feature is off; fetches when newly enabled. */
private fun PlayerRuntimeController.reconcileSkipIntervals(
    settings: PlayerSettings,
    skipIntroWasEnabled: Boolean,
    firstEmission: Boolean
) {
    if (!settings.skipIntroEnabled) {
        if (skipIntervals.isNotEmpty()) skipIntervals = emptyList()
        skipIntroFetchedKey = null
        skipIntroInFlightKey = null
        return
    }
    // The reported-settings snapshot already defaults skipIntroEnabled to true, so the
    // "newly enabled" edge never fired for a fresh session — AniSkip/IntroDB intervals were
    // only fetched after an in-player source or episode switch. fetchSkipIntervals dedupes
    // by lookup key, so requesting on the first emission is safe.
    if (firstEmission || !skipIntroWasEnabled) {
        fetchSkipIntervals(currentVideoId ?: contentId, currentSeason, currentEpisode)
    }
}

// ---------------------------------------------------------------------------
// Watch progress loading
// ---------------------------------------------------------------------------

/** Fire-and-forget variant: loads saved progress and seeks or queues it. */
internal fun PlayerRuntimeController.loadSavedProgressFor(season: Int?, episode: Int?) {
    scope.launch {
        loadSavedProgressSuspend(season, episode)
        applyResumeToActivePlayer()
    }
}

/**
 * Suspend variant that completes the DB read inline. Must be called BEFORE
 * [initializePlayer] so [pendingResumeProgress] is guaranteed set before
 * STATE_READY fires — otherwise the resume seek is silently dropped.
 */
internal suspend fun PlayerRuntimeController.loadSavedProgressSuspend(season: Int?, episode: Int?) {
    pendingResumeProgress = null
    val progress = loadProgressFromStore(season, episode) ?: return
    if (progress.isInProgress()) pendingResumeProgress = progress
}

/** Loads watch progress from the repository — episode-specific or movie-level. */
private suspend fun PlayerRuntimeController.loadProgressFromStore(
    season: Int?,
    episode: Int?
): WatchProgress? {
    val rawContentId = contentId ?: return null
    return if (season != null && episode != null) {
        playbackProgressSink.getEpisodeProgress(rawContentId, season, episode).firstOrNull()
    } else {
        playbackProgressSink.getProgress(rawContentId).firstOrNull()
    }
}

/** Applies pending resume progress to the active player (MPV or ExoPlayer). */
private fun PlayerRuntimeController.applyResumeToActivePlayer() {
    val progress = pendingResumeProgress ?: return
    val durationHint = progress.duration.coerceAtLeast(0L)
    val target = progress.resolveResumePosition(durationHint)
    if (target <= 0L) {
        // Percent-only without a usable duration — keep pending for duration-aware seek paths.
        if (progress.progressPercent != null && durationHint <= 0L) return
        pendingResumeProgress = null
        return
    }
    if (isUsingMpvEngine()) {
        mpvView?.let { applyPendingMpvSeekIfNeeded(it, target, durationHint) }
    } else {
        val player = _exoPlayer ?: return
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            player.seekTo(target)
        }
    }
    pendingResumeProgress = null
    _uiState.update { it.copy(pendingSeekPosition = null) }
}

// ---------------------------------------------------------------------------
// Skip intervals
// ---------------------------------------------------------------------------

/**
 * Fetches skip-interval metadata (intro/outro/preview). Routes to MAL, Kitsu,
 * or IMDb endpoints based on the content id format. Deduplicates via a cached
 * key so repeated calls for the same item are no-ops.
 */
internal fun PlayerRuntimeController.fetchSkipIntervals(id: String?, season: Int?, episode: Int?) {
    if (!skipIntroEnabled) return
    val effectiveId = (id?.takeIf { it.isNotBlank() } ?: currentVideoId ?: contentId) ?: return
    val lookupId = normalizeSkipLookupId(effectiveId)
    val cacheKey = "$lookupId:${season ?: 0}:${episode ?: 0}"
    if (skipIntroFetchedKey == cacheKey || skipIntroInFlightKey == cacheKey) return
    skipIntroInFlightKey = cacheKey

    when {
        tryFetchMalSkipIntervals(lookupId, episode, cacheKey) -> return
        tryFetchKitsuSkipIntervals(lookupId, episode, cacheKey) -> return
        tryFetchImdbSkipIntervals(lookupId, season, episode, cacheKey) -> return
        else -> skipIntroInFlightKey = null
    }
}

private fun PlayerRuntimeController.completeSkipLookup(cacheKey: String, result: List<SkipInterval>) {
    skipIntroInFlightKey = null
    skipIntroFetchedKey = cacheKey.takeIf { result.isNotEmpty() }
    skipIntervals = result
    // Evaluate immediately against the live playhead so Skip Intro can appear without
    // waiting for the next progress poll (500ms–1s), which stacks on top of network latency.
    val positionMs = currentPlaybackPositionMs()?.coerceAtLeast(0L)
        ?: playbackTimeline.value.currentPosition
    updateActiveSkipInterval(positionMs)
}

/** Stremio episode IDs may append :season:episode to an otherwise valid IMDb series ID. */
internal fun normalizeSkipLookupId(id: String): String =
    if (id.startsWith("tt", ignoreCase = true)) id.substringBefore(':') else id

private fun PlayerRuntimeController.tryFetchMalSkipIntervals(
    effectiveId: String,
    episode: Int?,
    cacheKey: String,
): Boolean {
    if (!effectiveId.startsWith("mal:", ignoreCase = true)) return false
    val malId = effectiveId.removePrefix("mal:").removePrefix("MAL:")
    val ep = episode ?: currentEpisode ?: run {
        skipIntroInFlightKey = null
        return true
    }
    scope.launch {
        val result = withTimeoutOrNull(15_000L) {
            skipIntroRepository.getSkipIntervalsForMal(malId, ep)
        }.orEmpty()
        completeSkipLookup(cacheKey, result)
    }
    return true
}

private fun PlayerRuntimeController.tryFetchKitsuSkipIntervals(
    effectiveId: String,
    episode: Int?,
    cacheKey: String,
): Boolean {
    if (!effectiveId.startsWith("kitsu:", ignoreCase = true)) return false
    val kitsuId = effectiveId.substringAfter("kitsu:", "").ifEmpty { return false }
    val ep = episode ?: currentEpisode ?: run {
        skipIntroInFlightKey = null
        return true
    }
    scope.launch {
        val result = withTimeoutOrNull(15_000L) {
            skipIntroRepository.getSkipIntervalsForKitsu(kitsuId, ep)
        }.orEmpty()
        completeSkipLookup(cacheKey, result)
    }
    return true
}

private fun PlayerRuntimeController.tryFetchImdbSkipIntervals(
    effectiveId: String,
    season: Int?,
    episode: Int?,
    cacheKey: String,
): Boolean {
    if (!effectiveId.startsWith("tt", ignoreCase = true)) return false
    val s = season ?: currentSeason ?: 0
    val e = episode ?: currentEpisode ?: 0
    scope.launch {
        val result = withTimeoutOrNull(15_000L) {
            skipIntroRepository.getSkipIntervals(effectiveId, s, e)
        }.orEmpty()
        completeSkipLookup(cacheKey, result)
    }
    return true
}

// ---------------------------------------------------------------------------
// Resume position resolution
// ---------------------------------------------------------------------------

/** Seeks the active player to the saved resume position (if any), then clears it. */
internal fun PlayerRuntimeController.tryApplyPendingResumeProgress(player: Player) {
    val progress = pendingResumeProgress ?: return
    val durationHint = player.duration.takeIf { it > 0L } ?: progress.duration
    val target = progress.resolveResumePosition(durationHint.coerceAtLeast(0L))
    if (target <= 0L) {
        // Percent-only without duration yet — keep pending for MPV/Exo once length is known.
        if (progress.progressPercent != null && (progress.duration <= 0L && durationHint <= 0L)) {
            return
        }
        pendingResumeProgress = null
        return
    }
    if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
        pendingResumeProgress = null
        return
    }
    player.seekTo(target)
    pendingResumeProgress = null
    _uiState.update { it.copy(pendingSeekPosition = null) }
}

/**
 * Computes the resume position to apply at player build time without
 * consuming pending state. Returns 0 when there is no useful target.
 */
internal fun PlayerRuntimeController.resolvePendingInitialResumePosition(): Long {
    val progress = pendingResumeProgress ?: return 0L
    // Prefer absolute ms; fall back to percent×duration (Trakt-style) when position is 0.
    return progress.resolveResumePosition(progress.duration.coerceAtLeast(0L))
}

/** Drops pending resume progress and matching UI pending-seek state. */
internal fun PlayerRuntimeController.clearPendingInitialResumePosition() {
    pendingResumeProgress = null
    _uiState.update { it.copy(pendingSeekPosition = null) }
}

// ---------------------------------------------------------------------------
// Deferred retry mechanism + retryCurrentStream* wrappers
// ---------------------------------------------------------------------------

/**
 * Core retry mechanism. Cancels watchdogs, sets the pending seek position,
 * cancels any existing deferred job, then launches a new coroutine that yields
 * first (so the caller can finish), checks the generation and URL haven't
 * changed, releases the player, and re-initializes via [initializePlayer].
 */
private fun PlayerRuntimeController.scheduleDeferredPlayerReinitialize(
    fromPositionMs: Long,
    clearResumeProgress: Boolean = false
) {
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    if (clearResumeProgress) pendingResumeProgress = null

    _uiState.update {
        it.copy(
            pendingSeekPosition = fromPositionMs.takeIf { p -> p > 0L },
            error = null,
            showLoadingOverlay = true,
            isBuffering = true
        )
    }

    deferredPlayerRecoveryJob?.cancel()
    val generation = ++playbackRecoveryGeneration
    val recoveryUrl = currentStreamUrl
    val recoveryHeaders = currentHeaders
    deferredPlayerRecoveryJob = scope.launch {
        yield()
        if (playbackRecoveryGeneration != generation) return@launch
        if (currentStreamUrl != recoveryUrl) return@launch
        try {
            releasePlayer(flushPlaybackState = false)
            initializePlayer(
                url = recoveryUrl,
                headers = recoveryHeaders,
                allowEngineFailover = false
            )
        } catch (t: Throwable) {
            Log.e(PlayerRuntimeController.TAG, "Deferred player reinitialize failed", t)
            _uiState.update {
                it.copy(
                    error = t.message ?: "Playback recovery failed",
                    showLoadingOverlay = false,
                    isBuffering = false
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamFromStartAfter416() {
    if (hasRetriedCurrentStreamAfter416) return
    hasRetriedCurrentStreamAfter416 = true
    scheduleDeferredPlayerReinitialize(fromPositionMs = 0L, clearResumeProgress = true)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterTimeout(fromPositionMs: Long) {
    if (timeoutRecoveryAttempts >= PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS) return
    timeoutRecoveryAttempts++
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterUnexpectedNpe(fromPositionMs: Long) {
    if (hasRetriedCurrentStreamAfterUnexpectedNpe) return
    hasRetriedCurrentStreamAfterUnexpectedNpe = true
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamAfterMediaPeriodHolderCrash(fromPositionMs: Long) {
    if (hasRetriedCurrentStreamAfterMediaPeriodHolderCrash) return
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = true
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamAfterDecoderResourcesReclaimed(fromPositionMs: Long) {
    val url = currentStreamUrl
    if (decoderResourcesReclaimedRecoveryUrl == url) return
    decoderResourcesReclaimedRecoveryUrl = url
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithSafeAudioFallback(fromPositionMs: Long) {
    safeAudioForcedStreamUrls.add(currentStreamUrl)
    isSafeAudioModeActiveForCurrentPlayback = true
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithAudioDisabled(fromPositionMs: Long) {
    audioDisabledForcedStreamUrls.add(currentStreamUrl)
    isAudioDisabledForCurrentPlayback = true
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDolbyVisionFallback(fromPositionMs: Long) {
    dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
    isMapDv7ToHevcActiveForCurrentPlayback = true
    hasTriedDv7HevcFallback = true
    scheduleDeferredPlayerReinitialize(fromPositionMs, clearResumeProgress = true)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDv7Mode1Fallback(fromPositionMs: Long) {
    dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
    scheduleDeferredPlayerReinitialize(fromPositionMs, clearResumeProgress = true)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithVc1SoftwareFallback(fromPositionMs: Long) {
    vc1SoftwarePreferredStreamUrls.add(currentStreamUrl)
    isVc1SoftwareFallbackActiveForCurrentPlayback = true
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithVc1TrackSelectionBypass(fromPositionMs: Long) {
    vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
    isVc1TrackSelectionBypassActiveForCurrentPlayback = true
    scheduleDeferredPlayerReinitialize(fromPositionMs)
}

// ---------------------------------------------------------------------------
// Watchdogs
// ---------------------------------------------------------------------------

/** Cancels the first-frame watchdog job and nulls the reference. */
internal fun PlayerRuntimeController.cancelFirstFrameWatchdog() {
    firstFrameWatchdogJob?.cancel()
    firstFrameWatchdogJob = null
}

/** Cancels the stall watchdog job and nulls the reference. */
internal fun PlayerRuntimeController.cancelStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = null
}

/**
 * Schedules a watchdog that monitors [ExoPlayer.getBufferedPosition] during
 * STATE_BUFFERING. If it stalls for the threshold duration, seeks just past
 * the buffered edge to break a stuck Range request.
 */
internal fun PlayerRuntimeController.maybeScheduleStallWatchdog() {
    if (stallWatchdogJob != null) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_BUFFERING) return
    stallWatchdogJob = scope.launch {
        var lastBuffered = player.bufferedPosition
        var stalledSinceMs = 0L
        while (true) {
            delay(PlayerRuntimeController.STALL_WATCHDOG_POLL_INTERVAL_MS)
            val current = _exoPlayer ?: return@launch
            if (current.playbackState != Player.STATE_BUFFERING) return@launch
            val bufferedNow = current.bufferedPosition
            if (bufferedNow > lastBuffered) {
                lastBuffered = bufferedNow
                stalledSinceMs = 0L
            } else {
                stalledSinceMs += PlayerRuntimeController.STALL_WATCHDOG_POLL_INTERVAL_MS
                if (stalledSinceMs >= PlayerRuntimeController.STALL_WATCHDOG_THRESHOLD_MS) {
                    if (attemptStallWatchdogSelfSeek(current, bufferedNow, stalledSinceMs)) return@launch
                    lastBuffered = current.bufferedPosition
                    stalledSinceMs = 0L
                }
            }
        }
    }
}

/**
 * Guards before seeking: checks duration is known, buffered is ahead of the
 * playhead, and the target is a forward seek. Returns true when the self-seek
 * was issued.
 */
private fun PlayerRuntimeController.attemptStallWatchdogSelfSeek(
    livePlayer: ExoPlayer,
    bufferedNow: Long,
    stalledForMs: Long
): Boolean {
    val duration = livePlayer.duration
    if (duration == C.TIME_UNSET) return false
    val playhead = livePlayer.currentPosition
    if (bufferedNow <= playhead) return false
    val target = (bufferedNow + PlayerRuntimeController.STALL_WATCHDOG_SKIP_PAST_BUFFERED_MS).coerceAtMost(duration)
    if (target <= playhead) return false
    Log.i(PlayerRuntimeController.TAG, "Stall watchdog: self-seek past buffered edge (+${stalledForMs}ms)")
    livePlayer.seekTo(target)
    return true
}

/**
 * Schedules the first-frame watchdog. If no frame arrives within the timeout,
 * nudges playback, then tries DV7 mode 1, VC1 software, and VC1 track-selection
 * bypass fallbacks in order.
 */
internal fun PlayerRuntimeController.maybeScheduleFirstFrameWatchdog() {
    if (hasRenderedFirstFrame) return
    if (!currentStreamHasVideoTrack) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_READY) return
    if (firstFrameWatchdogJob != null) return
    firstFrameWatchdogJob = scope.launch {
        delay(PlayerRuntimeController.FIRST_FRAME_TIMEOUT_MS)
        if (hasRenderedFirstFrame) return@launch
        val current = _exoPlayer ?: return@launch
        if (current.playbackState != Player.STATE_READY) return@launch

        if (!current.isPlaying && !userPausedManually) {
            current.playWhenReady = true
            current.play()
            return@launch
        }
        if (userPausedManually) return@launch

        val position = current.currentPosition.coerceAtLeast(0L)
        if (tryDv7Mode1FallbackFromFirstFrame(position)) return@launch
        if (tryVc1SoftwareFallbackFromFirstFrame(position)) return@launch
        tryVc1TrackSelectionBypassFromFirstFrame(position)
    }
}

/** Returns true when the DV7 mode 1 fallback was scheduled. */
private fun PlayerRuntimeController.tryDv7Mode1FallbackFromFirstFrame(currentPosition: Long): Boolean {
    if (!isExperimentalDv7ToDv81ActiveForCurrentPlayback && !isManualDv81Mode2ActiveForCurrentPlayback) return false
    if (dv7Mode1ForcedStreamUrls.contains(currentStreamUrl)) return false
    retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
    return true
}

/** Returns true when the VC1 software fallback was scheduled. */
private fun PlayerRuntimeController.tryVc1SoftwareFallbackFromFirstFrame(currentPosition: Long): Boolean {
    if (!currentVideoTrackIsLikelyVc1) return false
    if (isVc1SoftwareFallbackActiveForCurrentPlayback) return false
    if (vc1SoftwarePreferredStreamUrls.contains(currentStreamUrl)) return false
    retryCurrentStreamWithVc1SoftwareFallback(currentPosition)
    return true
}

private fun PlayerRuntimeController.tryVc1TrackSelectionBypassFromFirstFrame(currentPosition: Long): Boolean {
    if (!currentVideoTrackIsLikelyVc1) return false
    if (isVc1TrackSelectionBypassActiveForCurrentPlayback) return false
    if (vc1TrackSelectionBypassStreamUrls.contains(currentStreamUrl)) return false
    retryCurrentStreamWithVc1TrackSelectionBypass(currentPosition)
    return true
}

// ---------------------------------------------------------------------------
// Device-local aspect mode observer
// ---------------------------------------------------------------------------

/** Observes device-local aspect mode preference and pushes changes into UI state. */
internal fun PlayerRuntimeController.observeDeviceLocalAspectMode() {
    scope.launch {
        deviceLocalPlayerPreferences.aspectMode
            .distinctUntilChanged()
            .collect { mode ->
                _uiState.update { it.copy(aspectMode = mode) }
            }
    }
}
