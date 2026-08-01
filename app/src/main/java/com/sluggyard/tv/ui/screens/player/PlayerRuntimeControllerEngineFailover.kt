package com.sluggyard.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import com.sluggyard.tv.R
import com.sluggyard.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Auto/manual Exo <-> MPV engine failover with audio/subtitle selection capture and restore.
 *
 * Re-authored clean-room implementation. Public extension API is preserved verbatim; only
 * the internal expression has been rewritten.
 */

private const val ENGINE_SWITCH_INFO_TIMEOUT_MS = 2200L

internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(
    detailedError: String,
    allowEngineFailover: Boolean,
    allowAfterFirstFrame: Boolean = false
): Boolean {
    // Guard chain: feature flag, single-shot latch, and phase gate. A reclaimed MediaCodec
    // is treated as a special post-start retry window — allow one hand-off after it, but
    // ordinary transient errors after first frame stay on the current engine.
    if (!allowEngineFailover) return false
    if (!autoSwitchInternalPlayerOnErrorEnabled) return false
    if (!allowAfterFirstFrame && !isStartupPhaseForEngineFailover()) return false
    if (allowAfterFirstFrame) {
        if (postFirstFrameEngineFailoverTriggered) return false
        postFirstFrameEngineFailoverTriggered = true
    } else if (startupEngineFailoverTriggered) {
        return false
    }

    val destination = pickOppositeEngine(currentInternalPlayerEngine)
    beginSwitchTraceSession(reason = "startup-failover", targetEngine = destination)
    logSwitchTrace(
        stage = "startup-failover-trigger",
        message = "allowEngineFailover=$allowEngineFailover detailedError=${detailedError.take(220)}"
    )
    rememberCurrentTrackPreferenceForEngineSwitch()
    Log.w(PlayerRuntimeController.TAG, "Startup playback error; auto-switching engine: $detailedError")
    if (!allowAfterFirstFrame) startupEngineFailoverTriggered = true

    val resumeMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    if (resumeMs > 0L) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = resumeMs) }
    }

    val switchText = context.getString(
        R.string.player_engine_switching_message,
        targetEngineLabel(destination)
    )
    hidePlayerEngineSwitchInfoJob?.cancel()
    showRecoveryOverlay()
    _uiState.update { state ->
        state.copy(
            internalPlayerEngine = destination,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchText
        )
    }

    val headingToMpv = destination == InternalPlayerEngine.MVP_PLAYER
    pendingMpvHardRestartOnNextAttach = headingToMpv
    delayMpvResumeSeekUntilVideoTrack = headingToMpv
    releasePlayer(flushPlaybackState = false)
    initializePlayer(
        url = currentStreamUrl,
        headers = currentHeaders,
        overrideInternalPlayerEngine = destination,
        allowEngineFailover = false
    )
    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(ENGINE_SWITCH_INFO_TIMEOUT_MS)
        _uiState.update { it.copy(showPlayerEngineSwitchInfo = false) }
    }
    return true
}

internal fun PlayerRuntimeController.switchInternalPlayerEngineManually() {
    if (currentStreamUrl.isBlank()) return

    val destination = pickOppositeEngine(currentInternalPlayerEngine)
    beginSwitchTraceSession(reason = "manual-osd", targetEngine = destination)
    val switchText = context.getString(
        R.string.player_engine_switching_manual_message,
        targetEngineLabel(destination)
    )
    val resumeMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    logSwitchTrace(
        stage = "manual-switch-trigger",
        message = "targetEngine=$destination positionMs=$resumeMs " +
            "audioUiIndex=${_uiState.value.selectedAudioTrackIndex} subtitleUiIndex=${_uiState.value.selectedSubtitleTrackIndex}"
    )

    rememberCurrentTrackPreferenceForEngineSwitch()

    if (resumeMs > 0L) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = resumeMs) }
    }

    startupEngineFailoverTriggered = false
    postFirstFrameEngineFailoverTriggered = false
    userPausedManually = false
    resetErrorRetryState()
    hidePlayerEngineSwitchInfoJob?.cancel()
    _uiState.update { state ->
        state.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleDelayOverlay = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            internalPlayerEngine = destination,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchText
        )
    }

    val headingToMpv = destination == InternalPlayerEngine.MVP_PLAYER
    pendingMpvHardRestartOnNextAttach = headingToMpv
    delayMpvResumeSeekUntilVideoTrack = headingToMpv

    releasePlayer(flushPlaybackState = true)
    initializePlayer(
        url = currentStreamUrl,
        headers = currentHeaders,
        overrideInternalPlayerEngine = destination,
        allowEngineFailover = true
    )

    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(ENGINE_SWITCH_INFO_TIMEOUT_MS)
        _uiState.update { it.copy(showPlayerEngineSwitchInfo = false) }
    }
}

internal fun PlayerRuntimeController.clearPendingEngineSwitchTrackPreference() {
    logSwitchTrace(
        stage = "pending-switch-pref-clear",
        message = "reason=explicit-clear previous=${pendingEngineSwitchTrackPreference != null}"
    )
    pendingEngineSwitchTrackPreference = null
}

/**
 * When AUTO initially picked ExoPlayer because genres/language were missing, meta may later
 * prove the title is anime. Switch to MPV once — that is the real AUTO path catching up,
 * not a subtitle workaround.
 */
internal fun PlayerRuntimeController.maybeReselectAutoEngineForContentClassification() {
    if (runtimeInternalPlayerEngineOverride != null) return
    if (currentStreamUrl.isBlank()) return
    scope.launch {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (settings.internalPlayerEngine != InternalPlayerEngine.AUTO) return@launch
        if (runtimeInternalPlayerEngineOverride != null) return@launch
        val desired = resolveAutoInternalPlayerEngine()
        if (desired == currentInternalPlayerEngine) return@launch
        // Only upgrade Exo → MPV for anime/ASS. Do not bounce MPV → Exo mid-playback here;
        // DV ownership is decided at the first resolve with stream filename signals.
        if (desired != InternalPlayerEngine.MVP_PLAYER ||
            currentInternalPlayerEngine != InternalPlayerEngine.EXOPLAYER
        ) {
            return@launch
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "AUTO content reselect: ExoPlayer → MPV after meta " +
                "(genres=${effectiveContentGenres()} lang=$contentLanguage type=$contentType)",
        )
        beginSwitchTraceSession(reason = "auto-content-reselect", targetEngine = desired)
        rememberCurrentTrackPreferenceForEngineSwitch()
        val resumeMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
        if (resumeMs > 0L) {
            pendingResumeProgress = null
            _uiState.update { it.copy(pendingSeekPosition = resumeMs) }
        }
        resolvedAutoPlayerEngine = desired
        pendingMpvHardRestartOnNextAttach = true
        delayMpvResumeSeekUntilVideoTrack = true
        releasePlayer(flushPlaybackState = false)
        initializePlayer(
            url = currentStreamUrl,
            headers = currentHeaders,
            overrideInternalPlayerEngine = desired,
            allowEngineFailover = false,
        )
    }
}

/**
 * Resolve the destination engine for a failover/manual switch. AUTO resolves to whichever
 * surface is currently absent so the user actually observes a change.
 */
private fun PlayerRuntimeController.pickOppositeEngine(current: InternalPlayerEngine): InternalPlayerEngine =
    when (current) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.MVP_PLAYER
        InternalPlayerEngine.MVP_PLAYER -> InternalPlayerEngine.EXOPLAYER
        InternalPlayerEngine.AUTO -> if (mpvView != null) InternalPlayerEngine.EXOPLAYER else InternalPlayerEngine.MVP_PLAYER
    }

private fun PlayerRuntimeController.isStartupPhaseForEngineFailover(): Boolean {
    val state = _uiState.value
    val position = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: playbackTimeline.value.currentPosition
    return !hasRenderedFirstFrame && (state.showLoadingOverlay || state.isBuffering || position <= 0L)
}

private fun PlayerRuntimeController.targetEngineLabel(targetEngine: InternalPlayerEngine): String =
    when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
        InternalPlayerEngine.AUTO -> context.getString(R.string.playback_player_auto)
    }

/**
 * Snapshot the current audio + subtitle selection so the destination engine can restore it.
 * The captured preference is stored in [pendingEngineSwitchTrackPreference] keyed to the
 * current stream URL; stale entries are dropped by [applyPersistedTrackPreference] on mismatch.
 */
private fun PlayerRuntimeController.rememberCurrentTrackPreferenceForEngineSwitch() {
    val state = _uiState.value
    val sourceEngine = currentInternalPlayerEngine
    logSwitchTrace(
        stage = "capture-start",
        message = "sourceEngine=$sourceEngine uiAudioIndex=${state.selectedAudioTrackIndex} " +
            "uiSubtitleIndex=${state.selectedSubtitleTrackIndex} " +
            "uiAudioCount=${state.audioTracks.size} uiSubtitleCount=${state.subtitleTracks.size} " +
            "uiAddonSelected=${state.selectedAddonSubtitle?.let { "${it.lang}/${it.addonName}/${it.id}" } ?: "none"}"
    )

    val rememberedAudio = captureCurrentAudioSelectionForEngineSwitch(state, sourceEngine)
    val rememberedSubtitle = resolveCurrentSubtitleSelectionForEngineSwitch(state, sourceEngine)

    val merged = PlayerRuntimeController.TrackPreference(
        audio = rememberedAudio,
        subtitle = rememberedSubtitle
    )
    val captured = merged.takeUnless { it.audio == null && it.subtitle == null }
    pendingEngineSwitchTrackPreference = captured?.let { preference ->
        PlayerRuntimeController.PendingEngineSwitchTrackPreference(
            streamUrl = currentStreamUrl,
            preference = preference,
            sourceEngine = sourceEngine
        )
    }
    logSwitchTrace(
        stage = "capture-finish",
        message = "sourceEngine=$sourceEngine " +
            "audio=${describeRememberedTrackForLog(rememberedAudio)} " +
            "subtitle=${describeRememberedSubtitleForLog(rememberedSubtitle)} " +
            "savedForSwitch=${captured != null}"
    )
    // Reset the persisted-restore flags so the destination engine starts clean.
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
}

/**
 * Audio capture priority: MPV track snapshot (when on MPV) -> ExoPlayer selected format ->
 * UI TrackInfo fallback. Returns null when nothing is selected anywhere.
 */
private fun PlayerRuntimeController.captureCurrentAudioSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedTrackSelection? {
    if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        val mpvAudio = mpvView?.readTrackSnapshot()?.audioTracks?.firstOrNull { it.isSelected }
        if (mpvAudio != null) {
            logSwitchTrace(
                stage = "capture-audio",
                message = "source=mpv-snapshot lang=${mpvAudio.language} name=${mpvAudio.name} id=${mpvAudio.id}"
            )
            return PlayerRuntimeController.RememberedTrackSelection(
                language = mpvAudio.language,
                name = mpvAudio.name,
                trackId = mpvAudio.id.toString()
            )
        }
    }

    val exoAudio = readExoSelectedAudioFormat()
    if (exoAudio != null) {
        logSwitchTrace(
            stage = "capture-audio",
            message = "source=exo-tracks lang=${exoAudio.language} name=${exoAudio.name} id=${exoAudio.trackId}"
        )
        return exoAudio
    }

    val uiAudio = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }
    return uiAudio?.let { track ->
        logSwitchTrace(
            stage = "capture-audio",
            message = "source=ui-fallback lang=${track.language} name=${track.name} id=${track.trackId}"
        )
        PlayerRuntimeController.RememberedTrackSelection(
            language = track.language,
            name = track.name,
            trackId = track.trackId
        )
    }.also { selection ->
        if (selection == null) {
            logSwitchTrace(stage = "capture-audio", message = "source=none result=null")
        }
    }
}

private fun PlayerRuntimeController.readExoSelectedAudioFormat(): PlayerRuntimeController.RememberedTrackSelection? {
    val player = _exoPlayer ?: return null
    player.currentTracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
        for (i in 0 until group.length) {
            if (!group.isTrackSelected(i)) continue
            val format = group.getTrackFormat(i)
            return PlayerRuntimeController.RememberedTrackSelection(
                language = format.language,
                name = format.label ?: format.language ?: "Audio",
                trackId = format.id
            )
        }
    }
    return null
}

/**
 * Subtitle capture. The strategy is layered:
 *   1. Ask the active engine (MPV snapshot or ExoPlayer tracks) for the selected track.
 *   2. If the selected track is an addon-prefixed id, resolve it back to a Subtitle.
 *   3. Otherwise build an Internal selection with index/variant hints for sparse-MPV restore.
 *   4. Fall back to UI state, then to a preferred-language pick, then to Disabled when tracks
 *      exist but none is selected.
 *
 * The result is then post-processed by [resolveCurrentSubtitleSelectionForEngineSwitch] which
 * layers in effective/explicit/remembered fallbacks for the case where capture returns null
 * or Disabled.
 */
private fun PlayerRuntimeController.captureCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    // Local record describing an Exo text track for hint computation.
    data class ExoTextEntry(
        val id: String?,
        val language: String?,
        val label: String?,
        val forced: Boolean,
        val indexHint: Int?,
        val languageIndexHint: Int?
    )

    val mpvSnapshot = if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) mpvView?.readTrackSnapshot() else null
    logSwitchTrace(
        stage = "capture-subtitle-start",
        message = "sourceEngine=$sourceEngine uiSubtitleCount=${state.subtitleTracks.size} " +
            "uiSelectedSubtitleIndex=${state.selectedSubtitleTrackIndex} " +
            "uiAddonSelected=${state.selectedAddonSubtitle?.let { "${it.lang}/${it.addonName}/${it.id}" } ?: "none"} " +
            "mpvSnapshotSubs=${mpvSnapshot?.subtitleTracks?.size ?: -1}"
    )

    // --- MPV branch ---
    if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        val mpvSelected = mpvSnapshot?.subtitleTracks?.firstOrNull { it.isSelected }
        if (mpvSelected != null) {
            if (mpvSelected.isExternal) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = mpvSelected.name,
                    language = mpvSelected.language
                ) ?: state.selectedAddonSubtitle
                if (addon != null) {
                    logSwitchTrace(
                        stage = "capture-subtitle",
                        message = "source=mpv-external->addon id=${addon.id} lang=${addon.lang} addon=${addon.addonName}"
                    )
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
            }
            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=mpv-selected-internal id=${mpvSelected.id} lang=${mpvSelected.language} " +
                    "name=${mpvSelected.name} forced=${mpvSelected.isForced} external=${mpvSelected.isExternal}"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = mpvSelected.language,
                    name = mpvSelected.name,
                    trackId = mpvSelected.id.toString(),
                    isForced = mpvSelected.isForced,
                    selectedUiTrackOverride = resolveUiSubtitleTrackForEngineSwitchHints(
                        state = state,
                        trackId = mpvSelected.id.toString(),
                        language = mpvSelected.language,
                        name = mpvSelected.name
                    ),
                    allowUiStateFallbackForHints = false
                )
            )
        }
        logSwitchTrace(stage = "capture-subtitle", message = "source=mpv-selected-internal result=none")
    }

    // --- ExoPlayer branch ---
    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val player = _exoPlayer
        val textDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        logSwitchTrace(
            stage = "capture-subtitle-exo",
            message = "textTrackDisabled=$textDisabled hasPlayer=${player != null}"
        )

        val exoEntry = readExoSelectedTextEntryWithHints(player)
        if (exoEntry == null && textDisabled) {
            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=exo-selected result=disabled(no-selected-and-disabled=true)"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        }
        if (exoEntry != null) {
            val formatId = exoEntry.id
            val formatLanguage = exoEntry.language
            val formatLabel = exoEntry.label
            if (formatId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = formatId,
                    language = formatLanguage
                ) ?: state.selectedAddonSubtitle
                if (addon != null) {
                    logSwitchTrace(
                        stage = "capture-subtitle",
                        message = "source=exo-addon-track->addon id=${addon.id} lang=${addon.lang} addon=${addon.addonName}"
                    )
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
            }
            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=exo-selected-internal id=$formatId lang=$formatLanguage name=$formatLabel " +
                    "indexHint=${exoEntry.indexHint} languageIndexHint=${exoEntry.languageIndexHint} " +
                    "forced=${exoEntry.forced}"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = PlayerRuntimeController.RememberedTrackSelection(
                    language = formatLanguage,
                    name = formatLabel ?: formatLanguage ?: "Subtitle",
                    trackId = formatId,
                    indexHint = exoEntry.indexHint,
                    languageIndexHint = exoEntry.languageIndexHint,
                    isForcedHint = exoEntry.forced
                )
            )
        }
    }

    // --- UI fallback ---
    val uiTrack = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
        ?: state.subtitleTracks.firstOrNull { it.isSelected }
    if (uiTrack != null) {
        logSwitchTrace(
            stage = "capture-subtitle",
            message = "source=ui-track id=${uiTrack.trackId} lang=${uiTrack.language} name=${uiTrack.name} forced=${uiTrack.isForced}"
        )
        return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                state = state,
                language = uiTrack.language,
                name = uiTrack.name,
                trackId = uiTrack.trackId,
                isForced = uiTrack.isForced
            )
        )
    }

    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val uiFallbackTrack = pickUiInternalSubtitleTrackForEngineSwitchFallback(state)
        if (uiFallbackTrack != null) {
            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=ui-fallback id=${uiFallbackTrack.trackId} lang=${uiFallbackTrack.language} " +
                    "name=${uiFallbackTrack.name} forced=${uiFallbackTrack.isForced}"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = uiFallbackTrack.language,
                    name = uiFallbackTrack.name,
                    trackId = uiFallbackTrack.trackId,
                    isForced = uiFallbackTrack.isForced,
                    selectedUiTrackOverride = uiFallbackTrack
                )
            )
        }
    }

    state.selectedAddonSubtitle?.let { addon ->
        logSwitchTrace(
            stage = "capture-subtitle",
            message = "source=ui-selected-addon id=${addon.id} lang=${addon.lang} addon=${addon.addonName}"
        )
        return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = addon.id,
            url = addon.url,
            language = addon.lang,
            addonName = addon.addonName
        )
    }

    // Tracks exist somewhere but nothing is selected -> treat as intentionally disabled.
    val mpvHasSubs = mpvSnapshot?.subtitleTracks?.isNotEmpty() == true
    val exoHasSubs = sourceEngine == InternalPlayerEngine.EXOPLAYER && exoHasAnyTextTrack()
    if (mpvHasSubs || exoHasSubs) {
        logSwitchTrace(
            stage = "capture-subtitle",
            message = "source=implicit-disabled mpvHasTracks=$mpvHasSubs exoHasTracks=$exoHasSubs"
        )
        return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    }

    logSwitchTrace(stage = "capture-subtitle", message = "source=none result=null")
    return null
}

/**
 * Walk ExoPlayer text groups, collecting non-addon tracks in order. Returns the selected
 * entry (with index/variant hints) or, when nothing is selected, a preferred-language pick
 * so the destination engine has a reasonable starting point.
 */
private fun PlayerRuntimeController.readExoSelectedTextEntryWithHints(
    player: androidx.media3.exoplayer.ExoPlayer?
): ExoTextCaptureEntry? {
    if (player == null) return null
    val internalTracks = mutableListOf<ExoTextCaptureEntry>()
    var selected: ExoTextCaptureEntry? = null
    player.currentTracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEach
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val trackId = format.id
            val forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
            val isAddon = trackId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true
            val entry = ExoTextCaptureEntry(
                id = trackId,
                language = format.language,
                label = format.label,
                forced = forced,
                indexHint = if (isAddon) null else internalTracks.size,
                languageIndexHint = null
            )
            if (!isAddon) internalTracks += entry
            if (group.isTrackSelected(i)) selected = entry
        }
    }

    val resolved = selected ?: run {
        val preferredTargets = subtitleLanguageTargets()
        internalTracks.firstOrNull { track ->
            preferredTargets.any { target ->
                val normalized = target.trim().lowercase()
                if (normalized == "forced") track.forced
                else PlayerSubtitleUtils.matchesLanguageCode(track.language, target)
            }
        } ?: internalTracks.firstOrNull { !it.forced } ?: internalTracks.firstOrNull()
    } ?: return null

    val selectedIndex = resolved.indexHint ?: return resolved
    val selectedInternal = internalTracks.getOrNull(selectedIndex) ?: return resolved
    val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = selectedInternal.language,
        name = selectedInternal.label ?: selectedInternal.language,
        trackId = selectedInternal.id
    )
    val languageCandidates = internalTracks.indices.filter { idx ->
        val candidate = internalTracks[idx]
        val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = candidate.language,
            name = candidate.label ?: candidate.language,
            trackId = candidate.id
        )
        candidateVariant == selectedVariant ||
            (!selectedInternal.language.isNullOrBlank() &&
                PlayerSubtitleUtils.matchesLanguageCode(candidate.language, selectedInternal.language))
    }
    return resolved.copy(
        languageIndexHint = languageCandidates.indexOf(selectedIndex).takeIf { it >= 0 }
    )
}

private data class ExoTextCaptureEntry(
    val id: String?,
    val language: String?,
    val label: String?,
    val forced: Boolean,
    val indexHint: Int?,
    val languageIndexHint: Int?
)

private fun PlayerRuntimeController.exoHasAnyTextTrack(): Boolean {
    val player = _exoPlayer ?: return false
    return player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
}

/**
 * Layer fallbacks on top of the captured subtitle selection. When capture yields null or
 * Disabled, consult (in order) the effective selection remembered from the last Exo track
 * update, the explicit selection set by user action, and the persisted remembered track
 * preference. The first non-empty match wins; otherwise the captured value is returned.
 */
private fun PlayerRuntimeController.resolveCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    val captured = captureCurrentSubtitleSelectionForEngineSwitch(state, sourceEngine)
    logSwitchTrace(
        stage = "resolve-subtitle-start",
        message = "captured=${describeRememberedSubtitleForLog(captured)} sourceEngine=$sourceEngine"
    )

    val needsFallback = captured == null ||
        captured == PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    if (!needsFallback) {
        logSwitchTrace(
            stage = "resolve-subtitle",
            message = "result=${describeRememberedSubtitleForLog(captured)} reason=captured-direct"
        )
        return captured
    }

    effectiveSubtitleSelectionForEngineSwitch
        ?.takeIf { it.streamUrl == currentStreamUrl }
        ?.selection
        ?.let {
            logSwitchTrace(
                stage = "resolve-subtitle",
                message = "result=${describeRememberedSubtitleForLog(it)} " +
                    "reason=effective-fallback captured=${describeRememberedSubtitleForLog(captured)}"
            )
            return it
        }

    explicitSubtitleSelectionForEngineSwitch
        ?.takeIf { it.streamUrl == currentStreamUrl }
        ?.selection
        ?.let {
            logSwitchTrace(
                stage = "resolve-subtitle",
                message = "result=${describeRememberedSubtitleForLog(it)} " +
                    "reason=explicit-fallback captured=${describeRememberedSubtitleForLog(captured)}"
            )
            return it
        }

    rememberedTrackPreference?.subtitle?.let {
        logSwitchTrace(
            stage = "resolve-subtitle",
            message = "result=${describeRememberedSubtitleForLog(it)} " +
                "reason=remembered-fallback captured=${describeRememberedSubtitleForLog(captured)}"
        )
        return it
    }

    logSwitchTrace(
        stage = "resolve-subtitle",
        message = "result=${describeRememberedSubtitleForLog(captured)} reason=no-fallback-available"
    )
    return captured
}

/**
 * Pick a UI internal subtitle track to seed the engine-switch capture when nothing is
 * explicitly selected. Priority: selected index -> first isSelected -> preferred-language
 * match -> first non-forced -> first any.
 */
private fun PlayerRuntimeController.pickUiInternalSubtitleTrackForEngineSwitchFallback(
    state: PlayerUiState
): TrackInfo? {
    if (state.subtitleTracks.isEmpty()) {
        logSwitchTrace(stage = "capture-subtitle-ui-fallback", message = "result=null reason=no-ui-tracks")
        return null
    }

    state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.let {
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=selectedSubtitleTrackIndex index=${it.index} id=${it.trackId} lang=${it.language}"
        )
        return it
    }
    state.subtitleTracks.firstOrNull { it.isSelected }?.let {
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=first-isSelected index=${it.index} id=${it.trackId} lang=${it.language}"
        )
        return it
    }

    val preferredIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = subtitleLanguageTargets()
    )
    if (preferredIndex >= 0) {
        state.subtitleTracks.getOrNull(preferredIndex)?.let {
            logSwitchTrace(
                stage = "capture-subtitle-ui-fallback",
                message = "reason=preferred-language index=${it.index} id=${it.trackId} lang=${it.language}"
            )
            return it
        }
    }

    state.subtitleTracks.firstOrNull { !it.isForced }?.let {
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=first-non-forced index=${it.index} id=${it.trackId} lang=${it.language}"
        )
        return it
    }
    return state.subtitleTracks.firstOrNull().also { selected ->
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=first-any index=${selected?.index} id=${selected?.trackId} lang=${selected?.language}"
        )
    }
}

internal fun PlayerRuntimeController.buildRememberedInternalSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    language: String?,
    name: String?,
    trackId: String?,
    isForced: Boolean,
    selectedUiTrackOverride: TrackInfo? = null,
    allowUiStateFallbackForHints: Boolean = true
): PlayerRuntimeController.RememberedTrackSelection {
    val uiTrack = selectedUiTrackOverride ?: if (allowUiStateFallbackForHints) {
        state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
            ?: state.subtitleTracks.firstOrNull { it.isSelected }
    } else {
        null
    }

    val selection = PlayerRuntimeController.RememberedTrackSelection(
        language = language,
        name = name,
        trackId = trackId,
        indexHint = uiTrack?.index?.takeIf { it >= 0 },
        languageIndexHint = uiTrack?.let { track ->
            subtitleLanguageOrdinalHintForEngineSwitch(state.subtitleTracks, track)
        },
        isForcedHint = uiTrack?.isForced ?: isForced,
        isSignsAndSongsHint = uiTrack?.isSignsAndSongs
    )
    logSwitchTrace(
        stage = "capture-subtitle-hints-build",
        message = "inputId=$trackId inputLang=$language inputName=$name inputForced=$isForced " +
            "uiTrack=${uiTrack?.let { "${it.index}/${it.trackId}/${it.language}/${it.name}/forced=${it.isForced}/signs=${it.isSignsAndSongs}" } ?: "none"} " +
            "result=${describeRememberedTrackForLog(selection)} allowUiFallback=$allowUiStateFallbackForHints"
    )
    return selection
}

/**
 * Resolve a UI TrackInfo from raw track metadata (trackId, language, name) so that
 * index/variant hints can be attached to the remembered selection. Tries trackId, then
 * language+name, then language+isSelected.
 */
private fun PlayerRuntimeController.resolveUiSubtitleTrackForEngineSwitchHints(
    state: PlayerUiState,
    trackId: String?,
    language: String?,
    name: String?
): TrackInfo? {
    val normalizedId = normalizeTrackMatchValue(trackId)
    if (!normalizedId.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { normalizeTrackMatchValue(it.trackId) == normalizedId }
            ?.let {
                logSwitchTrace(
                    stage = "capture-subtitle-hints",
                    message = "reason=track-id id=${it.trackId} lang=${it.language} name=${it.name}"
                )
                return it
            }
    }

    val normalizedLang = normalizeTrackMatchValue(language)
    val normalizedName = normalizeTrackMatchValue(name)
    if (!normalizedLang.isNullOrBlank() && !normalizedName.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLang &&
                normalizeTrackMatchValue(track.name) == normalizedName
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-hints",
                message = "reason=lang+name id=${it.trackId} lang=${it.language} name=${it.name}"
            )
            return it
        }
    }

    if (!normalizedLang.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLang && track.isSelected
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-hints",
                message = "reason=lang+selected id=${it.trackId} lang=${it.language} name=${it.name}"
            )
            return it
        }
    }

    logSwitchTrace(
        stage = "capture-subtitle-hints",
        message = "reason=no-ui-match trackId=$trackId language=$language name=$name"
    )
    return null
}

/**
 * Compute the ordinal position of [selectedTrack] within the subset of [tracks] that share
 * its language variant. Used as a hint for restoring selection on engines (MPV) that expose
 * sparse track metadata where direct trackId matching is unreliable.
 */
internal fun PlayerRuntimeController.subtitleLanguageOrdinalHintForEngineSwitch(
    tracks: List<TrackInfo>,
    selectedTrack: TrackInfo
): Int? {
    val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = selectedTrack.language,
        name = selectedTrack.name,
        trackId = selectedTrack.trackId
    )
    val selectedPosition = tracks.indexOfFirst { it.index == selectedTrack.index }
    if (selectedPosition < 0) return null

    val sameVariantIndexes = tracks.indices.filter { idx ->
        val candidate = tracks[idx]
        val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = candidate.language,
            name = candidate.name,
            trackId = candidate.trackId
        )
        candidateVariant == selectedVariant ||
            (!selectedTrack.language.isNullOrBlank() &&
                PlayerSubtitleUtils.matchesLanguageCode(candidate.language, selectedTrack.language))
    }

    val ordinal = sameVariantIndexes.indexOf(selectedPosition).takeIf { it >= 0 }
    logSwitchTrace(
        stage = "capture-subtitle-language-ordinal",
        message = "selectedIndex=${selectedTrack.index} selectedId=${selectedTrack.trackId} " +
            "selectedLang=${selectedTrack.language} candidates=$sameVariantIndexes ordinal=$ordinal"
    )
    return ordinal
}

/**
 * Locate an addon [Subtitle] in the UI pool by its synthesized track id, falling back to a
 * language match. Used to map engine-level external subtitle tracks back to addon entries.
 */
private fun PlayerRuntimeController.findAddonSubtitleByTrackIdOrLanguage(
    state: PlayerUiState,
    trackId: String?,
    language: String?
): com.sluggyard.tv.domain.model.Subtitle? {
    val trimmedId = trackId?.trim()
    if (!trimmedId.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            val addonTrackId = buildAddonSubtitleTrackId(subtitle)
            addonTrackId.equals(trimmedId, ignoreCase = true) ||
                trimmedId.contains(addonTrackId, ignoreCase = true)
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-addon-match",
                message = "reason=trackId matchId=${it.id} matchLang=${it.lang} addon=${it.addonName} trackId=$trackId"
            )
            return it
        }
    }
    val trimmedLang = language?.trim()
    if (!trimmedLang.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, trimmedLang)
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-addon-match",
                message = "reason=language matchId=${it.id} matchLang=${it.lang} addon=${it.addonName} language=$language"
            )
            return it
        }
    }
    logSwitchTrace(
        stage = "capture-subtitle-addon-match",
        message = "reason=no-match trackId=$trackId language=$language addonPool=${state.addonSubtitles.size}"
    )
    return null
}

private fun PlayerRuntimeController.describeRememberedSubtitleForLog(
    selection: PlayerRuntimeController.RememberedSubtitleSelection?
): String = when (selection) {
    null -> "none"
    PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> "disabled"
    is PlayerRuntimeController.RememberedSubtitleSelection.Internal ->
        "internal:${describeRememberedTrackForLog(selection.track)}"
    is PlayerRuntimeController.RememberedSubtitleSelection.Addon ->
        "addon:${selection.language}/${selection.addonName}/${selection.id}"
}

private fun PlayerRuntimeController.describeRememberedTrackForLog(
    selection: PlayerRuntimeController.RememberedTrackSelection?
): String {
    if (selection == null) return "none"
    return "lang=${selection.language} name=${selection.name} trackId=${selection.trackId} " +
        "indexHint=${selection.indexHint} languageIndexHint=${selection.languageIndexHint} " +
        "forcedHint=${selection.isForcedHint}"
}
