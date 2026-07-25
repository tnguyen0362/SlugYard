package com.sluggyard.tv.ui.rewrite.details

import com.sluggyard.tv.ui.rewrite.requestFocusReliably
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics

data class RewriteDetailsEpisode(
    val id: String,
    val number: Int,
    val title: String,
    val releaseLabel: String?,
    val thumbnailUrl: String?,
    val watched: Boolean,
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
    onChooseStream: () -> Unit,
    onLibraryChanged: (Boolean) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onEpisodeSelected: (RewriteDetailsEpisode) -> Unit,
    onEpisodeWatchedChanged: (RewriteDetailsEpisode, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedSeason = state.seasons.firstOrNull { it.number == state.selectedSeason }
        ?: state.seasons.firstOrNull()
    // One FocusRequester per episode so DPAD up/down between rows is an explicit, single-step
    // hop instead of relying on Compose's geometric focus search -- the prior implementation
    // nested a separately-focusable "Mark watched" OutlinedButton inside a clickable Row, which
    // created TWO focus stops per episode and made up/down navigation feel like it randomly got
    // "stuck" skipping between them.
    val episodeFocusRequesters = remember(selectedSeason?.number, selectedSeason?.episodes?.size) {
        List(selectedSeason?.episodes?.size ?: 0) { FocusRequester() }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 56.dp),
    ) {
        item(key = "header:${state.id}") {
            DetailsHeader(
                state = state,
                onPlay = onPlay,
                onChooseStream = onChooseStream,
                onLibraryChanged = onLibraryChanged,
            )
        }
        if (state.isSeries && selectedSeason != null) {
            item(key = "season-selector:${state.id}") {
                SeasonSelector(
                    seasons = state.seasons,
                    selectedSeason = selectedSeason.number,
                    onSeasonSelected = onSeasonSelected,
                )
            }
            item(key = "season-action:${selectedSeason.number}") {
                Row(
                    modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Season ${selectedSeason.number}", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onMarkSeasonWatched(selectedSeason.number) }) {
                        Text(if (selectedSeason.fullyWatched) "Watched" else "Mark season watched")
                    }
                }
            }
            itemsIndexed(selectedSeason.episodes, key = { _, ep -> ep.id }) { index, episode ->
                EpisodeRow(
                    episode = episode,
                    focusRequester = episodeFocusRequesters[index],
                    upNeighbor = episodeFocusRequesters.getOrNull(index - 1),
                    downNeighbor = episodeFocusRequesters.getOrNull(index + 1),
                    onOpen = { onEpisodeSelected(episode) },
                    onWatchedChanged = { watched -> onEpisodeWatchedChanged(episode, watched) },
                )
            }
        }
    }
}

@Composable
private fun DetailsHeader(
    state: RewriteDetailsState,
    onPlay: () -> Unit,
    onChooseStream: () -> Unit,
    onLibraryChanged: (Boolean) -> Unit,
) {
    val playFocusRequester = remember(state.id) { FocusRequester() }
    LaunchedEffect(state.id) { playFocusRequester.requestFocusReliably() }
    Box(modifier = Modifier.fillMaxWidth().height(470.dp).background(SlugYardPalette.Surface)) {
        AsyncImage(
            model = state.backdropUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.48f,
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(
                start = SlugYardTvMetrics.ScreenHorizontalInset,
                end = SlugYardTvMetrics.ScreenHorizontalInset,
                bottom = 38.dp,
            ).fillMaxWidth(0.58f),
        ) {
            Text(state.title, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(8.dp))
            Text(state.metadata, style = MaterialTheme.typography.labelLarge, color = SlugYardPalette.OnCanvasMuted)
            Spacer(Modifier.height(12.dp))
            Text(state.description, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
            if (state.genres.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(state.genres.joinToString("  •  "), style = MaterialTheme.typography.labelMedium)
            }
            if (state.cast.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cast: ${state.cast.take(4).joinToString(", ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SlugYardPalette.OnCanvasMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFocusRequester),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlugYardPalette.OnCanvas,
                        contentColor = SlugYardPalette.Canvas,
                    ),
                ) {
                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onChooseStream) { Text("Choose stream") }
                OutlinedButton(onClick = { onLibraryChanged(!state.inLibrary) }) {
                    Text(if (state.inLibrary) "In My List" else "Add to My List")
                }
            }
        }
        state.posterUrl?.let { posterUrl ->
            AsyncImage(
                model = posterUrl,
                contentDescription = "${state.title} poster",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = SlugYardTvMetrics.ScreenHorizontalInset,
                        bottom = 34.dp,
                    )
                    .width(180.dp)
                    .height(270.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun SeasonSelector(
    seasons: List<RewriteDetailsSeason>,
    selectedSeason: Int,
    onSeasonSelected: (Int) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(seasons, key = RewriteDetailsSeason::number) { season ->
            FilterChip(
                selected = season.number == selectedSeason,
                onClick = { onSeasonSelected(season.number) },
                label = { Text("Season ${season.number}") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: RewriteDetailsEpisode,
    focusRequester: FocusRequester,
    upNeighbor: FocusRequester?,
    downNeighbor: FocusRequester?,
    onOpen: () -> Unit,
    onWatchedChanged: (Boolean) -> Unit,
) {
    // Single focus stop per row: primary click opens the episode, a long-press (hold OK/Center)
    // toggles watched. Previously an inner OutlinedButton was its own separate focus target,
    // which is what made DPAD up/down feel like it "got stuck" mid-list.
    Row(
        modifier = Modifier
            .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusProperties {
                upNeighbor?.let { up = it }
                downNeighbor?.let { down = it }
            }
            .combinedClickable(onClick = onOpen, onLongClick = { onWatchedChanged(!episode.watched) })
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
            Text("${episode.number}. ${episode.title}", style = MaterialTheme.typography.titleMedium)
            episode.releaseLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted) }
        }
        Icon(
            imageVector = if (episode.watched) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (episode.watched) "Watched" else "Not watched",
            tint = if (episode.watched) SlugYardPalette.Accent else SlugYardPalette.OnCanvasMuted,
        )
    }
}
