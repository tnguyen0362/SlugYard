package com.sluggyard.tv.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
}

private val SlugYardTypography = Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 42.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.6).sp,
        lineHeightStyle = LineHeightStyle.Default,
    ),
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
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
