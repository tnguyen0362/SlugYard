package com.sluggyard.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

@Immutable
data class SlugYardSurfaceColors(
    val background: Color,
    val raised: Color,
    val card: Color,
    val default: Color,
    val variant: Color,
    val panel: Color,
    val overlay: Color,
    val field: Color,
    val menu: Color,
    val modal: Color,
    val playerOverlay: Color,
    val divider: Color
)

@Immutable
data class SlugYardTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val disabled: Color,
    val inverse: Color,
    val onAccent: Color,
    val onOverlay: Color,
    val metadata: Color
)

@Immutable
data class SlugYardFocusColors(
    val ring: Color,
    val background: Color,
    val content: Color,
    val accent: Color,
    val scrim: Color
)

@Immutable
data class SlugYardSelectionColors(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val mutedBackground: Color,
    val mutedForeground: Color
)

@Immutable
data class SlugYardMediaColors(
    val heroScrim: Color,
    val imageScrim: Color,
    val posterFallback: Color,
    val videoControlsScrim: Color,
    val glassPanelTop: Color,
    val glassPanelMiddle: Color,
    val glassPanelBottom: Color,
    val glow: Color
)

@Immutable
data class SlugYardStatusColors(
    val rating: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val info: Color,
    val watched: Color,
    val unwatched: Color,
    val cached: Color,
    val torrent: Color,
    val premium: Color
)

@Immutable
data class SlugYardDisabledColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val overlay: Color
)

@Immutable
data class SlugYardSourceColors(
    val trakt: Color,
    val tmdb: Color,
    val imdb: Color,
    val mdblist: Color
)

@Immutable
data class SlugYardContrastPair(
    val foreground: Color,
    val background: Color
)

class SlugYardColorScheme(
    palette: ThemeColorPalette,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false
) {
    private val pureBlack = SlugYardPrimitives.black
    private val pureBlackSurfaces = amoledMode && amoledSurfacesMode

    val Background = if (amoledMode) pureBlack else palette.background
    val BackgroundElevated = if (pureBlackSurfaces) pureBlack else palette.backgroundElevated
    val BackgroundCard = if (pureBlackSurfaces) pureBlack else palette.backgroundCard
    val Surface = if (pureBlackSurfaces) pureBlack else palette.surface
    val SurfaceVariant = if (pureBlackSurfaces) pureBlack else palette.surfaceVariant
    val Panel = if (pureBlackSurfaces) pureBlack else palette.panel
    val Overlay = palette.overlay
    val Field = if (pureBlackSurfaces) pureBlack else palette.field
    val Menu = if (pureBlackSurfaces) pureBlack else palette.menu
    val Modal = if (pureBlackSurfaces) pureBlack else palette.modal
    val PlayerOverlay = palette.playerOverlay
    val Divider = SlugYardPrimitives.neutral750

    val Primary = SlugYardPrimitives.neutral500
    val PrimaryVariant = SlugYardPrimitives.neutral650
    val OnPrimary = SlugYardPrimitives.white
    val Secondary = palette.secondary
    val SecondaryVariant = palette.secondaryVariant
    val OnSecondary = palette.onSecondary
    val OnSecondaryVariant = palette.onSecondaryVariant

    val TextPrimary = SlugYardPrimitives.white
    val TextSecondary = SlugYardPrimitives.neutral400
    val TextTertiary = SlugYardPrimitives.neutral600
    val TextDisabled = SlugYardPrimitives.neutral700
    val TextInverse = SlugYardPrimitives.neutral925

    val FocusRing = palette.focusRing
    val FocusBackground = palette.focusBackground
    val FocusContent = SlugYardPrimitives.white
    val FocusScrim = SlugYardPrimitives.black.copy(alpha = 0.32f)

    val Rating = SlugYardPrimitives.rating
    val Error = SlugYardPrimitives.error
    val Warning = SlugYardPrimitives.warning
    val Success = SlugYardPrimitives.success
    val Info = SlugYardPrimitives.info
    val Watched = SlugYardPrimitives.success
    val Unwatched = SlugYardPrimitives.neutral600
    val Cached = SlugYardPrimitives.blue300
    val Torrent = SlugYardPrimitives.torrent
    val Premium = SlugYardPrimitives.premium

    val Border = SlugYardPrimitives.neutral750
    val BorderFocused = palette.focusRing
    val BorderMuted = SlugYardPrimitives.neutral750.copy(alpha = 0.58f)

    val Scrim = SlugYardPrimitives.black.copy(alpha = 0.62f)
    val ImageScrim = SlugYardPrimitives.black.copy(alpha = 0.58f)
    val VideoControlsScrim = SlugYardPrimitives.black.copy(alpha = 0.72f)
    val PosterFallback = BackgroundCard

    val DisabledContainer = SurfaceVariant.copy(alpha = 0.42f)
    val DisabledContent = TextDisabled
    val DisabledBorder = Border.copy(alpha = 0.48f)
    val DisabledOverlay = SlugYardPrimitives.black.copy(alpha = 0.42f)

    val surfaces = SlugYardSurfaceColors(
        background = Background,
        raised = BackgroundElevated,
        card = BackgroundCard,
        default = Surface,
        variant = SurfaceVariant,
        panel = Panel,
        overlay = Overlay,
        field = Field,
        menu = Menu,
        modal = Modal,
        playerOverlay = PlayerOverlay,
        divider = Divider
    )

    val text = SlugYardTextColors(
        primary = TextPrimary,
        secondary = TextSecondary,
        tertiary = TextTertiary,
        disabled = TextDisabled,
        inverse = TextInverse,
        onAccent = OnSecondary,
        onOverlay = SlugYardPrimitives.white,
        metadata = TextSecondary
    )

    val focus = SlugYardFocusColors(
        ring = FocusRing,
        background = FocusBackground,
        content = FocusContent,
        accent = Secondary,
        scrim = FocusScrim
    )

    val selection = SlugYardSelectionColors(
        background = Secondary,
        foreground = OnSecondary,
        border = SecondaryVariant,
        mutedBackground = FocusBackground,
        mutedForeground = TextPrimary
    )

    val media = SlugYardMediaColors(
        heroScrim = Scrim,
        imageScrim = ImageScrim,
        posterFallback = PosterFallback,
        videoControlsScrim = VideoControlsScrim,
        glassPanelTop = Color(0xCC141414),
        glassPanelMiddle = Color(0xB8141414),
        glassPanelBottom = Color(0xA3141414),
        glow = FocusRing.copy(alpha = 0.24f)
    )

    val status = SlugYardStatusColors(
        rating = Rating,
        error = Error,
        warning = Warning,
        success = Success,
        info = Info,
        watched = Watched,
        unwatched = Unwatched,
        cached = Cached,
        torrent = Torrent,
        premium = Premium
    )

    val disabled = SlugYardDisabledColors(
        container = DisabledContainer,
        content = DisabledContent,
        border = DisabledBorder,
        overlay = DisabledOverlay
    )

    val source = SlugYardSourceColors(
        trakt = SlugYardPrimitives.trakt,
        tmdb = SlugYardPrimitives.tmdb,
        imdb = SlugYardPrimitives.imdb,
        mdblist = SlugYardPrimitives.mdblist
    )

    val contrastPairs = listOf(
        SlugYardContrastPair(TextPrimary, Background),
        SlugYardContrastPair(TextPrimary, BackgroundCard),
        SlugYardContrastPair(TextSecondary, Background),
        SlugYardContrastPair(OnSecondary, Secondary),
        SlugYardContrastPair(FocusContent, FocusBackground),
        SlugYardContrastPair(SlugYardPrimitives.white, PlayerOverlay)
    )
}

object SlugYardColors {
    val Background: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.Background

    val BackgroundElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.BackgroundElevated

    val BackgroundCard: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.BackgroundCard

    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.Surface

    val SurfaceVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.SurfaceVariant

    val Primary = SlugYardPrimitives.neutral500
    val PrimaryVariant = SlugYardPrimitives.neutral650
    val OnPrimary = SlugYardPrimitives.white
    val TextPrimary = SlugYardPrimitives.white
    val TextSecondary = SlugYardPrimitives.neutral400
    val TextTertiary = SlugYardPrimitives.neutral600
    val TextDisabled = SlugYardPrimitives.neutral700
    val Rating = SlugYardPrimitives.rating
    val Error = SlugYardPrimitives.error
    val Success = SlugYardPrimitives.success
    val Warning = SlugYardPrimitives.warning
    val Info = SlugYardPrimitives.info
    val Border = SlugYardPrimitives.neutral750

    val Secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.Secondary

    val SecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.SecondaryVariant

    val OnSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.OnSecondary

    val OnSecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.OnSecondaryVariant

    val FocusRing: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.FocusRing

    val FocusBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.FocusBackground

    val BorderFocused: Color
        @Composable
        @ReadOnlyComposable
        get() = SlugYardTheme.colors.BorderFocused
}
