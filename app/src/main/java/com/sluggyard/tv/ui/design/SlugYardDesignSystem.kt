package com.sluggyard.tv.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TV-first visual primitives for the clean-room UI.
 *
 * The platform sans family deliberately avoids bundling a look-alike proprietary typeface. Its
 * weights and sizes are chosen for legibility at television viewing distance; screen content
 * should use this hierarchy rather than ad-hoc text sizes.
 */
object SlugYardPalette {
    val Canvas = Color(0xFF101010)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceElevated = Color(0xFF282828)
    val OnCanvas = Color(0xFFF5F5F1)
    val OnCanvasMuted = Color(0xFFB6B5B1)
    val Accent = Color(0xFFF2C94C)
    val FocusRing = Color(0xFFFFFFFF)
    val Danger = Color(0xFFEF6A6A)
}

object SlugYardTvMetrics {
    val ScreenHorizontalInset = 56.dp
    val ScreenVerticalInset = 32.dp
    val CardCornerRadius = 6.dp
    val FocusRingWidth = 3.dp
    val FocusScale = 1.06f
    val RowGap = 14.dp

    /** Height reserved for [RewriteRootNavigation] when it floats over non-hero screens so the
     * first row of content doesn't render underneath it. */
    val RootNavBarHeight = 64.dp

    /** Dominant-card focus scale for catalog rows -- deliberately larger than [FocusScale]
     * so the focused poster reads as the clear center of attention in a row, not a subtle bump.
     * Kept below 1.15 because rows sit close enough together (see the vertical gaps in
     * RewriteHomeScreen) that a larger scale grows the card past its row and covers the
     * neighboring row's title text. */
    val RowFocusScale = 1.12f
}

/**
 * Android's built-in condensed system grotesque. It reads much closer to the tight, dense
 * display faces streaming UIs use for titles than the default wide-tracked `sans-serif` does;
 * unlike bundling a proprietary font file, this stays a device font with no asset/license cost.
 */
private val SlugYardCondensed = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold))
private val SlugYardCondensedMedium = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed-medium"), FontWeight.Medium))
private val SlugYardSans = FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal))
private val SlugYardSansMedium = FontFamily(Font(DeviceFontFamilyName("sans-serif-medium"), FontWeight.Medium))

private val SlugYardTypography = Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 46.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = LineHeightStyle.Default,
    ),
    displayMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.3).sp,
    ),
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardCondensedMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardCondensedMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSansMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSansMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSansMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSansMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSansMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSansMedium,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.sp,
    ),
)

private val SlugYardColors = darkColorScheme(
    primary = SlugYardPalette.Accent,
    onPrimary = Color(0xFF181818),
    background = SlugYardPalette.Canvas,
    onBackground = SlugYardPalette.OnCanvas,
    surface = SlugYardPalette.Surface,
    onSurface = SlugYardPalette.OnCanvas,
    surfaceVariant = SlugYardPalette.SurfaceElevated,
    onSurfaceVariant = SlugYardPalette.OnCanvasMuted,
    error = SlugYardPalette.Danger,
)

@Composable
fun SlugYardTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlugYardColors,
        typography = SlugYardTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(SlugYardTvMetrics.CardCornerRadius),
            medium = RoundedCornerShape(10.dp),
            large = RoundedCornerShape(14.dp),
            extraLarge = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
