package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.domain.model.Video
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerSecondaryActions
import com.sluggyard.tv.ui.rewrite.seasonDisplayLabel

/** Rewrite-owned sources and episode sheets; they dispatch intents but never select playback files. */
@Composable
fun RewritePlayerSecondaryOverlay(
    state: PlayerUiState,
    actions: RewritePlayerSecondaryActions,
    modifier: Modifier = Modifier,
) {
    val firstEpisodeFocusRequester = remember { FocusRequester() }
    val firstSourceFocusRequester = remember { FocusRequester() }

    LaunchedEffect(
        state.showEpisodesPanel,
        state.showEpisodeStreams,
        state.episodes.firstOrNull()?.id,
        state.episodeFilteredStreams.firstOrNull()?.stableKey(),
        state.showSourcesPanel,
        state.sourceFilteredStreams.firstOrNull()?.stableKey(),
    ) {
        when {
            state.showEpisodesPanel && !state.showEpisodeStreams && state.episodes.isNotEmpty() -> {
                firstEpisodeFocusRequester.requestRewritePlayerFocus()
            }
            state.showEpisodesPanel && state.showEpisodeStreams && state.episodeFilteredStreams.isNotEmpty() -> {
                firstSourceFocusRequester.requestRewritePlayerFocus()
            }
            state.showSourcesPanel && state.sourceFilteredStreams.isNotEmpty() -> {
                firstSourceFocusRequester.requestRewritePlayerFocus()
            }
        }
    }

    when {
        state.showSourcesPanel -> RewritePlayerSheet(
            title = "Sources",
            subtitle = state.currentStreamName ?: state.title,
            onClose = actions.onDismissSources,
            onReload = actions.onReloadSources,
            initialFocusRequester = state.sourceFilteredStreams.takeIf { it.isNotEmpty() }?.let { firstSourceFocusRequester },
            modifier = modifier,
        ) {
            SourceFilters(
                names = state.sourceAvailableAddons,
                selected = state.sourceSelectedAddonFilter,
                onSelected = actions.onSelectSourceAddon,
            )
            when {
                state.isLoadingSourceStreams -> SheetStatus("Finding sources…")
                state.sourceStreamsError != null -> SheetStatus(state.sourceStreamsError, SlugYardPalette.Danger)
                state.sourceFilteredStreams.isEmpty() -> SheetStatus("No sources available")
                else -> StreamRows(
                    streams = state.sourceFilteredStreams,
                    onSelected = actions.onSelectSource,
                    firstFocusRequester = firstSourceFocusRequester,
                )
            }
        }
        state.showEpisodesPanel -> RewritePlayerSheet(
            title = if (state.showEpisodeStreams) "Episode sources" else "Episodes",
            subtitle = state.contentName ?: state.title,
            onClose = actions.onDismissEpisodes,
            onReload = if (state.showEpisodeStreams) actions.onReloadEpisodeStreams else null,
            onBack = if (state.showEpisodeStreams) actions.onBackToEpisodes else null,
            initialFocusRequester = when {
                state.showEpisodeStreams && state.episodeFilteredStreams.isNotEmpty() -> firstSourceFocusRequester
                !state.showEpisodeStreams && state.episodes.isNotEmpty() -> firstEpisodeFocusRequester
                else -> null
            },
            modifier = modifier,
        ) {
            if (state.showEpisodeStreams) {
                SourceFilters(
                    names = state.episodeAvailableAddons,
                    selected = state.episodeSelectedAddonFilter,
                    onSelected = actions.onSelectEpisodeAddon,
                )
                when {
                    state.isLoadingEpisodeStreams -> SheetStatus("Finding episode sources…")
                    state.episodeStreamsError != null -> SheetStatus(state.episodeStreamsError, SlugYardPalette.Danger)
                    state.episodeFilteredStreams.isEmpty() -> SheetStatus("No sources available")
                    else -> StreamRows(
                        streams = state.episodeFilteredStreams,
                        onSelected = actions.onSelectEpisodeStream,
                        firstFocusRequester = firstSourceFocusRequester,
                    )
                }
            } else {
                SeasonFilters(
                    seasons = state.episodesAvailableSeasons,
                    selected = state.episodesSelectedSeason,
                    onSelected = actions.onSelectEpisodeSeason,
                )
                when {
                    state.isLoadingEpisodes -> SheetStatus("Loading episodes…")
                    state.episodesError != null -> SheetStatus(state.episodesError, SlugYardPalette.Danger)
                    state.episodes.isEmpty() -> SheetStatus("No episodes available")
                    else -> EpisodeRows(
                        episodes = state.episodes,
                        onSelected = actions.onSelectEpisode,
                        firstFocusRequester = firstEpisodeFocusRequester,
                    )
                }
            }
        }
    }
}

@Composable
private fun RewritePlayerSheet(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onReload: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
    initialFocusRequester: FocusRequester? = null,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(title, initialFocusRequester) {
        (initialFocusRequester ?: closeFocusRequester).requestRewritePlayerFocus()
    }
    Box(modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f))) {
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(620.dp)
                .background(SlugYardPalette.Canvas).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = SlugYardPalette.OnCanvas)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                onBack?.let { OutlinedButton(onClick = it) { Text("Back") } }
                onReload?.let { OutlinedButton(onClick = it) { Text("Refresh") } }
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.focusRequester(closeFocusRequester),
                ) { Text("Close") }
            }
            content()
        }
    }
}

@Composable
private fun SourceFilters(names: List<String>, selected: String?, onSelected: (String?) -> Unit) {
    if (names.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelected(null) }, label = { Text("All") }) }
        items(names, key = { it }) { name ->
            FilterChip(selected = name == selected, onClick = { onSelected(name) }, label = { Text(name) })
        }
    }
}

@Composable
private fun SeasonFilters(seasons: List<Int>, selected: Int?, onSelected: (Int) -> Unit) {
    if (seasons.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(seasons, key = { it }) { season ->
            FilterChip(selected = season == selected, onClick = { onSelected(season) }, label = { Text(seasonDisplayLabel(season)) })
        }
    }
}

@Composable
private fun StreamRows(
    streams: List<Stream>,
    onSelected: (Stream) -> Unit,
    firstFocusRequester: FocusRequester,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(streams, key = { index, stream -> "$index:${stream.stableKey()}" }) { index, stream ->
            var focused by remember(stream.stableKey()) { mutableStateOf(false) }
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .background(if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
                    .semantics(mergeDescendants = true) {
                        contentDescription = stream.getDisplayName()
                        role = Role.Button
                    }
                    .clickable { onSelected(stream) }.heightIn(min = 56.dp).padding(16.dp),
            ) {
                Text(stream.getDisplayName(), style = MaterialTheme.typography.titleMedium, color = SlugYardPalette.OnCanvas)
                Text(stream.addonName, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted)
                stream.quality?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted) }
            }
        }
    }
}

@Composable
private fun EpisodeRows(
    episodes: List<Video>,
    onSelected: (Video) -> Unit,
    firstFocusRequester: FocusRequester,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(episodes, key = { index, episode -> "$index:${episode.id}" }) { index, episode ->
            var focused by remember(episode.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .background(if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
                    .semantics(mergeDescendants = true) {
                        contentDescription = episode.title
                        role = Role.Button
                    }
                    .clickable { onSelected(episode) }.heightIn(min = 56.dp).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${episode.episode ?: ""}".trim(), style = MaterialTheme.typography.labelLarge, color = SlugYardPalette.OnCanvasMuted)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleMedium, color = SlugYardPalette.OnCanvas)
                    episode.released?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted) }
                }
            }
        }
    }
}

@Composable
private fun SheetStatus(message: String?, color: Color = SlugYardPalette.OnCanvasMuted) {
    Text(
        text = message ?: "Unavailable",
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SlugYardPalette.Surface).padding(18.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
    )
}
