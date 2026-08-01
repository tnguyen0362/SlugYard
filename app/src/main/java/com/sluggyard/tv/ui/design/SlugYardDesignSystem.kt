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
 * Hybrid streaming look: near-black Netflix-like surfaces with SlugYard gold accent.
 * Quicksand is retained (no proprietary Netflix Sans / Bebas lookalikes); sizes and weights
 * follow a Caption → Body → Title → LargeTitle hierarchy tuned for couch distance.
 */
object SlugYardPalette {
    val Canvas = Color(0xFF141414)
    val Surface = Color(0xFF181818)
    val SurfaceElevated = Color(0xFF262626)
    val OnCanvas = Color(0xFFF5F5F1)
    val OnCanvasMuted = Color(0xFFB1B1B1)
    val Accent = Color(0xFFF2C94C)
    val FocusRing = Color(0xFFFFFFFF)
    /** Destructive / hard error only — never primary brand fill. */
    val Danger = Color(0xFFE50914)
    val Divider = Color(0xFF333333)
}

object SlugYardTvMetrics {
    /** Figma Home header / content inset (~58px at 1440). */
    val ScreenHorizontalInset = 58.dp
    val ScreenVerticalInset = 32.dp
    /** Figma MovieCard radius (2px). */
    val CardCornerRadius = 2.dp
    /** Figma Play / More Info buttons (4px). */
    val ButtonCornerRadius = 4.dp
    /** Nav chips and compact circular affordances only. */
    val PillCornerRadius = 50.dp
    val FocusRingWidth = 2.dp
    val FocusScale = 1.06f
    /** Figma MovieBlock card gutter (~6px). */
    val RowGap = 6.dp
    val SettingsRowRadius = 4.dp
    val SheetCornerRadius = 8.dp
    /** Figma MovieBlock title → preview gap (~15px). */
    val ShelfTitleGap = 15.dp
    /** Vertical pad inside the poster LazyRow (above/below cards). */
    val ShelfRowVerticalPad = 0.dp
    /**
     * Hero → first shelf. Figma nests the first rail in the hero fade; keep this tiny so the
     * LazyColumn still stacks without a large empty band. BringIntoView clears [RootNavBarHeight].
     */
    val ShelfStackGap = 4.dp
    /** Gap after each shelf before the next title (MoviePreview patterns — tight). */
    val ShelfTrailingGap = 12.dp

    /**
     * Home hero band height (Figma HomePage pattern ~810px at 1440; scaled for 1080p TV with
     * title/actions upper-left and shelves immediately under the fade).
     */
    val HomeHeroHeight = 520.dp

    /** Height reserved for [RootNavigation] when it floats over non-hero screens so the
     * first row of content doesn't render underneath it. Keep ≥ real nav chrome (not Figma's
     * 68px web header) so shelf titles never sit under the bar. */
    val RootNavBarHeight = 76.dp

    /** Unused for shelf cards — focus uses width expansion, not uniform scale. */
    val RowFocusScale = 1.0f
}

/** Quicksand for UI body / labels (Debrid Streams face). */
private val SlugYardQuicksand = FontFamily(
    Font(R.font.quicksand_variable, FontWeight.Light),
    Font(R.font.quicksand_variable, FontWeight.Normal),
    Font(R.font.quicksand_variable, FontWeight.Medium),
    Font(R.font.quicksand_variable, FontWeight.SemiBold),
    Font(R.font.quicksand_variable, FontWeight.Bold),
)

/**
 * Bebas Neue (SIL OFL) for hero / LargeTitle display — same role as the Figma kit’s logo-style
 * titles. Single Regular master; UI chrome stays on Quicksand.
 */
private val SlugYardBebasNeue = FontFamily(
    Font(R.font.bebas_neue_regular, FontWeight.Normal),
)

/**
 * Type scale aligned to streaming UI kit roles (Caption / Body / Headline / Title / LargeTitle).
 * Display styles use Bebas Neue; all smaller roles stay Quicksand for TV legibility.
 */
private val SlugYardTypography = Typography(
    // LargeTitle / hero titles — Bebas Neue (kit logo / display role)
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardBebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = 1.sp,
        lineHeightStyle = LineHeightStyle.Default,
    ),
    displayMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardBebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.8.sp,
    ),
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardBebasNeue,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.6.sp,
    ),
    // Title1–Title2
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    // Title3–Title4 / Headline
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    // Body / SmallBody
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.15).sp,
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.15.sp,
    ),
    // Caption / labels
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = SlugYardQuicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.15.sp,
    ),
)

private val SlugYardColors = darkColorScheme(
    primary = SlugYardPalette.Accent,
    onPrimary = Color(0xFF141414),
    background = SlugYardPalette.Canvas,
    onBackground = SlugYardPalette.OnCanvas,
    surface = SlugYardPalette.Surface,
    onSurface = SlugYardPalette.OnCanvas,
    surfaceVariant = SlugYardPalette.SurfaceElevated,
    onSurfaceVariant = SlugYardPalette.OnCanvasMuted,
    error = SlugYardPalette.Danger,
    outline = SlugYardPalette.Divider,
)

@Composable
fun SlugYardTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlugYardColors,
        typography = SlugYardTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius),
            small = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius),
            medium = RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius),
            large = RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius),
            extraLarge = RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}
