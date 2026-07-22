package com.sluggyard.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class SlugYardStrokeTokens(
    val none: Dp,
    val hairline: Dp,
    val thin: Dp,
    val medium: Dp,
    val focus: Dp,
    val heavy: Dp,
    val progress: Dp,
    val divider: Dp
)

@Immutable
data class SlugYardElevationTokens(
    val none: Dp,
    val card: Dp,
    val focused: Dp,
    val menu: Dp,
    val dialog: Dp,
    val overlay: Dp
)

@Immutable
data class SlugYardEffectTokens(
    val blurSoft: Dp,
    val blurPanel: Dp,
    val blurStrong: Dp,
    val scrimLightAlpha: Float,
    val scrimMediumAlpha: Float,
    val scrimStrongAlpha: Float,
    val glowSoftAlpha: Float,
    val glowStrongAlpha: Float,
    val imageOverlayAlpha: Float,
    val disabledAlpha: Float,
    val shimmerLowAlpha: Float,
    val shimmerHighAlpha: Float
)

object SlugYardStrokes {
    val tokens = SlugYardStrokeTokens(
        none = 0.dp,
        hairline = 1.dp,
        thin = 1.5.dp,
        medium = 2.dp,
        focus = 2.dp,
        heavy = 3.dp,
        progress = 4.dp,
        divider = 1.dp
    )
}

object SlugYardElevations {
    val tokens = SlugYardElevationTokens(
        none = 0.dp,
        card = 1.dp,
        focused = 4.dp,
        menu = 6.dp,
        dialog = 8.dp,
        overlay = 12.dp
    )
}

object SlugYardEffects {
    val tokens = SlugYardEffectTokens(
        blurSoft = 8.dp,
        blurPanel = 16.dp,
        blurStrong = 24.dp,
        scrimLightAlpha = 0.24f,
        scrimMediumAlpha = 0.48f,
        scrimStrongAlpha = 0.72f,
        glowSoftAlpha = 0.12f,
        glowStrongAlpha = 0.24f,
        imageOverlayAlpha = 0.56f,
        disabledAlpha = 0.38f,
        shimmerLowAlpha = 0.06f,
        shimmerHighAlpha = 0.14f
    )
}
