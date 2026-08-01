package com.sluggyard.tv.ui.rewrite.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsEpisode
import com.sluggyard.tv.ui.rewrite.details.RewriteNetflixEpisodeRow
import com.sluggyard.tv.ui.rewrite.seasonDisplayLabel
import com.sluggyard.tv.ui.rewrite.streams.DigitalReleasePolicy
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamCandidate
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamPresentation

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
    val digitalReleaseStatus: DigitalReleasePolicy.Status? = null,
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
    val closeFocusRequester = remember(state.panel) { FocusRequester() }
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
        if (hasFirstContent) firstFocusRequester.requestRewritePlayerFocus()
        else refreshFocusRequester.requestRewritePlayerFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .62f)),
    ) {
        val isEpisodes = state.panel == RewritePlayerSecondaryPanel.EPISODES
        Column(
            modifier = Modifier
                .align(if (isEpisodes) Alignment.CenterStart else Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(if (isEpisodes) 0.78f else 0.48f)
                .then(if (isEpisodes) Modifier.padding(start = 40.dp, end = 24.dp) else Modifier.width(620.dp))
                .background(
                    if (isEpisodes) Color.Black.copy(alpha = 0.72f) else SlugYardPalette.Canvas,
                )
                .padding(if (isEpisodes) 28.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (state.panel) {
                            RewritePlayerSecondaryPanel.SOURCES -> "Sources"
                            RewritePlayerSecondaryPanel.EPISODES -> seasonDisplayLabel(state.selectedSeason ?: state.seasons.firstOrNull() ?: 1)
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
                    modifier = Modifier.focusRequester(closeFocusRequester),
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
private fun ColumnScope.SourceContent(
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

    val candidates by produceState(
        emptyList<RewriteStreamCandidate>(),
        state.sourceGroups,
        state.selectedSourceAddon,
        state.failedSourceIds,
        state.title,
        state.contentType,
    ) {
        value = withContext(Dispatchers.Default) {
            state.sourceGroups
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
                            digitalReleaseStatus = state.digitalReleaseStatus,
                        ),
                    )
                }
            }
    }
    var firstItemPlaced by remember(state.panel, candidates.firstOrNull()?.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    if (candidates.isNotEmpty()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Keep D-pad Up/Down inside the list so focus at the bottom can walk back up.
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(candidates, key = { index, candidate -> "$index:${candidate.id}" }) { index, candidate ->
                StreamCandidateButton(
                    candidate = candidate,
                    onClick = { actions.onSelectSource(candidate) },
                    focusRequester = if (index == 0) firstFocusRequester else null,
                    onPlaced = if (index == 0) {
                        {
                            if (!firstItemPlaced) {
                                firstItemPlaced = true
                                runCatching { firstFocusRequester.requestFocus() }
                            }
                        }
                    } else null,
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
    Text(
        text = "${state.episodes.size} Episodes",
        style = MaterialTheme.typography.titleMedium,
        color = SlugYardPalette.OnCanvasMuted,
    )
    if (state.episodeSourceId != null && (state.sourceLoading || state.sourceError != null)) {
        Text(
            text = when {
                state.sourceLoading -> "Finding a playable source..."
                else -> state.sourceError ?: "Episode playback could not start"
            },
            color = if (state.sourceError != null) SlugYardPalette.Danger else SlugYardPalette.OnCanvasMuted,
        )
    }
    if (state.episodes.isNotEmpty()) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(state.episodes, key = { index, episode -> "$index:${episode.id}" }) { index, episode ->
                RewriteNetflixEpisodeRow(
                    episode = episode,
                    onClick = { actions.onSelectEpisode(state.selectedSeason ?: 1, episode) },
                    focusRequester = if (index == 0) firstFocusRequester else null,
                )
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
    onPlaced: (() -> Unit)? = null,
) {
    var focused by remember(candidate.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val detailLine = RewriteStreamPresentation.detailLine(candidate)
    val cacheLabel = RewriteStreamPresentation.cacheLabel(candidate.cacheState)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(focusRequester?.let(Modifier::focusRequester) ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface,
                shape,
            )
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            )
            // clickable already installs a focus target — do not add a second .focusable().
            .clickable(onClick = onClick)
            .onGloballyPositioned { onPlaced?.invoke() }
            .semantics {
                contentDescription = listOfNotNull(candidate.title, detailLine, cacheLabel)
                    .joinToString(", ")
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = candidate.title,
            color = if (focused) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (detailLine.isNotBlank()) {
            Text(
                text = detailLine,
                color = SlugYardPalette.OnCanvasMuted,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (cacheLabel != null) {
            Text(
                text = cacheLabel,
                color = when (candidate.cacheState) {
                    StreamCacheState.CACHED -> SlugYardPalette.Accent
                    else -> SlugYardPalette.OnCanvasMuted
                },
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
