package com.sluggyard.tv.ui.screens.player

import android.os.Build
import android.util.Log
import com.sluggyard.tv.core.player.FrameRateUtils
import com.sluggyard.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS = 6000L
private const val AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS = 4000L

internal suspend fun PlayerRuntimeController.runAfrPreflightIfEnabled(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingMode: FrameRateMatchingMode,
    resolutionMatchingEnabled: Boolean
) {
    mpvDelayStartAfterAfrSwitch = false

    if (frameRateMatchingMode == FrameRateMatchingMode.OFF) {
        _uiState.update {
            it.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        }
        return
    }

    val activity = currentHostActivity() ?: run {
        Log.w(PlayerRuntimeController.TAG, "AFR preflight skipped: host activity unavailable")
        return
    }

    if (_uiState.value.afrProbeRunning || _uiState.value.detectedFrameRateSource != null) {
        Log.d(PlayerRuntimeController.TAG, "AFR preflight: already running or completed, skipping duplicate execution")
        return
    }

    _uiState.update {
        it.copy(
            detectedFrameRateRaw = 0f,
            detectedFrameRate = 0f,
            detectedFrameRateSource = null,
            afrProbeRunning = true
        )
    }

    // Headers without Range — used for the NextLib bypass decision. Any non-Range
    // entries imply auth headers that NextLib cannot forward.
    val streamHeaders = headers.filterKeys { !it.equals("Range", ignoreCase = true) }
    // Extractor fallback headers — force connection teardown.
    val probeHeaders = streamHeaders.toMutableMap().apply { put("Connection", "close") }

    try {
        val cached = FrameRateUtils.getCachedFrameRate(url, headers)
        if (cached != null) {
            Log.d(PlayerRuntimeController.TAG, "AFR preflight: cache hit! Using cached FPS=${cached.snapped}")
            _uiState.update {
                it.copy(
                    detectedFrameRateRaw = cached.raw,
                    detectedFrameRate = cached.snapped,
                    detectedFrameRateSource = FrameRateSource.PROBE
                )
            }
            applyDetectedFrameRateToDisplay(
                activity = activity,
                rawFps = cached.raw,
                snappedFps = cached.snapped,
                videoWidth = cached.videoWidth,
                videoHeight = cached.videoHeight,
                resolutionMatchingEnabled = resolutionMatchingEnabled
            )
            return
        }

        val nextLibDetection = withTimeoutOrNull(AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                FrameRateUtils.detectFrameRateFromNextLib(
                    context = context,
                    sourceUrl = url,
                    headers = streamHeaders
                )
            }
        }
        val detection = nextLibDetection ?: run {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight NextLib probe failed/timed out after ${AFR_PREFLIGHT_NEXTLIB_TIMEOUT_MS}ms; trying extractor fallback"
            )
            withTimeoutOrNull(AFR_PREFLIGHT_FALLBACK_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    FrameRateUtils.detectFrameRateFromExtractor(
                        context = context,
                        sourceUrl = url,
                        headers = probeHeaders
                    )
                }
            }
        }

        if (detection == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AFR preflight probe timed out/failed (NextLib + extractor fallback)"
            )
            return
        }

        FrameRateUtils.cacheFrameRate(url, headers, detection)

        _uiState.update {
            it.copy(
                detectedFrameRateRaw = detection.raw,
                detectedFrameRate = detection.snapped,
                detectedFrameRateSource = FrameRateSource.PROBE
            )
        }
        applyDetectedFrameRateToDisplay(
            activity = activity,
            rawFps = detection.raw,
            snappedFps = detection.snapped,
            videoWidth = detection.videoWidth,
            videoHeight = detection.videoHeight,
            resolutionMatchingEnabled = resolutionMatchingEnabled
        )
    } finally {
        withContext(NonCancellable) {
            _uiState.update { it.copy(afrProbeRunning = false) }
        }
    }
}

private suspend fun PlayerRuntimeController.applyDetectedFrameRateToDisplay(
    activity: android.app.Activity,
    rawFps: Float,
    snappedFps: Float,
    videoWidth: Int?,
    videoHeight: Int?,
    resolutionMatchingEnabled: Boolean
) {
    val prefer23976Near24 = rawFps in 23.95f..23.999f
    val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
        activity = activity,
        detectedFps = snappedFps,
        prefer23976Near24 = prefer23976Near24
    )
    val initialDisplayModeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        withContext(Dispatchers.Main) {
            activity.window?.decorView?.display?.mode?.modeId
        }
    } else {
        null
    }

    val result = FrameRateUtils.matchFrameRateAndWait(
        activity = activity,
        frameRate = targetFrameRate,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        resolutionMatchingEnabled = resolutionMatchingEnabled
    ) ?: return

    val switchedDisplayMode = initialDisplayModeId != null &&
        initialDisplayModeId != result.appliedMode.modeId
    mpvDelayStartAfterAfrSwitch = switchedDisplayMode

    _uiState.update {
        it.copy(
            displayModeInfo = DisplayModeInfo(
                width = result.appliedMode.physicalWidth,
                height = result.appliedMode.physicalHeight,
                refreshRate = result.appliedMode.refreshRate
            ),
            showDisplayModeInfo = true
        )
    }
}