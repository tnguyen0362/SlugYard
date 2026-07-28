package com.sluggyard.tv.ui.rewrite.details

import com.sluggyard.tv.ui.rewrite.requestFocusReliably
import com.sluggyard.tv.ui.rewrite.seasonDisplayLabel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import kotlinx.coroutines.launch

data class RewriteDetailsEpisode(
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
)

data class RewriteDetailsSeason(
    val number: Int,
    val episodes: List<RewriteDetailsEpisode>,
    val fullyWatched: Boolean,
)

data class RewriteDetailsState(
    val id: String,
    val title: String,
    val backdropUrl: String?,
    val posterUrl: String?,
    val metadata: String,
    val description: String,
    val genres: List<String>,
    val cast: List<String> = emptyList(),
    val inLibrary: Boolean,
    val isSeries: Boolean,
    val seasons: List<RewriteDetailsSeason> = emptyList(),
    val selectedSeason: Int? = null,
)

/** A TV details screen with explicit actions, suitable for a unidirectional state holder. */
@Composable
fun RewriteDetailsScreen(
    state: RewriteDetailsState,
    onPlay: () -> Unit,
    onLibraryChanged: (Boolean) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onEpisodeSelected: (Int, RewriteDetailsEpisode) -> Unit,
    onEpisodeWatchedChanged: (RewriteDetailsEpisode, Boolean) -> Unit,
    contentFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val selectedSeason = state.seasons.firstOrNull { it.number == state.selectedSeason }
        ?: state.seasons.firstOrNull()
    val detailsListState = rememberLazyListState()
    val selectedSeasonFocusRequester = remember(selectedSeason?.number) { FocusRequester() }
    var lastFocusedSeason by remember(state.id) { mutableStateOf(selectedSeason?.number) }
    val episodeFocusRequesters = remember(selectedSeason?.number, selectedSeason?.episodes?.size) {
        List(selectedSeason?.episodes?.size ?: 0) { FocusRequester() }
    }
    LaunchedEffect(selectedSeason?.number) {
        if (selectedSeason != null && selectedSeason.number != lastFocusedSeason) {
            detailsListState.scrollToItem(1)
            selectedSeasonFocusRequester.requestFocusReliably(retries = 8)
        }
        lastFocusedSeason = selectedSeason?.number
    }
    LazyColumn(
        state = detailsListState,
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "details_header") {
            DetailsHeader(
                state = state,
                onPlay = onPlay,
                onLibraryChanged = onLibraryChanged,
                contentFocusRequester = contentFocusRequester,
            )
        }
        if (state.isSeries && selectedSeason != null) {
            item(key = "season_selector") {
                SeasonSelector(
                    seasons = state.seasons,
                    selectedSeason = selectedSeason.number,
                    selectedFocusRequester = selectedSeasonFocusRequester,
                    downFocusRequester = episodeFocusRequesters.firstOrNull(),
                    onSeasonSelected = onSeasonSelected,
                )
            }
            item(key = "season_${selectedSeason.number}_heading") {
                Row(
                    modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(seasonDisplayLabel(selectedSeason.number), style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { onMarkSeasonWatched(selectedSeason.number) },
                        enabled = !selectedSeason.fullyWatched,
                    ) {
                        Text(if (selectedSeason.fullyWatched) "Watched" else "Mark season watched")
                    }
                }
            }
            item(key = "watched_hint") {
                Text(
                    "Hold OK on an episode to mark it watched or unwatched.",
                    modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                    style = MaterialTheme.typography.bodySmall,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            }
            itemsIndexed(
                items = selectedSeason.episodes,
                key = { index, episode -> "$index:${episode.id}" },
            ) { index, episode ->
                EpisodeRow(
                    episode = episode,
                    onOpen = { onEpisodeSelected(selectedSeason.number, episode) },
                    onWatchedChanged = { watched -> onEpisodeWatchedChanged(episode, watched) },
                    focusRequester = episodeFocusRequesters[index],
                    upFocusRequester = episodeFocusRequesters.getOrNull(index - 1),
                    downFocusRequester = episodeFocusRequesters.getOrNull(index + 1),
                )
            }
        } else if (state.isSeries) {
            item(key = "no_episodes") {
                Text(
                    "No episodes are available for this title.",
                    modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            }
        }
        item(key = "details_footer") { Spacer(Modifier.height(56.dp)) }
    }
}

@Composable
private fun DetailsHeader(
    state: RewriteDetailsState,
    onPlay: () -> Unit,
    onLibraryChanged: (Boolean) -> Unit,
    contentFocusRequester: FocusRequester? = null,
) {
    val playFocusRequester = remember(state.id) { FocusRequester() }
    LaunchedEffect(state.id) {
        (contentFocusRequester ?: playFocusRequester).requestFocusReliably(retries = 10)
    }
    Box(modifier = Modifier.fillMaxWidth().height(410.dp).background(SlugYardPalette.Surface)) {
        AsyncImage(
            model = state.backdropUrl ?: state.posterUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = if (state.backdropUrl != null) 0.48f else 0.20f,
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.10f to SlugYardPalette.Canvas.copy(alpha = 0.10f),
                        0.62f to SlugYardPalette.Canvas.copy(alpha = 0.84f),
                        1.0f to SlugYardPalette.Canvas,
                    ),
                ),
            ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.posterUrl?.let { posterUrl ->
                AsyncImage(
                    model = posterUrl,
                    contentDescription = "${state.title} poster",
                    modifier = Modifier
                        .width(176.dp)
                        .height(264.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(state.title, style = MaterialTheme.typography.displayMedium, maxLines = 2)
                Text(state.metadata, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
                if (state.genres.isNotEmpty()) {
                    Text(state.genres.joinToString("  •  "), style = MaterialTheme.typography.bodyMedium)
                }
                 Text(state.description, style = MaterialTheme.typography.bodyLarge, maxLines = 5)
                if (state.cast.isNotEmpty()) {
                    Text(
                        "Cast: ${state.cast.take(4).joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlugYardPalette.OnCanvasMuted,
                        maxLines = 1,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier
                            .focusRequester(contentFocusRequester ?: playFocusRequester),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlugYardPalette.OnCanvas,
                            contentColor = SlugYardPalette.Canvas,
                        ),
                    ) {
                        androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play")
                    }
                    OutlinedButton(onClick = { onLibraryChanged(!state.inLibrary) }) {
                         Text(if (state.inLibrary) "Remove from Watchlist" else "Add to Watchlist")
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonSelector(
    seasons: List<RewriteDetailsSeason>,
    selectedSeason: Int,
    selectedFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSeasonSelected: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(seasons, key = RewriteDetailsSeason::number) { season ->
            FilterChip(
                modifier = Modifier
                    .then(if (season.number == selectedSeason) Modifier.focusRequester(selectedFocusRequester) else Modifier)
                    .focusProperties { downFocusRequester?.let { down = it } },
                selected = season.number == selectedSeason,
                onClick = { onSeasonSelected(season.number) },
                label = { Text(seasonDisplayLabel(season.number)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: RewriteDetailsEpisode,
    onOpen: () -> Unit,
    onWatchedChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusProperties {
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            }
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) scope.launch { bringIntoViewRequester.bringIntoView() }
            }
            .semantics {
                contentDescription = "Episode ${episode.displayNumber}: ${episode.title}. " +
                    if (episode.watched) "Watched. Hold OK to mark unwatched." else "Not watched. Hold OK to mark watched."
                role = Role.Button
            }
            .combinedClickable(onClick = onOpen, onLongClick = { onWatchedChanged(!episode.watched) })
            .background(SlugYardPalette.Surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = episode.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.width(180.dp).height(102.dp).clip(RoundedCornerShape(5.dp)).background(SlugYardPalette.SurfaceElevated),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${episode.displayNumber}. ${episode.title}", style = MaterialTheme.typography.titleMedium, color = SlugYardPalette.OnCanvas)
            episode.releaseLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted) }
        }
        Icon(
            imageVector = if (episode.watched) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (episode.watched) "Watched" else "Not watched",
            tint = if (episode.watched) SlugYardPalette.Accent else SlugYardPalette.OnCanvasMuted,
        )
    }
}
