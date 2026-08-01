package com.sluggyard.tv.core.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.roundToInt

/**
 * Best-effort, EDID-driven detection of whether the current display advertises
 * multiple refresh rates (at the current resolution) and/or multiple resolutions
 * via [Display.getSupportedModes].
 *
 * The result is advisory only — it informs UI affordances, it does not gate the
 * frame-rate switching code path (which silently no-ops when no matching mode
 * exists). Some TVs/boxes misreport modes, so treat the answer as a hint.
 */
object DisplayCapabilities {

    private const val TAG = "DisplayCapabilities"

    data class Snapshot(
        val supportsFrameRateSwitching: Boolean,
        val supportsResolutionSwitching: Boolean,
        val supportedModes: List<Display.Mode>,
        val currentModeId: Int,
        val apiSupported: Boolean,
    ) {
        companion object {
            val Unknown = Snapshot(
                supportsFrameRateSwitching = false,
                supportsResolutionSwitching = false,
                supportedModes = emptyList(),
                currentModeId = -1,
                apiSupported = false,
            )
        }
    }

    fun detect(activity: Activity): Snapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Snapshot.Unknown
        val display = activity.window?.decorView?.display ?: return Snapshot.Unknown
        val modes = display.supportedModes.toList()
        val (frameRateSwitch, resSwitch) = deriveSupport(modes, display.mode.modeId)
        return Snapshot(
            supportsFrameRateSwitching = frameRateSwitch,
            supportsResolutionSwitching = resSwitch,
            supportedModes = modes,
            currentModeId = display.mode.modeId,
            apiSupported = true,
        )
    }

    fun logSummary(snapshot: Snapshot) {
        if (!snapshot.apiSupported) {
            Log.i(TAG, "api=${Build.VERSION.SDK_INT} apiSupported=false (no display introspection)")
            return
        }
        val current = snapshot.supportedModes.firstOrNull { it.modeId == snapshot.currentModeId }
        val rates = snapshot.supportedModes
            .map { milliHz(it.refreshRate) / 1000f }
            .distinct()
            .sorted()
            .joinToString(",")
        val resolutions = snapshot.supportedModes
            .map { "${it.physicalWidth}x${it.physicalHeight}" }
            .distinct()
            .joinToString(",")
        val currentDesc = current?.let {
            "${it.physicalWidth}x${it.physicalHeight}@${"%.3f".format(it.refreshRate)}Hz"
        } ?: "unknown"
        Log.i(
            TAG,
            "api=${Build.VERSION.SDK_INT} current=$currentDesc modeCount=${snapshot.supportedModes.size} " +
                "rates=[$rates] resolutions=[$resolutions] " +
                "afrSupported=${snapshot.supportsFrameRateSwitching} " +
                "resSupported=${snapshot.supportsResolutionSwitching}"
        )
    }

    /**
     * Pure predicate extracted for unit testing.
     *
     * Returns `(supportsFrameRateSwitching, supportsResolutionSwitching)`. Refresh
     * rates are deduped at millihertz precision to avoid float-noise false positives
     * (e.g. 59.94f vs 59.940002f).
     */
    internal fun deriveSupport(modes: List<Display.Mode>, currentModeId: Int): Pair<Boolean, Boolean> {
        if (modes.isEmpty()) return false to false
        val current = modes.firstOrNull { it.modeId == currentModeId } ?: modes.first()
        val sameResRateCount = modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { milliHz(it.refreshRate) }
            .toSet()
            .size
        val distinctResolutions = modes.map { it.physicalWidth to it.physicalHeight }.toSet().size
        return (sameResRateCount >= 2) to (distinctResolutions >= 2)
    }

    private fun milliHz(refreshRate: Float): Int = (refreshRate * 1000f).roundToInt()
}