package com.sluggyard.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.sluggyard.tv.domain.model.AppFont
import com.sluggyard.tv.domain.model.AppTheme
import com.sluggyard.tv.domain.model.SettingsUiStyle

data class SlugYardExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalSlugYardColors = staticCompositionLocalOf {
    SlugYardColorScheme(ThemeColors.Ocean)
}

val LocalSlugYardExtendedColors = staticCompositionLocalOf {
    SlugYardExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalSlugYardTextStyles = staticCompositionLocalOf { SlugYardTextStyles }

val LocalAppTheme = staticCompositionLocalOf { AppTheme.SLUGYARD }

val LocalSettingsUiStyle = staticCompositionLocalOf { SettingsUiStyle.ZEN }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SlugYardTheme(
    appTheme: AppTheme = AppTheme.SLUGYARD,
    appFont: AppFont = AppFont.INTER,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false,
    settingsUiStyle: SettingsUiStyle = SettingsUiStyle.ZEN,
    content: @Composable () -> Unit
) {
    val palette = ThemeColors.getColorPalette(appTheme)
    val colorScheme = SlugYardColorScheme(palette, amoledMode, amoledSurfacesMode)
    val typography = buildSlugYardTypography(getFontFamily(appFont))
    val textStyles = buildSlugYardTextStyles(typography)

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = SlugYardExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalSlugYardColors provides colorScheme,
        LocalSlugYardExtendedColors provides extendedColors,
        LocalSlugYardTextStyles provides textStyles,
        LocalAppTheme provides appTheme,
        LocalSettingsUiStyle provides settingsUiStyle
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = typography,
            content = content
        )
    }
}

object SlugYardTheme {
    val colors: SlugYardColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalSlugYardColors.current

    val extendedColors: SlugYardExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSlugYardExtendedColors.current

    val textStyles: SlugYardTextStyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalSlugYardTextStyles.current

    val spacing: SlugYardSpacingTokens
        get() = SlugYardSpacing.tokens

    val radii: SlugYardRadiusTokens
        get() = SlugYardRadii.tokens

    val shapes: SlugYardShapeTokens
        get() = SlugYardShapes.tokens

    val sizes: SlugYardSizeTokens
        get() = SlugYardSizes.tokens

    val strokes: SlugYardStrokeTokens
        get() = SlugYardStrokes.tokens

    val elevations: SlugYardElevationTokens
        get() = SlugYardElevations.tokens

    val effects: SlugYardEffectTokens
        get() = SlugYardEffects.tokens

    val motion: SlugYardMotionTokens
        get() = SlugYardMotion.tokens

    val focus: SlugYardFocusTokens
        get() = SlugYardFocus.tokens

    val layout: SlugYardLayoutTokens
        get() = SlugYardLayout.tokens

    val media: SlugYardMediaTokens
        get() = SlugYardMedia.tokens

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current

    val settingsUiStyle: SettingsUiStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalSettingsUiStyle.current
}
