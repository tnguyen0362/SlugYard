package com.sluggyard.tv.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class SlugYardRadiusTokens(
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
data class SlugYardShapeTokens(
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

object SlugYardRadii {
    val tokens = SlugYardRadiusTokens(
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

object SlugYardShapes {
    val tokens = SlugYardShapeTokens(
        posterCard = RoundedCornerShape(SlugYardRadii.tokens.xxs),
        backdropCard = RoundedCornerShape(SlugYardRadii.tokens.sm),
        collectionCard = RoundedCornerShape(SlugYardRadii.tokens.sm),
        button = RoundedCornerShape(SlugYardRadii.tokens.sm),
        iconButton = RoundedCornerShape(SlugYardRadii.tokens.md),
        chip = RoundedCornerShape(SlugYardRadii.tokens.full),
        badge = RoundedCornerShape(SlugYardRadii.tokens.xxs),
        dialog = RoundedCornerShape(SlugYardRadii.tokens.lg),
        sidePanel = RoundedCornerShape(SlugYardRadii.tokens.xl),
        sidebar = RoundedCornerShape(SlugYardRadii.tokens.xl),
        navItem = RoundedCornerShape(SlugYardRadii.tokens.full),
        progress = RoundedCornerShape(SlugYardRadii.tokens.xxs),
        slider = RoundedCornerShape(SlugYardRadii.tokens.full),
        field = RoundedCornerShape(SlugYardRadii.tokens.sm),
        menu = RoundedCornerShape(SlugYardRadii.tokens.md),
        circle = CircleShape
    )
}
