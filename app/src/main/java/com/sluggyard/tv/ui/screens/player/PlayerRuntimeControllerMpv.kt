@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.util.Log
import androidx.media3.exoplayer.SeekParameters
import com.sluggyard.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MPV_RESUME_SEEK_TOLERANCE_MS = 1500L

internal fun PlayerRuntimeController.attachMpvView(view: MpvPlayerSurfaceView?) {
    if (mpvView === view) return
    mpvView = view

    if (view == null) return
    if (!isUsingMpvEngine()) return
    if (currentStreamUrl.isBlank()) return
    // The first view attachment happens before startInitialPlaybackIfNeeded(). That startup path
    // owns the initial setMedia call, including the resume position. Queuing a zero-position
    // playFile here leaves BaseMPVView with a second pending load, so Continue Watching can issue
    // an unseeked load and a resumed load during the same surfaceCreated callback.
    if (!initialPlaybackStarted) return
    if (mpvInitializationInProgress) return

    runCatching {
        performPendingMpvHardRestartIfNeeded(view)
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        view.setMedia(currentStreamUrl, currentHeaders)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applyAudioOutputSettings(
            downmixEnabled = currentPlayerSettingsForReport.downmixEnabled,
            outputChannels = currentPlayerSettingsForReport.audioOutputChannels,
            maintainOriginalMix = currentPlayerSettingsForReport.maintainOriginalAudioOnDownmix,
        )
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage,
            preferAss = shouldPreferAssSubtitles(),
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)
        view.setPaused(false)
        applyPendingMpvSeekIfNeeded(view)
        hasRenderedFirstFrame = false
        resetMpvTrackDiscoveryWindow()
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { err ->
        val detailedError = err.message ?: context.getString(com.sluggyard.tv.R.string.player_error_mpv_surface_failed)
        if (maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = true
            )
        ) {
            return@onFailure
        }
        _uiState.update { state ->
            state.copy(
                error = detailedError,
                showLoadingOverlay = false
            )
        }
    }
}

internal fun PlayerRuntimeController.initializeMpvPlayer(
    url: String,
    headers: Map<String, String>,
    allowEngineFailover: Boolean = true
) {
    disposeExoPlayerBeforeRebuild()
    trackSelector = null

    val view = mpvView
    if (view == null) {
        setLoadingStatus(
            phase = "mpv_waiting_surface",
            message = context.getString(com.sluggyard.tv.R.string.player_loading_building),
            showOverlay = true
        )
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    runCatching {
        setLoadingStatus(
            phase = "mpv_starting",
            message = context.getString(com.sluggyard.tv.R.string.player_loading_starting),
            showOverlay = true
        )
        performPendingMpvHardRestartIfNeeded(view)
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        val initialResumePosition = resolvePendingInitialResumePosition()
        playbackAnalyticsDiagnostics.setStartupStartPosition(initialResumePosition)
        // The MPV surface is retained across episode changes; reset the bounded
        // discovery window on every load, not only when the surface attaches.
        resetMpvTrackDiscoveryWindow()
        view.setMedia(url, headers, initialResumePosition)
        playbackAnalyticsDiagnostics.recordRawEventLine(
            "PLAYER_INIT: engine=MPV host=${url.safeMpvTraceHost()} " +
                "playbackSpeed=${_uiState.value.playbackSpeed} resumePositionMs=$initialResumePosition"
        )
        if (initialResumePosition > 0L) {
            clearPendingInitialResumePosition()
            updatePlaybackTimeline(currentPosition = initialResumePosition)
        }
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applyAudioOutputSettings(
            downmixEnabled = currentPlayerSettingsForReport.downmixEnabled,
            outputChannels = currentPlayerSettingsForReport.audioOutputChannels,
            maintainOriginalMix = currentPlayerSettingsForReport.maintainOriginalAudioOnDownmix,
        )
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage,
            preferAss = shouldPreferAssSubtitles(),
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.applyAspectMode(_uiState.value.aspectMode)
        view.setPaused(false)
        applyPendingMpvSeekIfNeeded(view)

        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { err ->
        Log.e(PlayerRuntimeController.TAG, "libmpv initialize failed: ${err.message}", err)
        val detailedError = err.message ?: context.getString(com.sluggyard.tv.R.string.player_error_mpv_playback_failed)
        if (maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = allowEngineFailover
            )
        ) {
            return@onFailure
        }
        _uiState.update {
            it.copy(
                error = detailedError,
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }
}

internal fun PlayerRuntimeController.releaseMpvPlayer() {
    runCatching { mpvView?.releasePlayer() }
}

private fun String.safeMpvTraceHost(): String {
    return runCatching {
        android.net.Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

internal fun PlayerRuntimeController.pauseForLifecycle() {
    // Mark backgrounded so onPlayerError can defer recovery until onResume.
    isInBackground = true

    // Drop the MediaSession so the system doesn't route media commands (play/pause,
    // audio focus) to this player while the app is backgrounded.
    runCatching {
        currentMediaSession?.release()
        currentMediaSession = null
    }.onFailure { it.printStackTrace() }

    // Treat as user-paused so autoplay logic doesn't resume on its own.
    userPausedManually = true
    shouldEnforceAutoplayOnFirstReady = false

    if (isUsingMpvEngine()) {
        mpvView?.setPaused(true)
        stopWatchProgressSaving()
        stopProgressUpdates()
        _uiState.update { it.copy(isPlaying = false) }
        return
    }
    pauseStartTimeMs = System.currentTimeMillis()
    _exoPlayer?.let { player ->
        // Disable automatic audio focus so ExoPlayer can't re-acquire focus and
        // flip playWhenReady=true behind our back.
        player.setAudioAttributes(player.audioAttributes, false)
        player.playWhenReady = false
        player.pause()
    }
    stopWatchProgressSaving()
    stopProgressUpdates()
}

internal fun PlayerRuntimeController.resumeForLifecycle() {
    isInBackground = false

    // If the codec crashed in the background, the player was released to free
    // resources. Rebuild it now with the saved position so the user returns to a
    // clean, paused player ready to play.
    if (pendingBackgroundCrashRecovery) {
        pendingBackgroundCrashRecovery = false
        val savedPosition = backgroundCrashSavedPositionMs
        backgroundCrashSavedPositionMs = 0L
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        if (currentStreamUrl.isNotEmpty()) {
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = true)
        }
        return
    }

    val player = _exoPlayer
    if (player != null && !isUsingMpvEngine()) {
        // Restore the automatic audio focus handling disabled in pauseForLifecycle().
        player.setAudioAttributes(player.audioAttributes, true)

        // Recreate the MediaSession so media controls work in the foreground.
        if (currentMediaSession == null) {
            runCatching {
                currentMediaSession = androidx.media3.session.MediaSession.Builder(context, player).build()
                updateMediaSessionMetadata()
            }.onFailure { it.printStackTrace() }
        }
    }
}

internal fun PlayerRuntimeController.updateMpvAvailableTracks() {
    if (!isUsingMpvEngine()) return
    if (mpvTrackRefreshInProgress) return
    val view = mpvView ?: return
    val streamUrlAtRefresh = currentStreamUrl
    mpvTrackRefreshInProgress = true
    mpvTrackRefreshJob = scope.launch {
        try {
            // BaseMPVView/libmpv are tied to the Android view/native owner. Reading
            // its properties from Dispatchers.IO caused a full JNI scan to race video
            // rendering every 500 ms.
            val snapshot = view.readTrackSnapshot()
            if (!isUsingMpvEngine() || mpvView !== view || currentStreamUrl != streamUrlAtRefresh) return@launch
            applyMpvTrackSnapshot(snapshot)
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "Failed to refresh MPV track snapshot: ${err.message}")
        } finally {
            mpvTrackRefreshInProgress = false
        }
    }
}

/** Initial discovery window: covers typical local/cached loads before first frame. */
private const val MPV_TRACK_DISCOVERY_INITIAL_WINDOW_MS = 15_000L

internal fun PlayerRuntimeController.resetMpvTrackDiscoveryWindow() {
    val now = android.os.SystemClock.elapsedRealtime()
    mpvTrackDiscoveryStartedElapsedMs = now
    mpvTrackDiscoveryDeadlineElapsedMs = now + MPV_TRACK_DISCOVERY_INITIAL_WINDOW_MS
    hasScannedTextTracksOnce = false
}

private fun PlayerRuntimeController.applyMpvTrackSnapshot(snapshot: MpvTrackSnapshot) {
    val switchPending = pendingEngineSwitchTrackPreference
        ?.takeIf { it.streamUrl == currentStreamUrl && it.sourceEngine == InternalPlayerEngine.EXOPLAYER }
    logSwitchTrace(
        stage = "mpv-snapshot-read",
        message = "switchPending=${switchPending != null} audioCount=${snapshot.audioTracks.size} " +
            "subtitleCount=${snapshot.subtitleTracks.size} " +
            "selectedAudioId=${snapshot.audioTracks.firstOrNull { it.isSelected }?.id} " +
            "selectedSubtitleId=${snapshot.subtitleTracks.firstOrNull { it.isSelected }?.id}"
    )

    val previouslyEmptyInternalSubs = _uiState.value.subtitleTracks.isEmpty()
    var effectiveSnapshot = snapshot

    // Late track discovery: MPV may already be painting softsubs via slang/sid=auto
    // while preferAss never got a second chance after the early empty retries.
    if (previouslyEmptyInternalSubs &&
        snapshot.subtitleTracks.any { !it.isExternal } &&
        !subtitleDisabledByPersistedPreference &&
        rememberedTrackPreference?.subtitle !is PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    ) {
        val preferAss = shouldPreferAssSubtitles()
        if (preferAss || _uiState.value.selectedSubtitleTrackIndex < 0) {
            Log.d(
                PlayerRuntimeController.TAG,
                "MPV_TRACKS: late discovery re-applying subtitle prefs " +
                    "embedded=${snapshot.subtitleTracks.count { !it.isExternal }} preferAss=$preferAss"
            )
            mpvView?.applySubtitleLanguagePreferences(
                preferred = _uiState.value.subtitleStyle.preferredLanguage,
                secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage,
                preferAss = preferAss,
            )
            effectiveSnapshot = mpvView?.readTrackSnapshot() ?: snapshot
        }
    }

    // PreferAss may set sid while track-list selection flags are still empty. Re-read after
    // apply; if still none selected but an ASS track exists, select it so UI index matches paint.
    // Never run this heal after the user picked Off / addon / another track — that is what made
    // "click different subtitle does nothing" on MPV anime (preferAss immediately re-forced sid=1).
    val anySubtitleSelected = effectiveSnapshot.subtitleTracks.any { it.isSelected }
    val addonSubtitleSelected = _uiState.value.selectedAddonSubtitle != null
    if (!anySubtitleSelected &&
        !addonSubtitleSelected &&
        !subtitleDisabledByPersistedPreference &&
        rememberedTrackPreference?.subtitle !is PlayerRuntimeController.RememberedSubtitleSelection.Disabled &&
        rememberedTrackPreference?.subtitle !is PlayerRuntimeController.RememberedSubtitleSelection.Addon &&
        !autoSubtitleSelected &&
        shouldPreferAssSubtitles()
    ) {
        val assCandidates = effectiveSnapshot.subtitleTracks.filter { track ->
            if (track.isExternal) return@filter false
            val haystack = listOfNotNull(track.codec, track.name).joinToString(" ").lowercase()
            "ass" in haystack || "ssa" in haystack
        }
        val assId = assCandidates
            .sortedWith(
                compareBy<MpvTrack> {
                    PlayerSubtitleUtils.assDialoguePreferenceScore(name = it.name, isForced = it.isForced)
                }.thenBy { it.id },
            )
            .firstOrNull()
            ?.id
        if (assId != null) {
            Log.d(PlayerRuntimeController.TAG, "MPV_TRACKS: forcing preferAss dialogue sid=$assId for UI sync")
            mpvView?.selectSubtitleTrackById(assId)
            effectiveSnapshot = mpvView?.readTrackSnapshot() ?: effectiveSnapshot
        }
    }

    val audioTracks = effectiveSnapshot.audioTracks.mapIndexed { index, track ->
        val codecSuffix = buildList {
            track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
            track.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
        }.joinToString(" ")
        val displayName = if (codecSuffix.isBlank()) track.name else "${track.name} ($codecSuffix)"
        TrackInfo(
            index = index,
            name = displayName,
            language = track.language,
            trackId = track.id.toString(),
            codec = track.codec,
            channelCount = track.channelCount,
            isSelected = track.isSelected
        )
    }

    val internalSubtitleTracks = effectiveSnapshot.subtitleTracks
        .filterNot { it.isExternal }
        .mapIndexed { index, track ->
            TrackInfo(
                index = index,
                name = track.name,
                language = track.language,
                trackId = track.id.toString(),
                codec = CustomDefaultTrackNameProvider.formatNameFromMime(track.codec)
                    ?: track.codec,
                isForced = track.isForced,
                isSignsAndSongs = PlayerSubtitleUtils.isSignsAndSongsTrack(listOf(track.name)),
                isSelected = track.isSelected
            )
        }

    val selectedAudioIndex = audioTracks.indexOfFirst { it.isSelected }
    val selectedSubtitleIndex = internalSubtitleTracks.indexOfFirst { it.isSelected }
    val selectedExternalSubtitleTrack = effectiveSnapshot.subtitleTracks.firstOrNull { it.isExternal && it.isSelected }
    val selectedExternalSubtitle = selectedExternalSubtitleTrack != null
    logSwitchTrace(
        stage = "mpv-snapshot-mapped",
        message = "selectedAudioIndex=$selectedAudioIndex selectedSubtitleIndex=$selectedSubtitleIndex " +
            "selectedExternalSubtitle=${selectedExternalSubtitleTrack?.id ?: "none"} " +
            "mappedInternalSubtitleCount=${internalSubtitleTracks.size}"
    )

    if (internalSubtitleTracks.isNotEmpty()) {
        hasScannedTextTracksOnce = true
        if (previouslyEmptyInternalSubs) {
            Log.d(
                PlayerRuntimeController.TAG,
                "MPV_TRACKS: embedded subs ready count=${internalSubtitleTracks.size} " +
                    "selectedIndex=$selectedSubtitleIndex " +
                    "details=[${internalSubtitleTracks.joinToString(" | ") { 
                        "id=${it.trackId} lang=${it.language} codec=${it.codec} sel=${it.isSelected}" 
                    }}]"
            )
        }
    } else if (hasRenderedFirstFrame &&
        android.os.SystemClock.elapsedRealtime() > mpvTrackDiscoveryDeadlineElapsedMs
    ) {
        // Past the discovery window with first frame and still no embedded subs —
        // allow AUTO_SUB to fall through to addon tracks.
        hasScannedTextTracksOnce = true
    }
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)

    _uiState.update { state ->
        val selectedAddonFromMpvTrack = selectedExternalSubtitleTrack?.let { track ->
            state.addonSubtitles.firstOrNull { subtitle ->
                buildAddonSubtitleTrackId(subtitle).equals(track.name, ignoreCase = true)
            }
        }

        val addonSelection = when {
            selectedAddonFromMpvTrack != null -> selectedAddonFromMpvTrack
            selectedExternalSubtitle -> null
            selectedSubtitleIndex >= 0 -> null
            else -> state.selectedAddonSubtitle
        }
        val normalizedSelectedSubtitleIndex = if (selectedExternalSubtitle) -1 else selectedSubtitleIndex

        val unchanged = state.audioTracks == audioTracks &&
            state.subtitleTracks == internalSubtitleTracks &&
            state.selectedAudioTrackIndex == selectedAudioIndex &&
            state.selectedSubtitleTrackIndex == normalizedSelectedSubtitleIndex &&
            state.selectedAddonSubtitle == addonSelection

        if (unchanged) state
        else state.copy(
            audioTracks = audioTracks,
            subtitleTracks = internalSubtitleTracks,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = normalizedSelectedSubtitleIndex,
            selectedAddonSubtitle = addonSelection
        )
    }
    applyPersistedTrackPreference(
        audioTracks = audioTracks,
        subtitleTracks = internalSubtitleTracks
    )
    maybeRememberObservedReleaseTracks(
        audioTracks = audioTracks,
        subtitleTracks = internalSubtitleTracks,
    )
    logSwitchTrace(
        stage = "mpv-snapshot-after-restore",
        message = "uiAudioIndex=${_uiState.value.selectedAudioTrackIndex} " +
            "uiSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex} " +
            "uiAddonSelected=${_uiState.value.selectedAddonSubtitle?.let { "${it.lang}/${it.addonName}/${it.id}" } ?: "none"}"
    )
}

private fun PlayerRuntimeController.performPendingMpvHardRestartIfNeeded(view: MpvPlayerSurfaceView): Boolean {
    if (!pendingMpvHardRestartOnNextAttach) return false
    pendingMpvHardRestartOnNextAttach = false
    runCatching {
        Log.d(PlayerRuntimeController.TAG, "Applying MPV hard restart for startup engine failover")
        view.releasePlayer()
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "MPV hard restart release failed: ${it.message}")
    }
    return true
}

internal fun PlayerRuntimeController.applyPendingMpvSeekIfNeeded(
    view: MpvPlayerSurfaceView,
    currentPositionMs: Long = view.currentPositionMs().coerceAtLeast(0L),
    durationMs: Long = view.durationMs().coerceAtLeast(0L)
) {
    if (!isUsingMpvEngine()) return
    if (delayMpvResumeSeekUntilVideoTrack) {
        if (!view.hasVideoTrackSelectedNow()) return
        delayMpvResumeSeekUntilVideoTrack = false
    }

    val state = _uiState.value
    val savedResume = pendingResumeProgress
    val queuedPosition = state.pendingSeekPosition ?: savedResume?.position
    if (queuedPosition == null) return
    if (queuedPosition <= 0L && savedResume == null) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val target = when {
        savedResume != null && durationMs > 0L -> {
            savedResume.resolveResumePosition(durationMs).coerceAtLeast(0L)
        }
        savedResume != null -> {
            val needsDurationForPercentResume = savedResume.progressPercent != null &&
                savedResume.duration <= 0L
            if (needsDurationForPercentResume) return
            savedResume.position.coerceAtLeast(0L)
        }
        else -> queuedPosition.coerceAtLeast(0L)
    }

    if (target <= 0L) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val alreadyAtTarget = currentPositionMs >= target ||
        abs(currentPositionMs - target) <= MPV_RESUME_SEEK_TOLERANCE_MS
    if (alreadyAtTarget) {
        if (state.pendingSeekPosition != null || savedResume != null) {
            _uiState.update { it.copy(pendingSeekPosition = null) }
            pendingResumeProgress = null
        }
        return
    }

    val canSeekNow = durationMs > 0L || currentPositionMs > 0L || hasRenderedFirstFrame
    if (!canSeekNow) return

    view.seekToMs(target)
    if (state.pendingSeekPosition != target) {
        _uiState.update { it.copy(pendingSeekPosition = target) }
    }
}

internal fun PlayerRuntimeController.isUsingMpvEngine(): Boolean {
    return currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER
}

internal fun PlayerRuntimeController.currentPlaybackPositionMs(): Long? {
    return if (isUsingMpvEngine()) mpvView?.currentPositionMs()
    else _exoPlayer?.currentPosition
}

internal fun PlayerRuntimeController.currentPlaybackDurationMs(): Long {
    return if (isUsingMpvEngine()) mpvView?.durationMs() ?: 0L
    else _exoPlayer?.duration ?: 0L
}

internal fun PlayerRuntimeController.isPlaybackCurrentlyPlaying(): Boolean {
    return if (isUsingMpvEngine()) mpvView?.isPlayingNow() == true
    else _exoPlayer?.isPlaying == true
}

internal fun PlayerRuntimeController.seekPlaybackTo(
    positionMs: Long,
    seekParameters: SeekParameters = SeekParameters.CLOSEST_SYNC
) {
    if (isUsingMpvEngine()) {
        mpvView?.let { view ->
            view.seekToMs(positionMs)
            // Keep subtitle delay sticky across FF/RW seeks.
            view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        }
    } else {
        _exoPlayer?.let { player ->
            if (ExoPlayerPerformanceHelper.enabled) {
                val currentPos = player.currentPosition
                val isForwardSeek = positionMs >= currentPos
                val inBuffer = isForwardSeek && ExoPlayerPerformanceHelper.isSeekInBuffer(player, positionMs)
                if (inBuffer) {
                    suppressBufferingUiForSeek = true
                    scheduleSeekSuppressTimeout()
                } else {
                    // Out-of-buffer or backward seek: show the spinner immediately.
                    seekBufferingUiDeferred = false
                    suppressBufferingUiForSeek = false
                    seekBufferingUiJob?.cancel()
                    _uiState.update { it.copy(isBuffering = true) }
                }
                ExoPlayerPerformanceHelper.buildScrubbingParams()?.let { params ->
                    isScrubbingModeActive = true
                    player.setScrubbingModeParameters(params)
                }
            }
            player.setSeekParameters(seekParameters)
            player.seekTo(positionMs)
        }
    }
}

internal fun PlayerRuntimeController.setPlaybackSpeedInternal(speed: Float) {
    if (isUsingMpvEngine()) {
        mpvView?.setPlaybackSpeed(speed)
    } else {
        _exoPlayer?.setPlaybackSpeed(speed)
    }
}

internal fun PlayerRuntimeController.setPlaybackPaused(paused: Boolean) {
    if (isUsingMpvEngine()) {
        mpvView?.setPaused(paused)
        _uiState.update { it.copy(isPlaying = !paused) }
    } else {
        _exoPlayer?.let { player ->
            if (paused) player.pause() else player.play()
        }
    }
}

internal fun PlayerRuntimeController.pauseForStillWatchingPrompt() {
    setPlaybackPaused(true)
    if (isUsingMpvEngine()) {
        stopProgressUpdates()
        stopWatchProgressSaving()
        emitStopScrobbleForCurrentProgress()
    }
}

internal fun PlayerRuntimeController.keepMpvPlayingIfNeeded(wasPlaying: Boolean) {
    if (!wasPlaying || !isUsingMpvEngine()) return
    scope.launch {
        var resumed = false
        repeat(6) {
            if (!isUsingMpvEngine()) return@launch
            val view = mpvView ?: return@launch
            val pausedByCache = view.isPausedForCacheNow()
            val coreIdle = view.isCoreIdleNow()
            if (view.isPlayingNow() && !pausedByCache && !coreIdle) {
                _uiState.update { state ->
                    if (state.isPlaying) state else state.copy(isPlaying = true, isBuffering = false)
                }
                resumed = true
                return@launch
            }
            view.setPaused(false)
            _uiState.update { it.copy(isPlaying = true, isBuffering = true) }
            delay(120L)
        }
        if (!resumed && isUsingMpvEngine()) {
            _uiState.update { it.copy(isPlaying = false, isBuffering = true) }
        }
    }
}

/**
 * After an in-buffer seek, auto-clear the buffering-UI suppression flag after a
 * short timeout so normal buffering states resume if the seek runs long.
 */
internal fun PlayerRuntimeController.scheduleSeekSuppressTimeout() {
    scope.launch {
        delay(ExoPlayerPerformanceHelper.SEEK_SUPPRESS_TIMEOUT_MS)
        suppressBufferingUiForSeek = false
    }
}
