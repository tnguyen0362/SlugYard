package com.sluggyard.tv.ui.app.player

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
import com.sluggyard.tv.ui.components.StreamBadgeChips
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.details.DetailsEpisode
import com.sluggyard.tv.ui.app.details.NetflixEpisodeRow
import com.sluggyard.tv.ui.app.seasonDisplayLabel
import com.sluggyard.tv.ui.app.streams.DigitalReleasePolicy
import com.sluggyard.tv.ui.app.streams.StreamCandidate
import com.sluggyard.tv.ui.app.streams.StreamGroup
import com.sluggyard.tv.ui.app.streams.StreamGroupState
import com.sluggyard.tv.ui.app.streams.StreamPresentation

/** Hard cap before regex-heavy Sources ranking — unlimited dumps ANR'd the Onn. */
private const val MaxPlayerSourcesRanked = 60

enum class PlayerSecondaryPanel {
    HIDDEN,
    SOURCES,
    EPISODES,
    EPISODE_SOURCES,
}

data class PlayerSecondaryState(
    val title: String,
    val contentType: String = "movie",
    val panel: PlayerSecondaryPanel = PlayerSecondaryPanel.HIDDEN,
    val sourceGroups: List<StreamGroup> = emptyList(),
    val sourceLoading: Boolean = false,
    val sourceError: String? = null,
    val selectedSourceAddon: String? = null,
    val failedSourceIds: Set<String> = emptySet(),
    val digitalReleaseStatus: DigitalReleasePolicy.Status? = null,
    val preferredSubtitleLanguage: String? = null,
    val episodeLoading: Boolean = false,
    val episodeError: String? = null,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<DetailsEpisode> = emptyList(),
    val episodeSourceId: String? = null,
    val episodeSourceTitle: String? = null,
    val episodeSourceSeason: Int? = null,
    val episodeSourceEpisode: Int? = null,
    val episodeSourceEpisodeTitle: String? = null,
)

data class PlayerSecondaryActionsV2(
    val onDismiss: () -> Unit,
    val onReloadSources: () -> Unit,
    val onSelectSourceAddon: (String?) -> Unit,
    val onSelectSource: (StreamCandidate) -> Unit,
    val onBackToEpisodes: () -> Unit,
    val onReloadEpisodes: () -> Unit,
    val onSelectSeason: (Int) -> Unit,
    val onSelectEpisode: (Int, DetailsEpisode) -> Unit,
)

@Composable
fun PlayerSecondaryOverlayV2(
    state: PlayerSecondaryState,
    actions: PlayerSecondaryActionsV2,
    modifier: Modifier = Modifier,
) {
    if (state.panel == PlayerSecondaryPanel.HIDDEN) return

    BackHandler { actions.onDismiss() }

    val firstFocusRequester = remember(state.panel) { FocusRequester() }
    val refreshFocusRequester = remember(state.panel) { FocusRequester() }
    val closeFocusRequester = remember(state.panel) { FocusRequester() }
    val hasFirstContent = when (state.panel) {
        PlayerSecondaryPanel.SOURCES,
        PlayerSecondaryPanel.EPISODE_SOURCES -> state.sourceGroups.any {
            (it.state as? StreamGroupState.Content)?.streams?.any { stream ->
                stream.id !in state.failedSourceIds
            } == true
        }
        PlayerSecondaryPanel.EPISODES -> state.episodes.isNotEmpty()
        PlayerSecondaryPanel.HIDDEN -> false
    }
    // Only seed focus when the panel / first content appears — not on every
    // sourceLoading flip or ranked-list refresh (that stole D-pad scroll).
    LaunchedEffect(state.panel, hasFirstContent) {
        if (hasFirstContent) firstFocusRequester.requestPlayerFocus()
        else refreshFocusRequester.requestPlayerFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .62f)),
    ) {
        val isEpisodes = state.panel == PlayerSecondaryPanel.EPISODES
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
                            PlayerSecondaryPanel.SOURCES -> "Sources"
                            PlayerSecondaryPanel.EPISODES -> seasonDisplayLabel(state.selectedSeason ?: state.seasons.firstOrNull() ?: 1)
                            PlayerSecondaryPanel.EPISODE_SOURCES -> "Episode sources"
                            PlayerSecondaryPanel.HIDDEN -> ""
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
                if (state.panel == PlayerSecondaryPanel.EPISODES) {
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
                PlayerSecondaryPanel.SOURCES,
                PlayerSecondaryPanel.EPISODE_SOURCES,
                -> SourceContent(state, actions, firstFocusRequester)

                PlayerSecondaryPanel.EPISODES -> EpisodeContent(state, actions, firstFocusRequester)
                PlayerSecondaryPanel.HIDDEN -> Unit
            }
        }
    }
}

@Composable
private fun ColumnScope.SourceContent(
    state: PlayerSecondaryState,
    actions: PlayerSecondaryActionsV2,
    firstFocusRequester: FocusRequester,
) {
    val addonNames = state.sourceGroups.map(StreamGroup::addonName).distinct()
    if (addonNames.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SourceAddonChip(
                    label = "All",
                    selected = state.selectedSourceAddon == null,
                    onClick = { actions.onSelectSourceAddon(null) },
                )
            }
            items(addonNames, key = { it }) { name ->
                SourceAddonChip(
                    label = name,
                    selected = state.selectedSourceAddon == name,
                    onClick = { actions.onSelectSourceAddon(name) },
                )
            }
        }
    }

    // Keep the last ranked list visible while re-ranking. Identity ignores cache-state churn
    // (probe spam) so we don't re-sort hundreds of rows on every TorBox tick.
    var candidates by remember(state.panel) { mutableStateOf<List<StreamCandidate>>(emptyList()) }
    // Drop the previous All/addon list immediately on chip change so stale rows aren't clickable.
    LaunchedEffect(state.selectedSourceAddon) {
        candidates = emptyList()
    }
    val rankingIdentity = remember(
        state.sourceGroups,
        state.selectedSourceAddon,
        state.failedSourceIds,
    ) {
        state.sourceGroups.joinToString("|") { group ->
            val content = group.state as? StreamGroupState.Content
            val count = content?.streams?.size ?: -1
            val head = content?.streams?.firstOrNull()?.id.orEmpty()
            val tail = content?.streams?.lastOrNull()?.id.orEmpty()
            "${group.addonId}:${group.state::class.simpleName}:$count:$head:$tail"
        } + "#${state.selectedSourceAddon}#${state.failedSourceIds.size}"
    }
    LaunchedEffect(
        rankingIdentity,
        state.title,
        state.contentType,
        state.preferredSubtitleLanguage,
        state.digitalReleaseStatus,
    ) {
        val next = withContext(Dispatchers.Default) {
            val values = state.sourceGroups
                .filter { state.selectedSourceAddon == null || it.addonName == state.selectedSourceAddon }
                .flatMap { group ->
                    (group.state as? StreamGroupState.Content)?.streams.orEmpty()
                }
                .filterNot { it.id in state.failedSourceIds }
            // Cap before full regex ranking — unlimited dumps froze Sources (ANR on main/Default).
            val pool = if (values.size <= MaxPlayerSourcesRanked) {
                values
            } else {
                values
                    .sortedWith(
                        compareByDescending<StreamCandidate> {
                            it.cacheState == com.sluggyard.tv.core.streamresolution.StreamCacheState.CACHED
                        }.thenByDescending { it.seeders ?: 0 },
                    )
                    .take(MaxPlayerSourcesRanked)
            }
            com.sluggyard.tv.ui.app.streams.StreamScoringEngine.rankedCandidates(
                pool,
                com.sluggyard.tv.ui.app.streams.StreamScoringEngine.Context(
                    title = state.title,
                    contentType = state.contentType,
                    preferredSubtitleLanguage = state.preferredSubtitleLanguage,
                    digitalReleaseStatus = state.digitalReleaseStatus,
                ),
            )
        }
        candidates = next
    }
    var seededInitialFocus by remember(state.panel) { mutableStateOf(false) }
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
            items(candidates, key = { it.id }) { candidate ->
                val isFirst = candidate.id == candidates.firstOrNull()?.id
                StreamCandidateButton(
                    candidate = candidate,
                    onClick = { actions.onSelectSource(candidate) },
                    focusRequester = if (isFirst) firstFocusRequester else null,
                    onPlaced = if (isFirst) {
                        {
                            if (!seededInitialFocus) {
                                seededInitialFocus = true
                                runCatching { firstFocusRequester.requestFocus() }
                            }
                        }
                    } else null,
                )
            }
        }
    } else {
        val message = when {
            state.sourceLoading ||
                state.sourceGroups.any { it.state is StreamGroupState.Loading } ->
                "Finding sources..."
            state.sourceError != null -> state.sourceError
            state.sourceGroups.any { it.state is StreamGroupState.Error } ->
                state.sourceGroups.firstNotNullOfOrNull { (it.state as? StreamGroupState.Error)?.message }
            else -> "No sources available"
        }
        Text(message ?: "No sources available", color = if (state.sourceError != null) SlugYardPalette.Danger else SlugYardPalette.OnCanvasMuted)
    }
}

@Composable
private fun EpisodeContent(
    state: PlayerSecondaryState,
    actions: PlayerSecondaryActionsV2,
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
                NetflixEpisodeRow(
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
    candidate: StreamCandidate,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onPlaced: (() -> Unit)? = null,
) {
    var focused by remember(candidate.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val detailLine = StreamPresentation.detailLine(candidate)
    val cacheLabel = StreamPresentation.cacheLabel(candidate.cacheState)
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
        if (candidate.badges.isNotEmpty() || candidate.videoSizeBytes != null) {
            StreamBadgeChips(
                badges = candidate.badges,
                fileSizeBytes = candidate.videoSizeBytes,
                showFileSizeBadge = candidate.videoSizeBytes != null,
                focused = focused,
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

@Composable
private fun SourceAddonChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember(label) { mutableStateOf(false) }
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    val bg = if (selected) SoftYardAccentFill else SlugYardPalette.SurfaceElevated
    val fg = when {
        selected -> Color.Black
        focused -> SlugYardPalette.OnCanvas
        else -> SoftYardChipLabel
    }
    val border = when {
        focused -> SlugYardPalette.FocusRing
        selected -> SlugYardPalette.Accent
        else -> SoftYardChipBorder
    }
    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) SlugYardTvMetrics.FocusRingWidth else 2.dp, border, shape)
            .background(bg, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private val SoftYardAccentFill = Color(0xFFF2C94C)
private val SoftYardChipLabel = Color(0xFFF5F5F1)
private val SoftYardChipBorder = Color(0xFF6B6B6B)
