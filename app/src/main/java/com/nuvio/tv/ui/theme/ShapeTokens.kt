package com.nuvio.tv.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NuvioRadiusTokens(
    val none: Dp,
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val panel: Dp,
    val full: Dp
)

@Immutable
data class NuvioShapeTokens(
    val posterCard: Shape,
    val backdropCard: Shape,
    val collectionCard: Shape,
    val button: Shape,
    val iconButton: Shape,
    val chip: Shape,
    val badge: Shape,
    val dialog: Shape,
    val sidePanel: Shape,
    val sidebar: Shape,
    val navItem: Shape,
    val progress: Shape,
    val slider: Shape,
    val field: Shape,
    val menu: Shape,
    val circle: Shape
)

object NuvioRadii {
    val tokens = NuvioRadiusTokens(
        none = 0.dp,
        xxs = 2.dp,
        xs = 3.dp,
        sm = 4.dp,
        md = 6.dp,
        lg = 8.dp,
        xl = 12.dp,
        xxl = 16.dp,
        panel = 20.dp,
        full = 999.dp
    )
}

object NuvioShapes {
    val tokens = NuvioShapeTokens(
        posterCard = RoundedCornerShape(NuvioRadii.tokens.xxs),
        backdropCard = RoundedCornerShape(NuvioRadii.tokens.sm),
        collectionCard = RoundedCornerShape(NuvioRadii.tokens.sm),
        button = RoundedCornerShape(NuvioRadii.tokens.sm),
        iconButton = RoundedCornerShape(NuvioRadii.tokens.md),
        chip = RoundedCornerShape(NuvioRadii.tokens.full),
        badge = RoundedCornerShape(NuvioRadii.tokens.xxs),
        dialog = RoundedCornerShape(NuvioRadii.tokens.lg),
        sidePanel = RoundedCornerShape(NuvioRadii.tokens.xl),
        sidebar = RoundedCornerShape(NuvioRadii.tokens.xl),
        navItem = RoundedCornerShape(NuvioRadii.tokens.full),
        progress = RoundedCornerShape(NuvioRadii.tokens.xxs),
        slider = RoundedCornerShape(NuvioRadii.tokens.full),
        field = RoundedCornerShape(NuvioRadii.tokens.sm),
        menu = RoundedCornerShape(NuvioRadii.tokens.md),
        circle = CircleShape
    )
}
