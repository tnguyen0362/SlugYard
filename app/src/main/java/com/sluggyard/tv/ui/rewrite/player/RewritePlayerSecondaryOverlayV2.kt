package com.sluggyard.tv.ui.rewrite.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsEpisode
import com.sluggyard.tv.ui.rewrite.seasonDisplayLabel
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamCandidate
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState

enum class RewritePlayerSecondaryPanel {
    HIDDEN,
    SOURCES,
    EPISODES,
    EPISODE_SOURCES,
}

data class RewritePlayerSecondaryState(
    val title: String,
    val contentType: String = "movie",
    val panel: RewritePlayerSecondaryPanel = RewritePlayerSecondaryPanel.HIDDEN,
    val sourceGroups: List<RewriteStreamGroup> = emptyList(),
    val sourceLoading: Boolean = false,
    val sourceError: String? = null,
    val selectedSourceAddon: String? = null,
    val failedSourceIds: Set<String> = emptySet(),
    val episodeLoading: Boolean = false,
    val episodeError: String? = null,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<RewriteDetailsEpisode> = emptyList(),
    val episodeSourceId: String? = null,
    val episodeSourceTitle: String? = null,
    val episodeSourceSeason: Int? = null,
    val episodeSourceEpisode: Int? = null,
    val episodeSourceEpisodeTitle: String? = null,
)

data class RewritePlayerSecondaryActionsV2(
    val onDismiss: () -> Unit,
    val onReloadSources: () -> Unit,
    val onSelectSourceAddon: (String?) -> Unit,
    val onSelectSource: (RewriteStreamCandidate) -> Unit,
    val onBackToEpisodes: () -> Unit,
    val onReloadEpisodes: () -> Unit,
    val onSelectSeason: (Int) -> Unit,
    val onSelectEpisode: (Int, RewriteDetailsEpisode) -> Unit,
)

@Composable
fun RewritePlayerSecondaryOverlayV2(
    state: RewritePlayerSecondaryState,
    actions: RewritePlayerSecondaryActionsV2,
    modifier: Modifier = Modifier,
) {
    if (state.panel == RewritePlayerSecondaryPanel.HIDDEN) return

    BackHandler { actions.onDismiss() }

    val firstFocusRequester = remember(state.panel) { FocusRequester() }
    val refreshFocusRequester = remember(state.panel) { FocusRequester() }
    val hasFirstContent = when (state.panel) {
        RewritePlayerSecondaryPanel.SOURCES,
        RewritePlayerSecondaryPanel.EPISODE_SOURCES -> state.sourceGroups.any {
            (it.state as? RewriteStreamGroupState.Content)?.streams?.any { stream ->
                stream.id !in state.failedSourceIds
            } == true
        }
        RewritePlayerSecondaryPanel.EPISODES -> state.episodes.isNotEmpty()
        RewritePlayerSecondaryPanel.HIDDEN -> false
    }
    LaunchedEffect(state.panel, hasFirstContent, state.sourceLoading, state.episodeLoading) {
        runCatching {
            if (hasFirstContent) firstFocusRequester.requestFocus()
            else refreshFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .62f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(620.dp)
                .background(SlugYardPalette.Canvas)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (state.panel) {
                            RewritePlayerSecondaryPanel.SOURCES -> "Sources"
                            RewritePlayerSecondaryPanel.EPISODES -> "Episodes"
                            RewritePlayerSecondaryPanel.EPISODE_SOURCES -> "Episode sources"
                            RewritePlayerSecondaryPanel.HIDDEN -> ""
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = SlugYardPalette.OnCanvas,
                    )
                    Text(
                        text = state.episodeSourceTitle ?: state.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlugYardPalette.OnCanvasMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.panel == RewritePlayerSecondaryPanel.EPISODE_SOURCES) {
                    OutlinedButton(onClick = actions.onBackToEpisodes) { Text("Back") }
                }
                if (state.panel == RewritePlayerSecondaryPanel.EPISODES) {
                    OutlinedButton(
                        onClick = actions.onReloadEpisodes,
                        modifier = Modifier.focusRequester(refreshFocusRequester),
                    ) { Text("Refresh") }
                } else {
                    OutlinedButton(
                        onClick = actions.onReloadSources,
                        modifier = Modifier.focusRequester(refreshFocusRequester),
                    ) { Text("Refresh") }
                }
                OutlinedButton(
                    onClick = actions.onDismiss,
                    modifier = Modifier.focusRequester(refreshFocusRequester),
                ) { Text("Close") }
            }

            when (state.panel) {
                RewritePlayerSecondaryPanel.SOURCES,
                RewritePlayerSecondaryPanel.EPISODE_SOURCES,
                -> SourceContent(state, actions, firstFocusRequester)

                RewritePlayerSecondaryPanel.EPISODES -> EpisodeContent(state, actions, firstFocusRequester)
                RewritePlayerSecondaryPanel.HIDDEN -> Unit
            }
        }
    }
}

@Composable
private fun SourceContent(
    state: RewritePlayerSecondaryState,
    actions: RewritePlayerSecondaryActionsV2,
    firstFocusRequester: FocusRequester,
) {
    val addonNames = state.sourceGroups.map(RewriteStreamGroup::addonName).distinct()
    if (addonNames.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.selectedSourceAddon == null,
                    onClick = { actions.onSelectSourceAddon(null) },
                    label = { Text("All") },
                )
            }
            items(addonNames, key = { it }) { name ->
                FilterChip(
                    selected = state.selectedSourceAddon == name,
                    onClick = { actions.onSelectSourceAddon(name) },
                    label = { Text(name) },
                )
            }
        }
    }

    val candidates = state.sourceGroups
        .filter { state.selectedSourceAddon == null || it.addonName == state.selectedSourceAddon }
        .flatMap { group ->
            (group.state as? RewriteStreamGroupState.Content)?.streams.orEmpty()
        }
        .filterNot { it.id in state.failedSourceIds }
        .let { values ->
            com.sluggyard.tv.ui.rewrite.streams.RewriteStreamScoringEngine.rankedCandidates(
                values,
                com.sluggyard.tv.ui.rewrite.streams.RewriteStreamScoringEngine.Context(
                    title = state.title,
                    contentType = state.contentType,
                ),
            )
        }
    if (candidates.isNotEmpty()) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(candidates, key = { index, candidate -> "$index:${candidate.id}" }) { index, candidate ->
                StreamCandidateButton(
                    candidate = candidate,
                    onClick = { actions.onSelectSource(candidate) },
                    focusRequester = if (index == 0) firstFocusRequester else null,
                )
            }
        }
    } else {
        val message = when {
            state.sourceLoading -> "Finding sources..."
            state.sourceError != null -> state.sourceError
            state.sourceGroups.any { it.state is RewriteStreamGroupState.Error } ->
                state.sourceGroups.firstNotNullOfOrNull { (it.state as? RewriteStreamGroupState.Error)?.message }
            else -> "No sources available"
        }
        Text(message ?: "No sources available", color = if (state.sourceError != null) SlugYardPalette.Danger else SlugYardPalette.OnCanvasMuted)
    }
}

@Composable
private fun EpisodeContent(
    state: RewritePlayerSecondaryState,
    actions: RewritePlayerSecondaryActionsV2,
    firstFocusRequester: FocusRequester,
) {
    if (state.seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.seasons, key = { it }) { season ->
                FilterChip(
                    selected = state.selectedSeason == season,
                    onClick = { actions.onSelectSeason(season) },
                    label = { Text(seasonDisplayLabel(season)) },
                )
            }
        }
    }
    if (state.episodes.isNotEmpty()) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.episodes, key = { index, episode -> "$index:${episode.id}" }) { index, episode ->
                var focused by remember(episode.id) { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { actions.onSelectEpisode(state.selectedSeason ?: 1, episode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                        .onFocusChanged { focused = it.isFocused },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${episode.displayNumber}. ${episode.title}",
                            color = if (focused) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        episode.releaseLabel?.let { Text(it, color = SlugYardPalette.OnCanvasMuted) }
                    }
                }
            }
        }
    } else {
        Text(
            text = when {
                state.episodeLoading -> "Loading episodes..."
                state.episodeError != null -> state.episodeError
                else -> "No episodes available"
            },
            color = if (state.episodeError != null) SlugYardPalette.Danger else SlugYardPalette.OnCanvasMuted,
        )
    }
}

@Composable
private fun StreamCandidateButton(
    candidate: RewriteStreamCandidate,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember(candidate.id) { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(focusRequester?.let(Modifier::focusRequester) ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .semantics {
                contentDescription = candidate.title
                role = Role.Button
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = candidate.title,
                color = if (focused) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(candidate.sourceLabel, candidate.detailLabel).joinToString(" · "),
                color = SlugYardPalette.OnCanvasMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
