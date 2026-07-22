package com.sluggyard.tv.ui.theme

import androidx.compose.ui.graphics.Color
import com.sluggyard.tv.domain.model.AppTheme

data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color = SlugYardPrimitives.white,
    val onSecondaryVariant: Color = SlugYardPrimitives.white,
    val focusRing: Color,
    val focusBackground: Color,
    val background: Color = SlugYardPrimitives.neutral950,
    val backgroundElevated: Color = SlugYardPrimitives.neutral900,
    val backgroundCard: Color = SlugYardPrimitives.neutral825,
    val surface: Color = SlugYardPrimitives.neutral875,
    val surfaceVariant: Color = SlugYardPrimitives.neutral800,
    val panel: Color = SlugYardPrimitives.neutral900,
    val overlay: Color = Color(0xE6000000),
    val field: Color = SlugYardPrimitives.neutral850,
    val menu: Color = SlugYardPrimitives.neutral875,
    val modal: Color = SlugYardPrimitives.neutral900,
    val playerOverlay: Color = Color(0xB3000000)
)

object ThemeColors {
    val SlugYard = ThemeColorPalette(
        // A restrained, cinema-first palette: the accent is for intent, never decoration.
        secondary = Color(0xFFF5C518),
        secondaryVariant = Color(0xFFC99B00),
        onSecondary = SlugYardPrimitives.neutral925,
        onSecondaryVariant = SlugYardPrimitives.neutral925,
        focusRing = Color(0xFFF5F5F1),
        focusBackground = Color(0xFF333333),
        background = Color(0xFF141414),
        backgroundElevated = Color(0xFF181818),
        backgroundCard = Color(0xFF242424),
        surface = Color(0xFF202020),
        surfaceVariant = Color(0xFF2B2B2B),
        panel = Color(0xFF181818),
        field = Color(0xFF2A2A2A),
        menu = Color(0xFF202020),
        modal = Color(0xFF202020)
    )

    val Crimson = ThemeColorPalette(
        secondary = SlugYardPrimitives.red500,
        secondaryVariant = SlugYardPrimitives.red600,
        focusRing = SlugYardPrimitives.red300,
        focusBackground = Color(0xFF3D1A1A),
        backgroundCard = Color(0xFF241A1A)
    )

    val Ocean = ThemeColorPalette(
        secondary = SlugYardPrimitives.blue500,
        secondaryVariant = SlugYardPrimitives.blue700,
        focusRing = SlugYardPrimitives.blue300,
        focusBackground = Color(0xFF1A2D3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1A1F24)
    )

    val Violet = ThemeColorPalette(
        secondary = SlugYardPrimitives.violet500,
        secondaryVariant = SlugYardPrimitives.violet700,
        focusRing = SlugYardPrimitives.violet300,
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1F1A24)
    )

    val Emerald = ThemeColorPalette(
        secondary = SlugYardPrimitives.green500,
        secondaryVariant = SlugYardPrimitives.green700,
        focusRing = SlugYardPrimitives.green300,
        focusBackground = Color(0xFF1A3D1E),
        backgroundCard = Color(0xFF1A241A)
    )

    val Amber = ThemeColorPalette(
        secondary = SlugYardPrimitives.amber500,
        secondaryVariant = SlugYardPrimitives.amber700,
        focusRing = SlugYardPrimitives.amber300,
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0D0D),
        backgroundElevated = Color(0xFF1E1A1A),
        backgroundCard = Color(0xFF24201A)
    )

    val Rose = ThemeColorPalette(
        secondary = SlugYardPrimitives.rose500,
        secondaryVariant = SlugYardPrimitives.rose700,
        focusRing = SlugYardPrimitives.rose300,
        focusBackground = Color(0xFF3D1A2D),
        backgroundCard = Color(0xFF241A1F)
    )

    val White = ThemeColorPalette(
        secondary = SlugYardPrimitives.neutral100,
        secondaryVariant = SlugYardPrimitives.neutral200,
        onSecondary = SlugYardPrimitives.neutral925,
        onSecondaryVariant = SlugYardPrimitives.neutral925,
        focusRing = SlugYardPrimitives.white,
        focusBackground = Color(0xFF303030),
        backgroundCard = SlugYardPrimitives.neutral850
    )

    fun getColorPalette(theme: AppTheme): ThemeColorPalette {
        return when (theme) {
            AppTheme.SLUGYARD -> SlugYard
            AppTheme.CRIMSON -> Crimson
            AppTheme.OCEAN -> Ocean
            AppTheme.VIOLET -> Violet
            AppTheme.EMERALD -> Emerald
            AppTheme.AMBER -> Amber
            AppTheme.ROSE -> Rose
            AppTheme.WHITE -> White
        }
    }
}
