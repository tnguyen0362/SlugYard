package com.sluggyard.tv.ui.app.details

import com.sluggyard.tv.ui.app.requestFocusReliably
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.preferLargePosterUrl
import com.sluggyard.tv.ui.app.preferTvBackdropUrl
import kotlinx.coroutines.launch

data class DetailsEpisode(
    val id: String,
    // The addon-reported episode number. This is what season/episode source lookups must use --
    // addons index streams by their own numbering, which is occasionally absolute/global rather
    // than reset per season (e.g. "episode 36" for what a viewer would call S02E01).
    val number: Int,
    val title: String,
    val releaseLabel: String?,
    val thumbnailUrl: String?,
    val watched: Boolean,
    // 1-based position within this episode's season, used for the on-screen "N. Title" label so
    // a season's first episode always reads "1." regardless of what the addon's raw number is.
    val displayNumber: Int = number,
    val description: String? = null,
)

data class DetailsSeason(
    val number: Int,
    val episodes: List<DetailsEpisode>,
    val fullyWatched: Boolean,
)

data class DetailsState(
    val id: String,
    val title: String,
    val backdropUrl: String?,
    val posterUrl: String?,
    val metadata: String,
    val description: String,
    val genres: List<String>,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val imdbRating: String? = null,
    /** WatchHub (and similar) "where to stream" labels — informational only. */
    val availability: List<String> = emptyList(),
    val inLibrary: Boolean,
    val isSeries: Boolean,
    val seasons: List<DetailsSeason> = emptyList(),
    val selectedSeason: Int? = null,
    val contentLanguage: String? = null,
    val country: String? = null,
)

data class DetailsRelatedPoster(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val contentType: String?,
    val addonId: String?,
)

/**
 * TV details: hero stays fixed. Episodes / More Like This open as translucent overlays
 * matching the player secondary panel (scrim + left panel). Back closes the overlay only.
 */
@Composable
fun DetailsScreen(
    state: DetailsState,
    onPlay: () -> Unit,
    onLibraryChanged: (Boolean) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onEpisodeSelected: (Int, DetailsEpisode) -> Unit,
    onEpisodeWatchedChanged: (DetailsEpisode, Boolean) -> Unit,
    related: List<DetailsRelatedPoster> = emptyList(),
    relatedLoading: Boolean = false,
    onRelatedSelected: (DetailsRelatedPoster) -> Unit = {},
    contentFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val selectedSeason = state.seasons.firstOrNull { it.number == state.selectedSeason }
        ?: state.seasons.firstOrNull()
    var overlay by remember(state.id) { mutableStateOf(DetailsOverlay.None) }
    val playFocusRequester = remember(state.id) { FocusRequester() }
    val focusScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas),
    ) {
        DetailsHeader(
            state = state,
            onPlay = onPlay,
            onLibraryChanged = onLibraryChanged,
            contentFocusRequester = contentFocusRequester ?: playFocusRequester,
            activeOverlay = overlay,
            onEpisodes = if (state.isSeries && selectedSeason != null) {
                { overlay = DetailsOverlay.Episodes }
            } else {
                null
            },
            onMoreLikeThis = { overlay = DetailsOverlay.Related },
            moreLikeThisLoading = relatedLoading && related.isEmpty(),
        )
        if (state.isSeries && state.seasons.isEmpty() && overlay == DetailsOverlay.None) {
            Text(
                "No episodes are available for this title.",
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(SlugYardTvMetrics.ScreenHorizontalInset)
                    .padding(bottom = 28.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = SlugYardPalette.OnCanvasMuted,
            )
        }
        if (overlay != DetailsOverlay.None) {
            DetailsSecondaryOverlay(
                overlay = overlay,
                state = state,
                related = related,
                relatedLoading = relatedLoading,
                onDismiss = {
                    overlay = DetailsOverlay.None
                    focusScope.launch {
                        (contentFocusRequester ?: playFocusRequester).requestFocusReliably(retries = 8)
                    }
                },
                onSeasonSelected = onSeasonSelected,
                onMarkSeasonWatched = onMarkSeasonWatched,
                onEpisodeSelected = onEpisodeSelected,
                onEpisodeWatchedChanged = onEpisodeWatchedChanged,
                onRelatedSelected = onRelatedSelected,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(6f),
            )
        }
    }
}

@Composable
private fun DetailsHeader(
    state: DetailsState,
    onPlay: () -> Unit,
    onLibraryChanged: (Boolean) -> Unit,
    contentFocusRequester: FocusRequester,
    activeOverlay: DetailsOverlay = DetailsOverlay.None,
    onEpisodes: (() -> Unit)? = null,
    onMoreLikeThis: (() -> Unit)? = null,
    moreLikeThisLoading: Boolean = false,
) {
    LaunchedEffect(state.id) {
        contentFocusRequester.requestFocusReliably(retries = 10)
    }
    val metadataInline = buildList {
        state.metadata
            .replace(Regex("""★\s*[\d.]+\s*IMDb"""), "")
            .replace(Regex("""\s*·\s*"""), " · ")
            .trim(' ', '·')
            .takeIf { it.isNotBlank() }
            ?.let(::add)
        state.genres.take(3).forEach(::add)
    }.joinToString("  •  ")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlugYardPalette.Surface),
    ) {
        AsyncImage(
            model = preferTvBackdropUrl(state.backdropUrl) ?: preferLargePosterUrl(state.posterUrl),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = if (state.backdropUrl != null) 0.88f else 0.38f,
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to SlugYardPalette.Canvas.copy(alpha = 0.94f),
                        0.42f to SlugYardPalette.Canvas.copy(alpha = 0.72f),
                        0.68f to SlugYardPalette.Canvas.copy(alpha = 0.22f),
                        1.0f to Color.Transparent,
                    ),
                ),
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.35f to SlugYardPalette.Canvas.copy(alpha = 0.22f),
                        0.72f to SlugYardPalette.Canvas.copy(alpha = 0.82f),
                        1.0f to SlugYardPalette.Canvas,
                    ),
                ),
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SlugYardTvMetrics.ScreenHorizontalInset,
                    end = SlugYardTvMetrics.ScreenHorizontalInset,
                    top = 72.dp,
                    bottom = 28.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(0.58f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metadataInline.isNotBlank()) {
                    Text(
                        metadataInline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlugYardPalette.OnCanvasMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.description.isNotBlank()) {
                    Text(
                        state.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = SlugYardPalette.OnCanvas.copy(alpha = 0.94f),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Compact two-column actions preserve vertical room for the synopsis while
                // keeping every target comfortably reachable with a remote.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DetailsFilledButton(
                            label = "Play",
                            onClick = onPlay,
                            primary = true,
                            leadingIcon = true,
                            modifier = Modifier.weight(1f).focusRequester(contentFocusRequester),
                        )
                        DetailsFilledButton(
                            label = if (state.inLibrary) "In My List" else "My List",
                            onClick = { onLibraryChanged(!state.inLibrary) },
                            primary = false,
                            selected = state.inLibrary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (onEpisodes != null) {
                            DetailsFilledButton(
                                label = "Episodes",
                                onClick = onEpisodes,
                                primary = false,
                                selected = activeOverlay == DetailsOverlay.Episodes,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (onMoreLikeThis != null) {
                            DetailsFilledButton(
                                label = if (moreLikeThisLoading) "More Like This…" else "More Like This",
                                onClick = onMoreLikeThis,
                                primary = false,
                                selected = activeOverlay == DetailsOverlay.Related,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsFilledButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    leadingIcon: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val filled = primary || selected
    val container = when {
        focused -> SlugYardPalette.Accent
        filled -> SlugYardPalette.OnCanvas
        else -> SlugYardPalette.SurfaceElevated
    }
    val content = when {
        focused -> Color(0xFF181818)
        filled -> SlugYardPalette.Canvas
        else -> SlugYardPalette.OnCanvas
    }
    Button(
        onClick = onClick,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = modifier
            .heightIn(min = 40.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            ),
    ) {
        if (leadingIcon) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontWeight = if (focused || filled) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
