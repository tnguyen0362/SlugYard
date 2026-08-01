@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.releasePlayer() {
    releasePlayer(flushPlaybackState = true)
}

internal fun PlayerRuntimeController.releasePlayer(flushPlaybackState: Boolean) {
    isReleasingPlayer = true

    if (flushPlaybackState) {
        stopTorrentStream()
        flushPlaybackSnapshotForSwitchOrExit()
    }

    notifyAudioSessionUpdate(active = false)
    unregisterAudioDelayRouteCallback()

    // Cancel transient watchdog/overlay jobs.
    firstFrameWatchdogJob?.cancel(); firstFrameWatchdogJob = null
    stallWatchdogJob?.cancel(); stallWatchdogJob = null
    pauseOverlayJob?.cancel(); pauseOverlayJob = null
    seekBufferingUiJob?.cancel(); seekBufferingUiJob = null
    hideSeekOverlayJob?.cancel(); hideSeekOverlayJob = null
    hideAspectRatioIndicatorJob?.cancel(); hideAspectRatioIndicatorJob = null
    bufferLogJob?.cancel(); bufferLogJob = null

    // Release the loudness enhancer audio effect if present.
    runCatching { loudnessEnhancer?.release() }
    loudnessEnhancer = null

    // Tear down the media session.
    runCatching {
        currentMediaSession?.release()
        currentMediaSession = null
    }.onFailure { it.printStackTrace() }

    // Cancel long-lived observer/progress jobs.
    progressJob?.cancel()
    mpvTrackRefreshJob?.cancel(); mpvTrackRefreshJob = null
    mpvTrackRefreshInProgress = false
    hideControlsJob?.cancel()
    watchProgressSaveJob?.cancel()
    seekProgressSyncJob?.cancel()
    frameRateProbeJob?.cancel()
    hideStreamSourceIndicatorJob?.cancel(); hideStreamSourceIndicatorJob = null
    _uiState.update { it.copy(showStreamSourceIndicator = false) }
    hidePlayerEngineSwitchInfoJob?.cancel()
    hideSubtitleDelayOverlayJob?.cancel()
    subtitleAutoSyncLoadJob?.cancel()
    playbackPreparationJob?.cancel(); playbackPreparationJob = null
    traktMappingJob?.cancel(); traktMappingJob = null
    delayMpvResumeSeekUntilVideoTrack = false
    nextEpisodeAutoPlayJob?.cancel(); nextEpisodeAutoPlayJob = null
    debridResolveJob?.cancel(); debridResolveJob = null
    stillWatchingPromptJob?.cancel(); stillWatchingPromptJob = null
    errorRetryJob?.cancel(); errorRetryJob = null
    stableProgressResetJob?.cancel(); stableProgressResetJob = null

    releaseMpvPlayer()

    // Stop and release the ExoPlayer instance defensively.
    _exoPlayer?.let { player ->
        runCatching { player.playWhenReady = false }
        runCatching { player.pause() }
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.release() }
    }
    _exoPlayer = null
    ffmpegAudioRenderer = null
    updateAudioControlAvailability()
    playbackSpeedAwareAudioSink = null
    resetPlaybackTimeline()
    isReleasingPlayer = false
}

internal fun PlayerRuntimeController.notifyAudioSessionUpdate(active: Boolean) {
    val player = _exoPlayer ?: return
    try {
        val action = if (active) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        val intent = Intent(action).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (active) {
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            }
        }
        context.sendBroadcast(intent)
    } catch (e: SecurityException) {
        e.printStackTrace()
    }
}