@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import com.sluggyard.tv.R
import com.sluggyard.tv.core.player.LastPlaybackDiagnostics
import com.sluggyard.tv.data.local.SubtitleStyleSettings
import com.sluggyard.tv.data.repository.PlaybackIssueErrorInput
import com.sluggyard.tv.data.repository.PlaybackIssuePlaybackSettingsInput
import com.sluggyard.tv.data.repository.PlaybackIssueReportInput
import com.sluggyard.tv.data.repository.SkipInterval
import com.sluggyard.tv.data.repository.TraktScrobbleItem
import com.sluggyard.tv.data.repository.extractYear
import com.sluggyard.tv.data.repository.parseContentIds
import com.sluggyard.tv.data.repository.resolveEffectiveContentId
import com.sluggyard.tv.data.repository.toTraktIds
import com.sluggyard.tv.domain.model.WatchProgress
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics

internal const val AUDIO_AMPLIFICATION_MIN_DB = 0
internal const val AUDIO_AMPLIFICATION_MAX_DB = 10
internal const val CENTER_MIX_LEVEL_MIN_DB = -10
internal const val CENTER_MIX_LEVEL_MAX_DB = 30
internal const val AUDIO_DELAY_MIN_MS = -3000
internal const val AUDIO_DELAY_MAX_MS = 3000
internal const val AUDIO_DELAY_STEP_MS = 25

/** Controls auto-hide delay for the player controls bar. */
private const val CONTROLS_AUTO_HIDE_MS = 3_000L
/** Interval at which watch progress is periodically persisted. */
private const val WATCH_PROGRESS_SAVE_INTERVAL_MS = 10_000L
/** Minimum position delta (ms) before a periodic progress save is triggered. */
private const val PROGRESS_SAVE_THRESHOLD_MS = 1L
/** Threshold below which a stream is treated as a placeholder/error clip, not real content. */
private const val SHORT_PLACEHOLDER_DURATION_MAX_MS = 120_999L
private const val SHORT_PLACEHOLDER_DURATION_MIN_MS = 1
/** Buffer log cadence for ExoPlayer diagnostics. */
private const val BUFFER_LOG_INTERVAL_MS = 10_000L
/** Progress loop cadence for ExoPlayer. */
private const val EXO_PROGRESS_INTERVAL_MS = 500L
/** Progress loop cadence for libmpv before the first frame renders. */
private const val MPV_STARTUP_PROGRESS_INTERVAL_MS = 750L
/** Progress loop cadence for libmpv after the first frame renders. */
private const val MPV_STEADY_PROGRESS_INTERVAL_MS = 1_000L
/**
 * Extra discovery time after first frame. Debrid/MKV loads often emit `file-loaded`
 * (and therefore track-list) only after first decode, past the initial window.
 */
private const val MPV_TRACK_DISCOVERY_AFTER_FIRST_FRAME_MS = 12_000L
/** Hard ceiling so we never JNI-scan track-list for an entire film. */
private const val MPV_TRACK_DISCOVERY_ABSOLUTE_MAX_MS = 45_000L
/** Tolerance used when detecting playback completion against the duration. */
private const val ENDED_TOLERANCE_MS = 500L
/** Resume-from-pause seeks back this far to avoid re-playing a just-heard beat. */
private const val LONG_PAUSE_BACK_SEEK_MS = 1_000L
/** Aspect-ratio / speed indicator auto-dismiss delay. */
private const val ASPECT_INDICATOR_DISMISS_MS = 1_500L

internal fun PlayerRuntimeController.applyAudioDelay(
    delayMs: Int,
    persistForCurrentRoute: Boolean = true
) {
    val clamped = delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    audioDelayUs.set(clamped.toLong() * 1_000L)
    _uiState.update { it.copy(audioDelayMs = clamped) }
    if (persistForCurrentRoute) persistAudioDelayForCurrentRoute(clamped)
}

internal fun PlayerRuntimeController.skipActiveInterval(): Boolean {
    val interval = _uiState.value.activeSkipInterval ?: return false
    return skipInterval(interval)
}

internal fun PlayerRuntimeController.skipInterval(interval: SkipInterval): Boolean {
    // Unknown duration → do not seek to Long.MAX_VALUE (native seek crash risk).
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: return false
    if (!interval.endTime.isFinite() || interval.endTime < 0.0) return false
    val rawTargetMs = if (interval.endTime == Double.MAX_VALUE) {
        duration
    } else {
        (interval.endTime * 1_000.0).toLong()
    }
    if (rawTargetMs <= 0L) return false
    val targetMs = rawTargetMs.coerceIn(0L, duration)
    runCatching {
        seekPlaybackTo(targetMs, SeekParameters.NEXT_SYNC)
        scheduleProgressSyncAfterSeek()
    }.onFailure { failure ->
        android.util.Log.w("PlayerRuntime", "Skip intro seek failed: ${failure.message}", failure)
        return false
    }
    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
    return true
}

internal fun PlayerRuntimeController.applyAudioAmplification(db: Int) {
    val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val available = isUsingMpvEngine() || _exoPlayer != null
    val wasActive = gainAudioProcessor.isGainEnabled()
    gainAudioProcessor.setGainDb(if (available) clampedDb else AUDIO_AMPLIFICATION_MIN_DB)
    val isActiveNow = gainAudioProcessor.isGainEnabled()

    if (wasActive != isActiveNow && !isUsingMpvEngine()) {
        playbackSpeedAwareAudioSink?.notifyAudioProcessingRequirementChanged()
        _exoPlayer?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        }
    }

    if (isUsingMpvEngine()) {
        mpvView?.applyAudioAmplificationDb(clampedDb)
    }
    _uiState.update {
        it.copy(audioAmplificationDb = clampedDb, isAudioAmplificationAvailable = available)
    }
}

internal fun PlayerRuntimeController.applyCenterMixLevel(db: Int) {
    val clampedDb = db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    ffmpegAudioRenderer?.setCenterMixLevelDb(clampedDb)
    _uiState.update { state -> state.copy(centerMixLevelDb = clampedDb) }
}

internal fun PlayerRuntimeController.updateAudioControlAvailability(
    audioTracks: List<TrackInfo> = _uiState.value.audioTracks,
    selectedAudioIndex: Int = _uiState.value.selectedAudioTrackIndex
) {
    val selectedTrack = audioTracks.getOrNull(selectedAudioIndex)
    val amplificationAvailable = isUsingMpvEngine() || _exoPlayer != null
    val centerMixAvailable = ffmpegAudioRenderer?.isCenterMixActive() == true &&
        (selectedTrack?.channelCount ?: 0) > 2
    val clampedDb = _uiState.value.audioAmplificationDb
        .coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    gainAudioProcessor.setGainDb(if (amplificationAvailable) clampedDb else AUDIO_AMPLIFICATION_MIN_DB)
    _uiState.update { state ->
        state.copy(
            isAudioAmplificationAvailable = amplificationAvailable,
            isCenterMixAvailable = centerMixAvailable
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayStateAfterPlaybackEnded() {
    if (!shouldResetPostPlayStateAfterPlaybackEnded(
            state = _uiState.value,
            hasInFlightNextEpisodeAutoPlay = nextEpisodeAutoPlayJob?.isActive == true
        )
    ) {
        return
    }

    // If auto-play is enabled and the user dismissed the card earlier, still
    // auto-play the next episode when playback ends naturally.
    val state = _uiState.value
    if (state.postPlayDismissedForCurrentEpisode &&
        streamAutoPlayNextEpisodeEnabledSetting &&
        state.nextEpisode?.hasAired == true &&
        nextEpisodeVideo != null
    ) {
        playNextEpisode()
        return
    }

    resetPostPlayOverlayState(clearEpisode = false)
}

internal fun shouldResetPostPlayStateAfterPlaybackEnded(
    state: PlayerUiState,
    hasInFlightNextEpisodeAutoPlay: Boolean
): Boolean {
    if (state.postPlayMode?.blocksNaturalCompletion() == true) return false
    if (hasInFlightNextEpisodeAutoPlay) return false
    return true
}

internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            if (isUsingMpvEngine()) {
                sampleMpvProgress()
                // libmpv property reads are synchronous JNI calls. Sampling at
                // 2 Hz while the decoder configures caused visible jank on
                // lower-power Android TV hardware. A 750 ms startup cadence
                // still discovers delayed ASS tracks promptly; after the first
                // frame a 1 Hz clock is enough for the TV progress UI and
                // halves steady-state JNI/UI work.
                delay(if (hasRenderedFirstFrame) MPV_STEADY_PROGRESS_INTERVAL_MS else MPV_STARTUP_PROGRESS_INTERVAL_MS)
                continue
            }
            sampleExoProgress()
            delay(EXO_PROGRESS_INTERVAL_MS)
        }
    }
}

/** Single libmpv progress sample: position, buffering, first-frame, tracks, post-play. */
private fun PlayerRuntimeController.sampleMpvProgress() {
    val view = mpvView ?: return
    val pos = view.currentPositionMs().coerceAtLeast(0L)
    val duration = view.durationMs().coerceAtLeast(0L)
    applyPendingMpvSeekIfNeeded(view = view, currentPositionMs = pos, durationMs = duration)
    val playingNow = view.isPlayingNow()
    val cacheBuffering = view.isPausedForCacheNow() || view.isCoreIdleNow()
    var firstFrameReady = hasRenderedFirstFrame
    if (!firstFrameReady) {
        firstFrameReady = pos > 0L || (playingNow && !cacheBuffering && duration > 0L)
        if (firstFrameReady) {
            hasRenderedFirstFrame = true
            // Debrid MKV track-list often lands at/after first decode. Keep polling
            // past the initial window so UI state can catch ASS/softsubs MPV already paints.
            val extendedDeadline =
                android.os.SystemClock.elapsedRealtime() + MPV_TRACK_DISCOVERY_AFTER_FIRST_FRAME_MS
            if (extendedDeadline > mpvTrackDiscoveryDeadlineElapsedMs) {
                mpvTrackDiscoveryDeadlineElapsedMs = extendedDeadline
            }
            val clickToFirstFrameMs = launchStartedAtElapsedMs
                ?.let { (android.os.SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                ?: -1L
            val initToFirstFrameMs = (System.currentTimeMillis() - playerInitializationStartedAtMs).coerceAtLeast(0L)
            playbackAnalyticsDiagnostics.recordRawEventLine(
                "PLAYBACK_STARTUP: clickToFirstFrameMs=$clickToFirstFrameMs " +
                    "initToFirstFrameMs=$initToFirstFrameMs playbackSpeed=${_uiState.value.playbackSpeed} " +
                    "currentPositionMs=$pos durationMs=$duration engine=MPV " +
                    "host=${currentStreamUrl.playbackEventsHost()}"
            )
            finishLoadingDiagnostics("mpv_first_frame_ready")
            if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
            }
        }
    }
    if (duration > lastKnownDuration) {
        lastKnownDuration = duration
    }
    val displayPosition = pendingPreviewSeekPosition ?: pos
    updatePlaybackTimeline(currentPosition = displayPosition, duration = duration)
    val ended = duration > 0L && pos >= (duration - ENDED_TOLERANCE_MS)
    val wasEnded = _uiState.value.playbackEnded
    _uiState.update { state ->
        state.copy(
            isPlaying = playingNow,
            isBuffering = !firstFrameReady || cacheBuffering,
            showLoadingOverlay = if (state.loadingOverlayEnabled) !firstFrameReady else false,
            // Snap the loading-logo fill to 100% once playback is ready so the
            // logo finishes filling on dismissal.
            loadingProgress = if (firstFrameReady && state.loadingProgress != null) 1f else state.loadingProgress,
            playbackEnded = ended
        )
    }
    // Tracks arrive after loadfile / first decode. Keep refreshing until we have
    // observed embedded tracks (or the absolute ceiling), not only the initial window —
    // otherwise UI stays on "Off" while libmpv still renders softsubs.
    if (shouldRefreshMpvTrackDiscovery()) {
        updateMpvAvailableTracks()
    }
    updateActiveSkipInterval(pos)
    evaluatePostPlayOverlayVisibility(positionMs = pos, durationMs = duration)
    if (ended && !wasEnded) {
        emitCompletionScrobbleStop(progressPercent = 99.5f)
        saveWatchProgress()
        resetPostPlayStateAfterPlaybackEnded()
    }
}

private fun PlayerRuntimeController.shouldRefreshMpvTrackDiscovery(): Boolean {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now <= mpvTrackDiscoveryDeadlineElapsedMs) return true
    if (hasScannedTextTracksOnce) return false
    val startedAt = mpvTrackDiscoveryStartedElapsedMs
    if (startedAt <= 0L) return false
    if (now <= startedAt + MPV_TRACK_DISCOVERY_ABSOLUTE_MAX_MS) return true
    // Absolute ceiling: treat as scanned so AUTO_SUB can fall through to addons.
    hasScannedTextTracksOnce = true
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    return false
}

/** Single ExoPlayer progress sample: timeline, torrent rebuffer, periodic buffer log. */
private fun PlayerRuntimeController.sampleExoProgress() {
    val player = _exoPlayer ?: return
    val pos = player.currentPosition.coerceAtLeast(0L)
    val duration = player.duration
    if (duration > lastKnownDuration) {
        lastKnownDuration = duration
    }
    val displayPosition = pendingPreviewSeekPosition ?: pos
    updatePlaybackTimeline(
        currentPosition = displayPosition,
        duration = duration.coerceAtLeast(0L),
        bufferedPosition = player.bufferedPosition.coerceAtLeast(displayPosition)
    )
    playbackAnalyticsDiagnostics.recordProgressSnapshot(
        player = player,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs
    )
    // Update torrent rebuffer progress from ExoPlayer's buffer state.
    if (isTorrentStream && _uiState.value.isBuffering && hasRenderedFirstFrame) {
        updateTorrentRebufferStatus(player, pos)
    }
    updateActiveSkipInterval(pos)
    evaluatePostPlayOverlayVisibility(positionMs = pos, durationMs = duration.coerceAtLeast(0L))

    if (player.isPlaying) {
        maybeLogBufferState(player, pos)
    }
}

private fun PlayerRuntimeController.updateTorrentRebufferStatus(player: androidx.media3.exoplayer.ExoPlayer, pos: Long) {
    val bufferedAheadMs = (player.bufferedPosition - pos).coerceAtLeast(0)
    val bufferedSec = bufferedAheadMs / 1_000f
    val statsHidden = _uiState.value.hideTorrentStats
    val message = if (statsHidden) null else {
        val speed = formatTorrentSpeed(context, _uiState.value.torrentDownloadSpeed)
        val peerInfo = context.getString(
            R.string.player_torrent_peer_info,
            _uiState.value.torrentSeeds,
            _uiState.value.torrentPeers
        )
        val bufLabel = String.format("%.0fs", bufferedSec)
        context.getString(R.string.player_torrent_buffered_status, bufLabel, peerInfo, speed)
    }
    val progress = (bufferedSec / 10f).coerceIn(0f, 1f)
    _uiState.update {
        it.copy(torrentBufferingMessage = message, torrentBufferingProgress = progress)
    }
}

private fun PlayerRuntimeController.maybeLogBufferState(
    player: androidx.media3.exoplayer.ExoPlayer,
    pos: Long
) {
    val now = System.currentTimeMillis()
    if (now - lastBufferLogTimeMs < BUFFER_LOG_INTERVAL_MS) return
    lastBufferLogTimeMs = now
    val bufAhead = (player.bufferedPosition - player.currentPosition) / 1_000
    val loading = player.isLoading
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    Log.d(PlayerRuntimeController.TAG, "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=$usedMb/${maxMb}MB, pos=${pos / 1000}s")

    if (!ExoPlayerPerformanceHelper.shouldLogMemoryFootprint()) return
    val defaultAllocator = runCatching {
        val field = _loadControl?.javaClass?.getDeclaredField("allocator")
        field?.isAccessible = true
        field?.get(_loadControl) as? androidx.media3.exoplayer.upstream.DefaultAllocator
    }.getOrNull()
    val totalFootprintBytes = defaultAllocator?.let { allocator ->
        runCatching {
            allocator.javaClass.getMethod("getMemoryFootprint").invoke(allocator) as? Long ?: 0L
        }.getOrDefault(0L)
    } ?: 0L
    val totalActiveBytes = defaultAllocator?.totalBytesAllocated ?: 0
    val footprintMb = totalFootprintBytes / (1024 * 1024)
    val activeMb = totalActiveBytes / (1024 * 1024)
    Log.d("ExoMemory", "Off-heap OS ahead: $footprintMb MB, active: $activeMb MB")
}

internal fun PlayerRuntimeController.stopProgressUpdates() {
    progressJob?.cancel()
    progressJob = null
}

internal fun PlayerRuntimeController.startWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = scope.launch {
        while (isActive) {
            delay(WATCH_PROGRESS_SAVE_INTERVAL_MS)
            saveWatchProgressIfNeeded()
        }
    }
}

internal fun PlayerRuntimeController.stopWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
}

internal fun PlayerRuntimeController.submitPlaybackIssueReport() {
    val state = _uiState.value
    if (!state.playbackIssueReportsEnabled) return
    if (state.playbackIssueReportStatus == PlaybackIssueReportStatus.Sending ||
        state.playbackIssueReportStatus == PlaybackIssueReportStatus.Sent
    ) return
    val timeline = _playbackTimeline.value
    val diagnostics = lastPlaybackDiagnosticsForReport.takeIf { it.timestampMs > 0L }
        ?: LastPlaybackDiagnostics(
            timestampMs = System.currentTimeMillis(),
            host = currentStreamUrl.reportSafeHost(),
            result = state.error?.let { "Error: $it" } ?: "Pending"
        )
    val reportError = lastPlaybackIssueError
        ?: PlaybackIssueErrorInput(
            displayMessage = state.error,
            errorCode = null,
            errorCodeName = null,
            exceptionClass = null,
            causeClass = null,
            causeMessage = null,
            httpStatus = null
        )
    val audioTrack = state.audioTracks.reportTrackLabel(state.selectedAudioTrackIndex)
    val subtitleTrack = state.subtitleTracks.reportTrackLabel(state.selectedSubtitleTrackIndex)
    val reportReason = if (state.error == null && state.showLoadingOverlay && !hasRenderedFirstFrame) {
        "loading_stall"
    } else {
        "playback_error"
    }
    val loadingInput = buildPlaybackIssueLoadingInput(reportReason)
    val playbackAnalyticsInput = playbackAnalyticsDiagnostics.snapshot(
        player = _exoPlayer,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        rebufferStartedAtMs = rebufferStartedAtMs
    ).copy(startupStages = loadingInput.events)
    val input = PlaybackIssueReportInput(
        diagnostics = diagnostics,
        error = reportError,
        title = title,
        contentName = contentName,
        contentId = contentId,
        contentType = contentType,
        videoId = currentVideoId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        releaseYear = year,
        streamUrl = currentStreamUrl,
        streamMimeType = currentStreamMimeType,
        streamName = state.currentStreamName,
        addonName = currentAddonName,
        videoHash = currentVideoHash,
        videoSize = currentVideoSize,
        requestHeaders = currentHeaders,
        responseHeaders = currentStreamResponseHeaders,
        playerEngine = currentInternalPlayerEngine.name,
        loading = loadingInput,
        positionMs = timeline.currentPosition.takeIf { it > 0L },
        durationMs = timeline.duration.takeIf { it > 0L },
        bufferedPositionMs = timeline.bufferedPosition.takeIf { it > 0L },
        selectedAudioTrack = audioTrack,
        selectedSubtitleTrack = subtitleTrack,
        isTorrentStream = isTorrentStream,
        playbackSettings = buildPlaybackIssuePlaybackSettingsInput(),
        playbackAnalytics = playbackAnalyticsInput
    )

    val requestVersion = playbackIssueReportRequestVersion.incrementAndGet()
    _uiState.update {
        it.copy(
            playbackIssueReportStatus = PlaybackIssueReportStatus.Sending,
            playbackIssueReportId = null,
            playbackIssueReportError = null
        )
    }
    scope.launch {
        val result = playbackIssueReportRepository.submit(input)
        _uiState.update { current ->
            if (playbackIssueReportRequestVersion.get() != requestVersion ||
                current.playbackIssueReportStatus != PlaybackIssueReportStatus.Sending
            ) {
                current
            } else {
                result.fold(
                    onSuccess = { reportId ->
                        current.copy(
                            playbackIssueReportStatus = PlaybackIssueReportStatus.Sent,
                            playbackIssueReportId = reportId,
                            playbackIssueReportError = null
                        )
                    },
                    onFailure = { error ->
                        current.copy(
                            playbackIssueReportStatus = PlaybackIssueReportStatus.Failed,
                            playbackIssueReportId = null,
                            playbackIssueReportError = error.message ?: "Unable to send report"
                        )
                    }
                )
            }
        }
    }
}

private fun PlayerRuntimeController.buildPlaybackIssuePlaybackSettingsInput(): PlaybackIssuePlaybackSettingsInput {
    val settings = currentPlayerSettingsForReport
    val state = _uiState.value
    val effectiveDecoderPriority = cachedDecoderPriority
    return PlaybackIssuePlaybackSettingsInput(
        playerPreference = settings.playerPreference.name,
        internalPlayerEngine = settings.internalPlayerEngine.name,
        resolvedInternalPlayerEngine = currentInternalPlayerEngine.name,
        autoSwitchInternalPlayerOnError = settings.autoSwitchInternalPlayerOnError,
        decoderPriority = settings.decoderPriority,
        decoderPriorityName = decoderPriorityReportName(settings.decoderPriority),
        effectiveDecoderPriority = effectiveDecoderPriority,
        effectiveDecoderPriorityName = decoderPriorityReportName(effectiveDecoderPriority),
        downmixEnabled = settings.downmixEnabled,
        audioOutputChannels = settings.audioOutputChannels.settingValue,
        maintainOriginalAudioOnDownmix = settings.maintainOriginalAudioOnDownmix,
        tunnelingEnabled = settings.tunnelingEnabled,
        tunnelingEffective = state.tunnelingEnabled,
        forceOpticalPassthrough = settings.forceOpticalPassthrough,
        skipSilence = settings.skipSilence,
        audioAmplificationDb = settings.audioAmplificationDb,
        centerMixLevelDb = settings.centerMixLevelDb,
        persistAudioAmplification = settings.persistAudioAmplification,
        rememberAudioDelayPerDevice = settings.rememberAudioDelayPerDevice,
        preferredAudioLanguage = settings.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
        preferredSubtitleLanguage = settings.subtitleStyle.preferredLanguage,
        secondaryPreferredSubtitleLanguage = settings.subtitleStyle.secondaryPreferredLanguage,
        useForcedSubtitles = settings.subtitleStyle.useForcedSubtitles,
        showOnlyPreferredSubtitleLanguages = settings.subtitleStyle.showOnlyPreferredLanguages,
        useLibass = settings.useLibass,
        activePlayerUsesLibass = requestedUseLibassByUser && !isUsingMpvEngine(),
        libassRenderType = settings.libassRenderType.name,
        addonSubtitleStartupMode = settings.addonSubtitleStartupMode.name,
        externalPlayerForwardSubtitles = settings.externalPlayerForwardSubtitles,
        subtitleOrganizationMode = settings.subtitleOrganizationMode.name,
        loadingOverlayEnabled = settings.loadingOverlayEnabled,
        showPlayerLoadingStatus = settings.showPlayerLoadingStatus,
        playbackIssueReportsEnabled = settings.playbackIssueReportsEnabled,
        dv5ToDv81Enabled = settings.dv5ToDv81Enabled,
        dv7ToDv81PreserveMappingEnabled = settings.dv7ToDv81PreserveMappingEnabled,
        dv7HandlingMode = settings.dv7HandlingMode.name,
        dv7LibdoviModeOverride = settings.dv7LibdoviModeOverride,
        stripHdr10PlusSei = settings.stripHdr10PlusSei,
        mpvHardwareDecodeMode = settings.mpvHardwareDecodeMode.name,
        frameRateMatchingMode = settings.frameRateMatchingMode.name,
        resolutionMatchingEnabled = settings.resolutionMatchingEnabled,
        resizeMode = settings.resizeMode,
        aspectMode = state.aspectMode.name,
        bufferEngineEnabled = settings.bufferEngineEnabled,
        minBufferMs = settings.bufferSettings.minBufferMs,
        maxBufferMs = settings.bufferSettings.maxBufferMs,
        bufferForPlaybackMs = settings.bufferSettings.bufferForPlaybackMs,
        bufferForPlaybackAfterRebufferMs = settings.bufferSettings.bufferForPlaybackAfterRebufferMs,
        targetBufferSizeMb = settings.bufferSettings.targetBufferSizeMb,
        backBufferDurationMs = settings.bufferSettings.backBufferDurationMs,
        effectiveBackBufferDurationMs = effectiveBackBufferDurationMs,
        retainBackBufferFromKeyframe = settings.bufferSettings.retainBackBufferFromKeyframe,
        parallelNetworkEnabled = settings.parallelNetworkEnabled,
        bufferBudgetManaged = settings.bufferBudgetManaged,
        allowLargeTargetBuffer = settings.allowLargeTargetBuffer,
        vodCacheEnabled = settings.vodCacheEnabled,
        vodCacheSizeMode = settings.vodCacheSizeMode.name,
        vodCacheSizeMb = settings.vodCacheSizeMb,
        useParallelConnections = settings.useParallelConnections,
        parallelConnectionCount = settings.parallelConnectionCount,
        parallelChunkSizeKb = settings.parallelChunkSizeKb,
        enableHttp2 = settings.enableHttp2,
        exoPerformanceModeEnabled = settings.exoPerformanceModeEnabled,
        streamAutoPlayMode = settings.streamAutoPlayMode.name,
        streamAutoPlaySource = settings.streamAutoPlaySource.name,
        streamAutoPlayNextEpisodeEnabled = settings.streamAutoPlayNextEpisodeEnabled,
        streamAutoPlayPreferBingeGroupForNextEpisode = settings.streamAutoPlayPreferBingeGroupForNextEpisode,
        streamAutoPlayReuseBingeGroup = settings.streamAutoPlayReuseBingeGroup,
        streamAutoPlayTimeoutSeconds = settings.streamAutoPlayTimeoutSeconds,
        stillWatchingEnabled = settings.stillWatchingEnabled,
        stillWatchingEpisodeThreshold = settings.stillWatchingEpisodeThreshold,
        nextEpisodeThresholdMode = settings.nextEpisodeThresholdMode.name,
        nextEpisodeThresholdPercent = settings.nextEpisodeThresholdPercent,
        nextEpisodeThresholdMinutesBeforeEnd = settings.nextEpisodeThresholdMinutesBeforeEnd,
        streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled,
        streamReuseLastLinkCacheHours = settings.streamReuseLastLinkCacheHours
    )
}

private fun decoderPriorityReportName(priority: Int): String =
    when (priority) {
        0 -> "DEVICE_ONLY"
        2 -> "PREFER_APP"
        else -> "PREFER_DEVICE"
    }

private fun List<TrackInfo>.reportTrackLabel(selectedIndex: Int): String? {
    val track = firstOrNull { it.index == selectedIndex } ?: getOrNull(selectedIndex) ?: return null
    return buildString {
        append(track.name)
        track.language?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
        track.codec?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
        track.channelCount?.let { append(" | ").append(it).append("ch") }
    }
}

private fun String.reportSafeHost(): String =
    runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")

internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    // Skip very short streams (< 2:01) — these are typically error/warning
    // messages or "stream not ready" placeholders that would incorrectly mark
    // content as watched when the user exits.
    if (isShortPlaceholderDuration(duration)) return

    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= PROGRESS_SAVE_THRESHOLD_MS) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = currentPlaybackPositionMs() ?: return
    val duration = getEffectiveDuration(currentPosition)
    if (isShortPlaceholderDuration(duration)) return
    saveWatchProgressInternal(currentPosition, duration)
}

internal fun PlayerRuntimeController.getEffectiveDuration(position: Long): Long {
    val playerDuration = currentPlaybackDurationMs()
    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
    if (effectiveDuration <= 0L) return 0L

    val isEnded = if (isUsingMpvEngine()) {
        position >= (effectiveDuration - ENDED_TOLERANCE_MS)
    } else {
        _exoPlayer?.playbackState == Player.STATE_ENDED
    }
    if (!isEnded && effectiveDuration < position) return 0L

    return effectiveDuration
}

private fun isShortPlaceholderDuration(duration: Long) =
    duration in SHORT_PLACEHOLDER_DURATION_MIN_MS..SHORT_PLACEHOLDER_DURATION_MAX_MS

private fun PlayerRuntimeController.isShortPlaceholderStream(): Boolean {
    val position = currentPlaybackPositionMs() ?: return false
    return isShortPlaceholderDuration(getEffectiveDuration(position))
}

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    val currentContentId = contentId?.takeIf { it.isNotEmpty() } ?: return
    val currentContentType = contentType?.takeIf { it.isNotEmpty() } ?: return
    if (position < 1_000L) return

    val fallbackPercent = if (duration <= 0L) 5f else null

    // If Trakt is the active CW source and contentId is not Trakt-resolvable
    // but videoId carries a valid IMDB/TMDB, use the resolved ID to avoid
    // duplicate CW entries (one local with a garbage ID, one from Trakt).
    val effectiveContentId = if (isTraktCwActive) {
        resolveEffectiveContentId(currentContentId, currentVideoId)
    } else {
        currentContentId
    }

    val progress = WatchProgress(
        contentId = effectiveContentId,
        contentType = currentContentType,
        name = contentName ?: title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = currentVideoId ?: currentContentId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        position = position,
        duration = duration,
        lastWatched = System.currentTimeMillis(),
        progressPercent = fallbackPercent
    )

    scope.launch(NonCancellable) {
        if (progress.isCompleted() && !hasMarkedCurrentEpisodeCompleted) {
            hasMarkedCurrentEpisodeCompleted = true
            // Don't send markAsWatched to Trakt from the player — the scrobble
            // stop (≥80%) already causes Trakt to add the history entry.
            // Local stores + Trakt Sync are still updated.
            playbackProgressSink.markAsCompleted(progress, syncRemoteToTrakt = false)
        } else {
            playbackProgressSink.saveProgress(progress, syncRemote = syncRemote)
        }
    }
}

internal fun PlayerRuntimeController.currentPlaybackProgressPercent(): Float {
    if (!hasRenderedFirstFrame) return 0f
    val position = currentPlaybackPositionMs() ?: return 0f
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: lastKnownDuration
    if (duration <= 0L) return 0f
    return ((position.toFloat() / duration.toFloat()) * 100f).coerceIn(0f, 100f)
}

internal fun PlayerRuntimeController.refreshScrobbleItem() {
    currentScrobbleItem = buildScrobbleItem()
    hasSentScrobbleStartForCurrentItem = false
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration++
    hasSentCompletionScrobbleForCurrentItem = false
}

internal fun PlayerRuntimeController.buildScrobbleItem(): TraktScrobbleItem? {
    val rawContentId = contentId ?: return null
    val parsedIds = parseContentIds(rawContentId)
    var ids = toTraktIds(parsedIds)
    // Fallback: if contentId doesn't resolve to valid Trakt IDs, try videoId.
    // Some addons use a non-standard contentId (e.g. "tun_tt7821582") but set a
    // valid IMDB/TMDB videoId (e.g. "tt7821582:3:7").
    if (ids.trakt == null && ids.imdb.isNullOrBlank() && ids.tmdb == null) {
        val fallbackVideoId = currentVideoId
        if (!fallbackVideoId.isNullOrBlank() && fallbackVideoId != rawContentId) {
            ids = toTraktIds(parseContentIds(fallbackVideoId))
        }
    }
    if (ids.trakt == null && ids.imdb.isNullOrBlank() && ids.tmdb == null) return null
    val parsedYear = extractYear(year)
    val normalizedType = contentType?.lowercase()
    val currentMappingKey = currentEpisodeMappingCacheKey()
    val mappedEpisode = if (currentTraktEpisodeMappingKey == currentMappingKey) {
        currentTraktEpisodeMapping
    } else {
        null
    }
    val effectiveSeason = mappedEpisode?.season ?: currentSeason
    val effectiveEpisode = mappedEpisode?.episode ?: currentEpisode

    val isEpisode = normalizedType in listOf("series", "tv") &&
        effectiveSeason != null && effectiveEpisode != null

    return if (isEpisode) {
        TraktScrobbleItem.Episode(
            showTitle = contentName ?: title,
            showYear = parsedYear,
            showIds = ids,
            season = effectiveSeason ?: return null,
            number = effectiveEpisode ?: return null,
            episodeTitle = currentEpisodeTitle
        )
    } else {
        TraktScrobbleItem.Movie(
            title = contentName ?: title,
            year = parsedYear,
            ids = ids
        )
    }
}

internal fun PlayerRuntimeController.emitScrobbleStart() {
    if (isShortPlaceholderStream()) return
    if (hasRequestedScrobbleStartForCurrentItem) return

    // Don't start a new Trakt scrobble session if playback resumes at ≥80%.
    // This avoids creating a duplicate history entry when the user continues
    // watching something already marked as watched. If the user seeks back
    // below 80%, the next progress update will re-trigger scrobble start.
    if (currentPlaybackProgressPercent() >= 80f) return

    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = ++scrobbleStartRequestGeneration
    scope.launch {
        // Wait for the episode mapping to finish (with its own timeout) so the
        // scrobble start is sent with the correct season/episode number.
        traktMappingJob?.join()
        currentScrobbleItem = buildScrobbleItem()
        val item = currentScrobbleItem ?: return@launch
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) return@launch
        val progressPercent = currentPlaybackProgressPercent()
        traktScrobbleService.scrobbleStart(item = item, progressPercent = progressPercent)
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) return@launch
        hasSentScrobbleStartForCurrentItem = true
    }
}

internal fun PlayerRuntimeController.emitScrobbleStop(progressPercent: Float? = null) {
    if (isShortPlaceholderStream()) return
    val item = currentScrobbleItem ?: return

    val provided = progressPercent
    if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return

    val percent = provided ?: currentPlaybackProgressPercent()
    scope.launch(NonCancellable) {
        traktScrobbleService.scrobbleStop(item = item, progressPercent = percent)
    }
    scrobbleStartRequestGeneration++
    hasRequestedScrobbleStartForCurrentItem = false
    hasSentScrobbleStartForCurrentItem = false
}

internal fun PlayerRuntimeController.emitPauseScrobbleStop(progressPercent: Float) {
    if (progressPercent < 1f || progressPercent >= 80f) return
    if (isShortPlaceholderStream()) return
    val item = currentScrobbleItem ?: return
    if (!hasRequestedScrobbleStartForCurrentItem) return

    scope.launch(NonCancellable) {
        traktScrobbleService.scrobbleStop(item = item, progressPercent = progressPercent)
    }
    scrobbleStartRequestGeneration++
    hasRequestedScrobbleStartForCurrentItem = false
    hasSentScrobbleStartForCurrentItem = false
}

internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return
    hasSentCompletionScrobbleForCurrentItem = true
    emitScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.emitStopScrobbleForCurrentProgress() {
    val progressPercent = currentPlaybackProgressPercent()
    emitPauseScrobbleStop(progressPercent = progressPercent)
    emitCompletionScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.flushPlaybackSnapshotForSwitchOrExit() {
    emitStopScrobbleForCurrentProgress()
    saveWatchProgress()
}

internal fun PlayerRuntimeController.scheduleProgressSyncAfterSeek() {
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(seekProgressSyncDebounceMs)
        saveWatchProgress()

        val progressPercent = currentPlaybackProgressPercent()
        emitPauseScrobbleStop(progressPercent = progressPercent)

        if (isPlaybackCurrentlyPlaying() && progressPercent >= 1f && progressPercent < 80f) {
            emitScrobbleStart()
        }
    }
}

fun PlayerRuntimeController.scheduleHideControls() {
    hideControlsJob?.cancel()
    hideControlsJob = scope.launch {
        delay(CONTROLS_AUTO_HIDE_MS)
        val s = _uiState.value
        val anyOverlayOpen = s.showAudioOverlay || s.showSubtitleOverlay ||
            s.showSubtitleStylePanel || s.showSpeedDialog || s.showMoreDialog ||
            s.showSubtitleDelayOverlay || s.showSubtitleTimingDialog ||
            s.showEpisodesPanel || s.showSourcesPanel || s.showStreamInfoOverlay
        if (s.isPlaying && !anyOverlayOpen) {
            _uiState.update { it.copy(showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.showSubtitleDelayOverlay() {
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showControls = false,
            showSubtitleDelayOverlay = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.hideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int) {
    adjustSubtitleDelay(deltaMs = deltaMs, showOverlay = true)
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int, showOverlay: Boolean) {
    val currentState = _uiState.value
    val currentDelayMs = currentState.subtitleDelayMs
    val newDelayMs = (currentDelayMs + deltaMs).coerceIn(
        minimumValue = SUBTITLE_DELAY_MIN_MS,
        maximumValue = SUBTITLE_DELAY_MAX_MS
    )
    val keepInlineInSubtitleOverlay = showOverlay && currentState.showSubtitleOverlay

    subtitleDelayUs.set(newDelayMs.toLong() * 1_000L)
    if (isUsingMpvEngine()) {
        mpvView?.setSubtitleDelayMs(newDelayMs)
    }
    if (showOverlay) {
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showControls = if (keepInlineInSubtitleOverlay) it.showControls else false,
                showSubtitleDelayOverlay = if (keepInlineInSubtitleOverlay) false else true
            )
        }
    } else {
        hideSubtitleDelayOverlayJob?.cancel()
        _uiState.update {
            it.copy(
                subtitleDelayMs = newDelayMs,
                showSubtitleDelayOverlay = false,
                showControls = true
            )
        }
    }

    refreshActiveSubtitleTrackAfterTimingChange()
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()

    if (!showOverlay || keepInlineInSubtitleOverlay) {
        hideSubtitleDelayOverlayJob?.cancel()
        hideSubtitleDelayOverlayJob = null
    } else {
        scheduleHideSubtitleDelayOverlay()
    }
}

internal fun PlayerRuntimeController.scheduleHideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = scope.launch {
        delay(SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS)
        _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
    }
}

internal fun PlayerRuntimeController.schedulePauseOverlay() {
    pauseOverlayJob?.cancel()

    if (!_uiState.value.pauseOverlayEnabled || !hasRenderedFirstFrame || !userPausedManually) {
        _uiState.update { it.copy(showPauseOverlay = false) }
        return
    }

    _uiState.update { it.copy(showPauseOverlay = false) }
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        val s = _uiState.value
        val anyPanelOpen = s.showSubtitleOverlay || s.showSubtitleStylePanel ||
            s.showSpeedDialog || s.showMoreDialog || s.showEpisodesPanel ||
            s.showSourcesPanel || s.showAudioOverlay || s.showStreamInfoOverlay ||
            s.showSubtitleTimingDialog
        if (!s.isPlaying && s.pauseOverlayEnabled && s.error == null && !anyPanelOpen) {
            // Keep transport/paused meta visible — blanking controls after 5s looks broken in rewrite.
            _uiState.update { it.copy(showPauseOverlay = false, showControls = true) }
        }
    }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
}

fun PlayerRuntimeController.onUserInteraction() {
    if (_uiState.value.showPauseOverlay) {
        cancelPauseOverlay()
        showControlsTemporarily()
    } else if (pauseOverlayJob != null && !_uiState.value.isPlaying && userPausedManually) {
        schedulePauseOverlay()
    }
}

fun PlayerRuntimeController.hideControls() {
    hideControlsJob?.cancel()
    _uiState.update { it.copy(showControls = false, showSeekOverlay = false, showMoreDialog = false) }
}

fun PlayerRuntimeController.onEvent(event: PlayerEvent) {
    ExperimentalDiagnostics.event(
        "player_input",
        "event_received",
        mapOf(
            "event" to event.javaClass.simpleName,
            "engine" to _uiState.value.internalPlayerEngine,
            "playing" to _uiState.value.isPlaying,
            "positionMs" to playbackTimeline.value.currentPosition,
        ),
    )
    onUserInteraction()
    when (event) {
        PlayerEvent.OnPlayPause -> handlePlayPause()
        PlayerEvent.OnSeekForward -> onEvent(PlayerEvent.OnSeekBy(deltaMs = 10_000L))
        PlayerEvent.OnSeekBackward -> onEvent(PlayerEvent.OnSeekBy(deltaMs = -10_000L))
        is PlayerEvent.OnSeekBy -> handleSeekBy(event.deltaMs)
        is PlayerEvent.OnPreviewSeekBy -> handlePreviewSeekBy(event.deltaMs)
        PlayerEvent.OnCommitPreviewSeek -> handleCommitPreviewSeek()
        is PlayerEvent.OnSeekTo -> handleSeekTo(event.position)
        is PlayerEvent.OnSelectAudioTrack -> handleSelectAudioTrack(event.index)
        is PlayerEvent.OnSetAudioDelayMs -> applyAudioDelay(event.delayMs)
        is PlayerEvent.OnSetAudioAmplificationDb -> handleSetAudioAmplificationDb(event.db)
        is PlayerEvent.OnSetPersistAudioAmplification -> handleSetPersistAudioAmplification(event.enabled)
        is PlayerEvent.OnSetCenterMixLevelDb -> handleSetCenterMixLevelDb(event.db)
        is PlayerEvent.OnSelectSubtitleTrack -> handleSelectSubtitleTrack(event.index)
        PlayerEvent.OnDisableSubtitles -> handleDisableSubtitles()
        is PlayerEvent.OnSelectAddonSubtitle -> handleSelectAddonSubtitle(event)
        PlayerEvent.OnRetrySubtitleSearch -> fetchAddonSubtitles()
        is PlayerEvent.OnSetPlaybackSpeed -> handleSetPlaybackSpeed(event.speed)
        PlayerEvent.OnToggleControls -> handleToggleControls()
        PlayerEvent.OnShowAudioOverlay -> showAudioOverlay()
        PlayerEvent.OnShowSubtitleOverlay -> showSubtitleOverlay()
        PlayerEvent.OnOpenSubtitleStylePanel -> openSubtitleStylePanel()
        PlayerEvent.OnDismissSubtitleStylePanel -> {
            _uiState.update { it.copy(showSubtitleStylePanel = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowSubtitleTimingDialog -> showSubtitleTimingDialog()
        PlayerEvent.OnDismissSubtitleTimingDialog -> dismissSubtitleTimingDialog()
        PlayerEvent.OnCaptureSubtitleAutoSyncTime -> captureSubtitleAutoSyncTime()
        is PlayerEvent.OnApplySubtitleAutoSyncCue -> applySubtitleAutoSyncCue(event.cueStartTimeMs)
        PlayerEvent.OnReloadSubtitleAutoSyncCues -> reloadSubtitleAutoSyncCues()
        PlayerEvent.OnShowSubtitleDelayOverlay -> showSubtitleDelayOverlay()
        PlayerEvent.OnHideSubtitleDelayOverlay -> hideSubtitleDelayOverlay()
        is PlayerEvent.OnAdjustSubtitleDelay -> adjustSubtitleDelay(event.deltaMs, event.showOverlay)
        PlayerEvent.OnShowSpeedDialog -> handleShowSpeedDialog()
        PlayerEvent.OnShowMoreDialog -> showMoreDialog()
        PlayerEvent.OnDismissMoreDialog -> {
            _uiState.update { it.copy(showMoreDialog = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowEpisodesPanel -> showEpisodesPanel()
        PlayerEvent.OnDismissEpisodesPanel -> dismissEpisodesPanel()
        PlayerEvent.OnBackFromEpisodeStreams -> {
            _uiState.update {
                it.copy(showEpisodeStreams = false, isLoadingEpisodeStreams = false)
            }
        }
        is PlayerEvent.OnEpisodeSeasonSelected -> selectEpisodesSeason(event.season)
        is PlayerEvent.OnEpisodeSelected -> loadStreamsForEpisode(event.video, forceRefresh = true, showPicker = true)
        PlayerEvent.OnReloadEpisodeStreams -> reloadEpisodeStreams()
        is PlayerEvent.OnEpisodeAddonFilterSelected -> filterEpisodeStreamsByAddon(event.addonName)
        is PlayerEvent.OnEpisodeStreamSelected -> switchToEpisodeStream(event.stream)
        PlayerEvent.OnShowSourcesPanel -> showSourcesPanel()
        PlayerEvent.OnDismissSourcesPanel -> dismissSourcesPanel()
        PlayerEvent.OnReloadSourceStreams -> loadSourceStreams(forceRefresh = true)
        is PlayerEvent.OnSourceAddonFilterSelected -> filterSourceStreamsByAddon(event.addonName)
        is PlayerEvent.OnSourceStreamSelected -> switchToSourceStream(event.stream)
        is PlayerEvent.OnPlaybackSourceSelected -> switchToPlaybackSource(event.handoff)
        PlayerEvent.OnDismissTransientOverlay -> dismissTransientOverlay()
        PlayerEvent.OnRetry -> handleRetry()
        PlayerEvent.OnReportPlaybackIssue -> submitPlaybackIssueReport()
        PlayerEvent.OnParentalGuideHide -> _uiState.update { it.copy(showParentalGuide = false) }
        PlayerEvent.OnToggleTorrentStats -> _uiState.update { it.copy(showTorrentStats = !it.showTorrentStats) }
        is PlayerEvent.OnShowDisplayModeInfo -> _uiState.update {
            it.copy(displayModeInfo = event.info, showDisplayModeInfo = true)
        }
        PlayerEvent.OnHideDisplayModeInfo -> _uiState.update { it.copy(showDisplayModeInfo = false) }
        PlayerEvent.OnDismissPauseOverlay -> cancelPauseOverlay()
        PlayerEvent.OnSkipIntro -> skipActiveInterval()
        PlayerEvent.OnDismissSkipIntro -> _uiState.update { it.copy(skipIntervalDismissed = true) }
        PlayerEvent.OnPlayNextEpisode -> playNextEpisode(userInitiated = true)
        PlayerEvent.OnDismissNextEpisodeCard -> {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeAutoPlayJob = null
            _uiState.update {
                it.copy(postPlayMode = null, postPlayDismissedForCurrentEpisode = true)
            }
        }
        PlayerEvent.OnStillWatchingContinue -> onStillWatchingContinue()
        PlayerEvent.OnDismissStillWatchingPrompt -> onDismissStillWatchingPrompt()
        is PlayerEvent.OnSetSubtitleSize -> scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        is PlayerEvent.OnSetSubtitleTextColor -> scope.launch { playerSettingsDataStore.setSubtitleTextColor(event.color) }
        is PlayerEvent.OnSetSubtitleBold -> scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
        is PlayerEvent.OnSetSubtitleOutlineEnabled -> scope.launch { playerSettingsDataStore.setSubtitleOutlineEnabled(event.enabled) }
        is PlayerEvent.OnSetSubtitleOutlineColor -> scope.launch { playerSettingsDataStore.setSubtitleOutlineColor(event.color) }
        is PlayerEvent.OnSetSubtitleVerticalOffset -> scope.launch { playerSettingsDataStore.setSubtitleVerticalOffset(event.offset) }
        PlayerEvent.OnResetSubtitleDefaults -> handleResetSubtitleDefaults()
        PlayerEvent.OnToggleAspectRatio -> handleToggleAspectRatio()
        PlayerEvent.OnSwitchInternalPlayerEngine -> {
            logSwitchTrace(stage = "event-switch-engine", message = "requestedByUser=true")
            switchInternalPlayerEngineManually()
        }
        PlayerEvent.OnShowStreamInfo -> {
            val info = buildStreamInfoData()
            _uiState.update {
                it.copy(showStreamInfoOverlay = true, streamInfoData = info, showControls = true)
            }
        }
        PlayerEvent.OnDismissStreamInfo -> _uiState.update { it.copy(showStreamInfoOverlay = false) }
    }
}

private fun PlayerRuntimeController.handlePlayPause() {
    if (isUsingMpvEngine()) {
        handleMpvPlayPause()
    } else {
        handleExoPlayPause()
    }
    showControlsTemporarily()
}

private fun PlayerRuntimeController.handleMpvPlayPause() {
    if (isPlaybackCurrentlyPlaying()) {
        userPausedManually = true
        setPlaybackPaused(true)
        stopProgressUpdates()
        stopWatchProgressSaving()
        emitStopScrobbleForCurrentProgress()
        schedulePauseOverlay()
    } else {
        userPausedManually = false
        cancelPauseOverlay()
        setPlaybackPaused(false)
        startProgressUpdates()
        startWatchProgressSaving()
        scheduleHideControls()
        emitScrobbleStart()
    }
}

private fun PlayerRuntimeController.handleExoPlayPause() {
    val player = _exoPlayer ?: return
    if (player.isPlaying) {
        userPausedManually = true
        pauseStartTimeMs = System.currentTimeMillis()
        player.pause()
        schedulePauseOverlay()
    } else {
        userPausedManually = false
        cancelPauseOverlay()
        val pausedDuration = System.currentTimeMillis() - pauseStartTimeMs
        if (pauseStartTimeMs > 0L && pausedDuration > PlayerRuntimeController.LONG_PAUSE_THRESHOLD_MS) {
            val pos = player.currentPosition
            player.seekTo((pos - LONG_PAUSE_BACK_SEEK_MS).coerceAtLeast(0L))
        }
        pauseStartTimeMs = 0L
        player.play()
    }
}

private fun PlayerRuntimeController.handleSeekBy(deltaMs: Long) {
    pendingPreviewSeekPosition = null
    _uiState.update { it.copy(pendingPreviewSeekPosition = null) }
    val current = currentPlaybackPositionMs() ?: 0L
    val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
    val target = (current + deltaMs).coerceAtLeast(0L).coerceAtMost(maxDuration)
    val seekParameters = if (deltaMs < 0L) SeekParameters.PREVIOUS_SYNC else SeekParameters.NEXT_SYNC
    seekPlaybackTo(target, seekParameters)
    updatePlaybackTimeline(currentPosition = target)
    scheduleProgressSyncAfterSeek()
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
}

private fun PlayerRuntimeController.handlePreviewSeekBy(deltaMs: Long) {
    val maxDuration = currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
    val basePosition = pendingPreviewSeekPosition ?: currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    val target = (basePosition + deltaMs).coerceAtLeast(0L).coerceAtMost(maxDuration)
    pendingPreviewSeekPosition = target
    _uiState.update { it.copy(pendingPreviewSeekPosition = target) }
    updatePlaybackTimeline(currentPosition = target)
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
}

private fun PlayerRuntimeController.handleCommitPreviewSeek() {
    val target = pendingPreviewSeekPosition ?: return
    seekPlaybackTo(target, SeekParameters.CLOSEST_SYNC)
    updatePlaybackTimeline(currentPosition = target)
    pendingPreviewSeekPosition = null
    _uiState.update { it.copy(pendingPreviewSeekPosition = null) }
    scheduleProgressSyncAfterSeek()
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
}

private fun PlayerRuntimeController.handleSeekTo(position: Long) {
    pendingPreviewSeekPosition = null
    _uiState.update { it.copy(pendingPreviewSeekPosition = null) }
    seekPlaybackTo(position, SeekParameters.CLOSEST_SYNC)
    updatePlaybackTimeline(currentPosition = position)
    scheduleProgressSyncAfterSeek()
    if (_uiState.value.showControls) showControlsTemporarily() else showSeekOverlayTemporarily()
}

private fun PlayerRuntimeController.handleSelectAudioTrack(index: Int) {
    logSwitchTrace(stage = "event-select-audio", message = "index=$index")
    rememberAudioSelection(index)
    selectAudioTrack(index)
    _uiState.update {
        it.copy(
            showAudioOverlay = false,
            showSubtitleDelayOverlay = false,
            showSubtitleTimingDialog = false
        )
    }
}

private fun PlayerRuntimeController.handleSetAudioAmplificationDb(db: Int) {
    val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    applyAudioAmplification(clampedDb)
    if (_uiState.value.persistAudioAmplification) {
        scope.launch { playerSettingsDataStore.setAudioAmplificationDb(clampedDb) }
    }
}

private fun PlayerRuntimeController.handleSetPersistAudioAmplification(enabled: Boolean) {
    val currentDb = _uiState.value.audioAmplificationDb
    val currentCenterMixDb = _uiState.value.centerMixLevelDb
    _uiState.update { it.copy(persistAudioAmplification = enabled) }
    scope.launch {
        playerSettingsDataStore.setPersistAudioAmplification(
            enabled = enabled,
            dbToPersist = if (enabled) currentDb else null,
            centerMixDbToPersist = if (enabled) currentCenterMixDb else null
        )
    }
}

private fun PlayerRuntimeController.handleSetCenterMixLevelDb(db: Int) {
    val clampedDb = db.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    applyCenterMixLevel(clampedDb)
    if (_uiState.value.persistAudioAmplification) {
        scope.launch { playerSettingsDataStore.setCenterMixLevelDb(clampedDb) }
    }
}

private fun PlayerRuntimeController.handleSelectSubtitleTrack(index: Int) {
    logSwitchTrace(stage = "event-select-subtitle-internal", message = "index=$index")
    autoSubtitleSelected = true
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    resetSubtitleAutoSyncState()
    rememberInternalSubtitleSelection(index)
    selectSubtitleTrack(index)
    _uiState.update {
        it.copy(
            showSubtitleOverlay = true,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true,
            selectedAddonSubtitle = null
        )
    }
}

private fun PlayerRuntimeController.handleDisableSubtitles() {
    logSwitchTrace(
        stage = "event-disable-subtitles",
        message = "selectedSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
    )
    autoSubtitleSelected = true
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    resetSubtitleAutoSyncState()
    rememberSubtitleDisabled()
    disableSubtitles()
    _uiState.update {
        it.copy(
            showSubtitleOverlay = true,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true,
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1
        )
    }
}

private fun PlayerRuntimeController.handleSelectAddonSubtitle(event: PlayerEvent.OnSelectAddonSubtitle) {
    logSwitchTrace(
        stage = "event-select-subtitle-addon",
        message = "addonId=${event.subtitle.id} addonLang=${event.subtitle.lang} addonName=${event.subtitle.addonName}"
    )
    autoSubtitleSelected = true
    rememberAddonSubtitleSelection(event.subtitle)
    selectAddonSubtitle(event.subtitle)
    _uiState.update {
        it.copy(
            showSubtitleOverlay = true,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true
        )
    }
}

private fun PlayerRuntimeController.handleSetPlaybackSpeed(speed: Float) {
    if (isUsingMpvEngine()) {
        setPlaybackSpeedInternal(speed)
    } else {
        _exoPlayer?.let { player ->
            player.setPlaybackSpeed(speed)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().build()
        }
    }
    _uiState.update {
        it.copy(
            playbackSpeed = speed,
            showSpeedDialog = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false
        )
    }
}

private fun PlayerRuntimeController.handleToggleControls() {
    if (_uiState.value.showSubtitleTimingDialog) dismissSubtitleTimingDialog()
    if (_uiState.value.showSubtitleDelayOverlay) hideSubtitleDelayOverlay()
    val shouldShowControls = !_uiState.value.showControls
    _uiState.update {
        it.copy(
            showControls = shouldShowControls,
            showSeekOverlay = false,
            showMoreDialog = if (shouldShowControls) it.showMoreDialog else false
        )
    }
    if (shouldShowControls) scheduleHideControls()
}

private fun PlayerRuntimeController.showAudioOverlay() {
    if (isUsingMpvEngine()) updateMpvAvailableTracks()
    _uiState.update {
        it.copy(
            showAudioOverlay = true,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showMoreDialog = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true
        )
    }
}

private fun PlayerRuntimeController.showSubtitleOverlay() {
    // MPV may have selected an embedded ASS track under the hood after discovery;
    // refresh so the picker / "Off" chip match what is painted on screen.
    if (isUsingMpvEngine()) updateMpvAvailableTracks()
    _uiState.update {
        it.copy(
            showSubtitleOverlay = true,
            showAudioOverlay = false,
            showSubtitleStylePanel = false,
            showMoreDialog = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true
        )
    }
}

private fun PlayerRuntimeController.openSubtitleStylePanel() {
    _uiState.update {
        it.copy(
            showSubtitleOverlay = false,
            showSubtitleStylePanel = true,
            showMoreDialog = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true
        )
    }
}

private fun PlayerRuntimeController.showMoreDialog() {
    _uiState.update {
        it.copy(
            showMoreDialog = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showSpeedDialog = false,
            showControls = true
        )
    }
}

/** Shows the tunneling-unavailable aspect indicator for a brief moment. */
private fun PlayerRuntimeController.showTunnelingUnavailableIndicator() {
    _uiState.update {
        it.copy(
            showAspectRatioIndicator = true,
            aspectRatioIndicatorText = context.getString(R.string.player_aspect_tunneling_unavailable)
        )
    }
    hideAspectRatioIndicatorJob?.cancel()
    hideAspectRatioIndicatorJob = scope.launch {
        delay(ASPECT_INDICATOR_DISMISS_MS)
        _uiState.update { it.copy(showAspectRatioIndicator = false) }
    }
}

private fun PlayerRuntimeController.handleShowSpeedDialog() {
    if (_uiState.value.tunnelingEnabled) {
        showTunnelingUnavailableIndicator()
        return
    }
    _uiState.update {
        it.copy(
            showSpeedDialog = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showMoreDialog = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true
        )
    }
}

private fun PlayerRuntimeController.dismissTransientOverlay() {
    _uiState.update {
        it.copy(
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showSubtitleDelayOverlay = false,
            showMoreDialog = false
        )
    }
    scheduleHideControls()
}

private fun PlayerRuntimeController.handleRetry() {
    hasRenderedFirstFrame = false
    hasRetriedCurrentStreamAfter416 = false
    playbackIssueReportRequestVersion.incrementAndGet()
    resetErrorRetryState()
    lastPlaybackIssueError = null
    clearPendingEngineSwitchTrackPreference()
    resetPostPlayOverlayState(clearEpisode = false)
    _uiState.update { state ->
        state.copy(
            error = null,
            playbackIssueReportStatus = PlaybackIssueReportStatus.Idle,
            playbackIssueReportId = null,
            playbackIssueReportError = null,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            showLoadingOverlay = state.loadingOverlayEnabled,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false
        )
    }
    if (isTorrentStream && currentInfoHash != null) {
        releasePlayer()
        stopTorrentStream()
        launchTorrentSourceStream(
            stream = com.sluggyard.tv.domain.model.Stream(
                name = _uiState.value.currentStreamName,
                title = null,
                description = null,
                url = null,
                ytId = null,
                infoHash = currentInfoHash,
                fileIdx = currentFileIdx,
                externalUrl = null,
                behaviorHints = null,
                addonName = currentAddonName ?: "",
                addonLogo = currentAddonLogo
            ),
            infoHash = currentInfoHash!!,
            loadSavedProgress = true
        )
    } else {
        releasePlayer()
        initializePlayer(currentStreamUrl, currentHeaders)
    }
}

private fun PlayerRuntimeController.handleResetSubtitleDefaults() {
    scope.launch {
        val defaults = SubtitleStyleSettings()
        playerSettingsDataStore.setSubtitleSize(defaults.size)
        playerSettingsDataStore.setSubtitleTextColor(defaults.textColor)
        playerSettingsDataStore.setSubtitleBold(defaults.bold)
        playerSettingsDataStore.setSubtitleOutlineEnabled(defaults.outlineEnabled)
        playerSettingsDataStore.setSubtitleOutlineColor(defaults.outlineColor)
        playerSettingsDataStore.setSubtitleOutlineWidth(defaults.outlineWidth)
        playerSettingsDataStore.setSubtitleVerticalOffset(defaults.verticalOffset)
        playerSettingsDataStore.setSubtitleBackgroundColor(defaults.backgroundColor)
    }
}

private fun PlayerRuntimeController.handleToggleAspectRatio() {
    val state = _uiState.value
    if (state.tunnelingEnabled) {
        showTunnelingUnavailableIndicator()
        return
    }
    val newMode = nextAspectMode(state.aspectMode)
    val label = aspectModeLabel(newMode, context::getString)
    Log.d(PlayerRuntimeController.TAG, "Aspect mode toggled by user: ${state.aspectMode} -> $newMode ($label)")
    _uiState.update {
        it.copy(
            aspectMode = newMode,
            showAspectRatioIndicator = true,
            aspectRatioIndicatorText = label
        )
    }
    scope.launch {
        Log.d(PlayerRuntimeController.TAG, "Persisting aspect mode: $newMode")
        deviceLocalPlayerPreferences.setAspectMode(newMode)
    }
    hideAspectRatioIndicatorJob?.cancel()
    hideAspectRatioIndicatorJob = scope.launch {
        delay(ASPECT_INDICATOR_DISMISS_MS)
        _uiState.update { it.copy(showAspectRatioIndicator = false) }
    }
}

internal fun PlayerRuntimeController.buildStreamInfoData(): StreamInfoData {
    val state = _uiState.value
    val selectedAudio = state.audioTracks.firstOrNull { it.isSelected }
    val selectedSubtitle = state.subtitleTracks.firstOrNull { it.isSelected }
    val addonSub = state.selectedAddonSubtitle

    val activeVideoFormat = _exoPlayer?.videoFormat
    val matchedFormat = _exoPlayer?.currentTracks?.groups
        ?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected }
        ?.let { group ->
            (0 until group.length)
                .map { group.getTrackFormat(it) }
                .firstOrNull { it.id == activeVideoFormat?.id || (it.bitrate > 0 && it.bitrate == activeVideoFormat?.bitrate) }
        }

    val videoWidth = matchedFormat?.width?.takeIf { it > 0 } ?: activeVideoFormat?.width?.takeIf { it > 0 } ?: currentVideoWidth
    val videoHeight = matchedFormat?.height?.takeIf { it > 0 } ?: activeVideoFormat?.height?.takeIf { it > 0 } ?: currentVideoHeight
    val videoBitrate = activeVideoFormat?.bitrate?.takeIf { it > 0 } ?: currentVideoBitrate
    val videoCodec = activeVideoFormat?.let { format ->
        CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
    } ?: currentVideoCodec

    return StreamInfoData(
        addonName = currentAddonName,
        addonLogo = currentAddonLogo,
        streamName = state.currentStreamName,
        streamDescription = currentStreamDescription,
        filename = currentFilename,
        fileSize = currentVideoSize,
        videoCodec = videoCodec,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoFrameRate = state.detectedFrameRate.takeIf { it > 0f },
        videoBitrate = videoBitrate,
        audioCodec = selectedAudio?.codec,
        audioChannels = selectedAudio?.channelCount?.let {
            CustomDefaultTrackNameProvider.getChannelLayoutName(it)
        },
        audioSampleRate = selectedAudio?.sampleRate,
        audioLanguage = selectedAudio?.language,
        subtitleName = selectedSubtitle?.name ?: addonSub?.lang,
        subtitleCodec = selectedSubtitle?.codec,
        subtitleLanguage = selectedSubtitle?.language ?: addonSub?.lang,
        subtitleSource = when {
            addonSub != null -> context.getString(R.string.stream_info_subtitle_source_addon)
            selectedSubtitle != null -> context.getString(R.string.stream_info_subtitle_source_embedded)
            else -> null
        },
        playerEngine = when (currentInternalPlayerEngine) {
            com.sluggyard.tv.data.local.InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
            com.sluggyard.tv.data.local.InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
            com.sluggyard.tv.data.local.InternalPlayerEngine.AUTO -> null
        }
    )
}

private fun String.playbackEventsHost(): String =
    runCatching {
        Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")

private fun formatTorrentSpeed(context: android.content.Context, bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> context.getString(R.string.unit_speed_mb_s, String.format("%.1f", bytesPerSec / 1_048_576.0))
        bytesPerSec >= 1_024 -> context.getString(R.string.unit_speed_kb_s, String.format("%.0f", bytesPerSec / 1_024.0))
        else -> context.getString(R.string.unit_speed_b_s, bytesPerSec)
    }
}
