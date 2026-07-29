package com.sluggyard.tv.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sluggyard.tv.R

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
    /** Action buttons, chips, and header tabs — stadium / pill, not squared cards. */
    val PillCornerRadius = 50.dp
    val FocusRingWidth = 3.dp
    val FocusScale = 1.06f
    val RowGap = 14.dp

    /** Height reserved for [RewriteRootNavigation] when it floats over non-hero screens so the
     * first row of content doesn't render underneath it. */
    val RootNavBarHeight = 76.dp

    /** Unused for shelf cards — focus uses width expansion, not uniform scale. */
    val RowFocusScale = 1.0f
}

/** The same rounded Quicksand variable face used by Debrid Streams. */
private val SlugYardQuicksand = FontFamily(
    Font(R.font.quicksand_variable, FontWeight.Light),
    Font(R.font.quicksand_variable, FontWeight.Normal),
    Font(R.font.quicksand_variable, FontWeight.Medium),
    Font(R.font.quicksand_variable, FontWeight.SemiBold),
    Font(R.font.quicksand_variable, FontWeight.Bold),
)
private val SlugYardDisplay = SlugYardQuicksand
private val SlugYardDisplayMedium = SlugYardQuicksand
private val SlugYardSans = SlugYardQuicksand
private val SlugYardSansMedium = SlugYardQuicksand

private val SlugYardTypography = Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(
         fontFamily = SlugYardDisplay,
         fontWeight = FontWeight.Normal,
         fontSize = 40.sp,
         lineHeight = 44.sp,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = LineHeightStyle.Default,
    ),
    displayMedium = androidx.compose.ui.text.TextStyle(
         fontFamily = SlugYardDisplay,
         fontWeight = FontWeight.Normal,
         fontSize = 32.sp,
         lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    displaySmall = androidx.compose.ui.text.TextStyle(
         fontFamily = SlugYardDisplay,
         fontWeight = FontWeight.Medium,
         fontSize = 28.sp,
         lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineLarge = androidx.compose.ui.text.TextStyle(
         fontFamily = SlugYardDisplay,
         fontWeight = FontWeight.Medium,
         fontSize = 28.sp,
         lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
         fontFamily = SlugYardDisplayMedium,
        fontWeight = FontWeight.Medium,
         fontSize = 22.sp,
         lineHeight = 27.sp,
        letterSpacing = (-0.1).sp,
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
         fontFamily = SlugYardDisplayMedium,
        fontWeight = FontWeight.Medium,
         fontSize = 19.sp,
         lineHeight = 24.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardSansMedium,
        fontWeight = FontWeight.Medium,
         fontSize = 18.sp,
         lineHeight = 24.sp,
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
            extraSmall = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
            small = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
            medium = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
            large = RoundedCornerShape(14.dp),
            extraLarge = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
