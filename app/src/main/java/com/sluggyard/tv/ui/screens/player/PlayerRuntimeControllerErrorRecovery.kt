package com.sluggyard.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.sluggyard.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STARTUP_AUTO_RETRIES = 2
private const val MAX_AUTO_RETRIES = 2
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

internal fun PlayerRuntimeController.attemptStartupRecovery(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (hasRenderedFirstFrame) return false
    if (!isRetryablePlaybackError(error)) return false
    if (startupRetryCount >= MAX_STARTUP_AUTO_RETRIES) return false

    handleParsingErrorFallback(error)

    val paused = userPausedManually
    val attempt = startupRetryCount
    startupRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery ${attempt + 1}/$MAX_STARTUP_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        releasePlayer(flushPlaybackState = false)
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * Classifies a [PlaybackException] as transient enough to be worth an automatic retry.
 *
 * Covers source/IO errors, parsing glitches, and the unexpected runtime exceptions
 * (IllegalState/NPE) that frequently follow pause/resume or seek on flaky streams.
 * Decoder-init and DRM failures are treated as fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // Source / IO (2xxx range).
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val httpCause = error.findCauseOfType<HttpDataSource.InvalidResponseCodeException>()
            if (httpCause != null) {
                val code = httpCause.responseCode
                !(code == 400 || code == 401 || code == 403 || code == 404 || code == 410)
            } else {
                true
            }
        }
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // Decoder errors — frequently transient after pause/resume on some hardware.
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // Behind-the-scenes / unexpected errors (often IllegalStateException / NPE).
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

/**
 * Audio-track failures the safe-audio → audio-disabled fallback ladder can recover.
 *
 * - ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001): AudioTrack creation failed (e.g. the
 *   requested passthrough/offload encoding is rejected by the sink).
 * - ERROR_CODE_AUDIO_TRACK_WRITE_FAILED (5002): an AudioTrack write failed, most
 *   often AudioTrack.ERROR_DEAD_OBJECT (-6) when an HDMI/audio-route renegotiation
 *   invalidates an E-AC-3/AC-3 passthrough or offload track mid-playback.
 *
 * Both are fixed by re-selecting audio with tunneling/passthrough off and channel
 * count clamped to device capabilities (safe-audio mode), or by dropping audio —
 * so a write failure must follow the same path as an init failure rather than the
 * fatal error screen.
 *
 * [combinedMessage] is the concatenated exception/cause messages; the string checks
 * are a safety net for devices that surface the same failure under a generic code.
 */
internal fun isAudioTrackFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
        combinedMessage.contains("audiotrack write failed", ignoreCase = true)
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = when (code) {
            400 -> context.getString(R.string.player_error_stream_blocked)
            401 -> context.getString(R.string.player_error_stream_expired)
            403 -> context.getString(R.string.player_error_stream_blocked)
            404 -> context.getString(R.string.player_error_stream_removed)
            410 -> context.getString(R.string.player_error_stream_expired)
            429 -> context.getString(R.string.player_error_stream_rate_limited)
            500, 502, 503, 504 -> context.getString(R.string.player_error_stream_unavailable)
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Provider returned non-video content.
    val isUnrecognizedFormat = findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(R.string.player_error_source_invalid_content, errorCodeName)
    }

    // Codec/renderer failures.
    val isRendererError = errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    if (isRendererError) {
        val meaningfulMessage = findMostRelevantCauseMessage()
        val decoderHeader = meaningfulMessage ?: context.getString(R.string.player_error_decoder)
        val unsupported = context.getString(R.string.player_error_unsupported_format, errorCodeName)
        return "$decoderHeader\n\n$unsupported"
    }

    val meaningfulMessage = findMostRelevantCauseMessage()
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

private inline fun <reified T : Throwable> Throwable.findCauseOfType(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

/**
 * Automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the same player; the second fully rebuilds it, so
 * recovery stays on the loading overlay until playback succeeds or finally fails.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should surface to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!isRetryablePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    handleParsingErrorFallback(error)

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Snapshot the position so we can resume after re-init.
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (isFirstAttempt) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (isFirstAttempt && !error.errorCode.isCorruptStateError()) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            // Skip for corrupt-state errors (decoding/parsing) which need a full teardown.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                player.playWhenReady = !paused
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPosition > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
                }
                initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
    }
    return true
}

/**
 * Reset the retry counters. Call when playback reaches a healthy state (first frame
 * rendered, or a user-initiated retry).
 */
internal fun PlayerRuntimeController.resetErrorRetryState() {
    startupRetryCount = 0
    errorRetryCount = 0
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            resetErrorRetryState()
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * With decoderPriority == 1 (EXTENSION_RENDERER_MODE_ON, the default) and tunneling
 * off, audio passthrough can fail on certain devices/formats. Rather than tearing
 * down and rebuilding the whole player, apply an imperceptible speed change
 * (1.00001×) that forces ExoPlayer to decode audio through the software PCM
 * pipeline — the same path the user triggers by manually changing playback speed.
 *
 * One-shot per stream; if it fails again the normal retry logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // EXTENSION_RENDERER_MODE_ON only
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true

    val player = _exoPlayer ?: return false
    val savedPosition = player.currentPosition.takeIf { it > 0L } ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }

    return true
}

/**
 * DV7-to-HEVC decoder fallback for ERROR_CODE_DECODER_INIT_FAILED (4003).
 *
 * With decoderPriority == 1 (EXTENSION_RENDERER_MODE_ON), a decoder init failure is
 * often Dolby Vision profile 7 content on a device without a DV decoder. Enabling
 * the DV7-to-HEVC mapping lets the HEVC decoder handle the stream instead.
 *
 * Unlike the PCM fallback this needs a full player rebuild because the mapping is
 * baked into the renderers factory at build time. Tunneling state is irrelevant.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDv7HevcFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (hasTriedDv7HevcFallback) return false
    if (cachedDecoderPriority != 1) return false
    // DV7-to-HEVC already active — nothing more to try.
    if (forceDv7ToHevc) return false

    hasTriedDv7HevcFallback = true
    forceDv7ToHevc = true

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4003) — retrying with DV7-to-HEVC mapping, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    // Loading overlay with fallback info, not the error screen.
    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

private fun PlayerRuntimeController.handleParsingErrorFallback(error: PlaybackException) {
    val isParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    if (isParsingError && currentStreamMimeType != null) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Parsing error [${error.errorCode}] detected with mimeType=$currentStreamMimeType. " +
                "Clearing mimeType override for fallback."
        )
        currentStreamMimeType = null
        currentStreamResponseHeaders = emptyMap()
    }
}

/**
 * True if the error code implies corrupt internal state that needs a full teardown.
 * Lightweight recovery (seekTo + prepare) is insufficient for these.
 */
private fun Int.isCorruptStateError(): Boolean {
    return this == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        this == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
        this == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        this == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        this == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        this == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
}