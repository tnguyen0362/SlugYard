package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.core.player.FrameRateUtils
import kotlin.math.abs

/**
 * Heuristics for resolving ambiguous 24 fps cinema tracks.
 *
 * Some containers report a flat 24.0 fps for content that is actually
 * 24000/1001 (NTSC film). When the track-reported rate sits in the ambiguous
 * 24.0±0.05 window and a probe suggests NTSC film with a meaningful delta,
 * the player should re-probe and adopt the corrected rate.
 */
internal object PlayerFrameRateHeuristics {
    private const val AMBIGUOUS_CINEMA_MIN = 23.95f
    private const val AMBIGUOUS_CINEMA_MAX = 24.05f
    private const val CORRECTION_EPSILON = 0.015f
    private const val NTSC_FILM_FPS = 24000f / 1001f

    fun isAmbiguousCinema24(value: Float): Boolean = value in AMBIGUOUS_CINEMA_MIN..AMBIGUOUS_CINEMA_MAX

    fun shouldProbeOverrideTrack(
        state: PlayerUiState,
        detection: FrameRateUtils.FrameRateDetection
    ): Boolean {
        if (state.detectedFrameRateSource != FrameRateSource.TRACK) return false

        val trackRaw = state.detectedFrameRateRaw.takeIf { it > 0f } ?: state.detectedFrameRate
        val trackIsAmbiguous = isAmbiguousCinema24(trackRaw) || isAmbiguousCinema24(state.detectedFrameRate)
        if (!trackIsAmbiguous) return false

        val probeIsNtscFilm = abs(detection.snapped - NTSC_FILM_FPS) < 0.01f
        val differsEnough = abs(detection.snapped - state.detectedFrameRate) > CORRECTION_EPSILON
        return probeIsNtscFilm && differsEnough
    }
}