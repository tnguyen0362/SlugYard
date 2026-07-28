package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.streamresolution.ManualResolutionResult
import com.sluggyard.tv.core.streamresolution.ManualStreamResolutionCoordinator
import com.sluggyard.tv.core.streamresolution.ManualStreamSelection
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsDataSource
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsEpisode
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsLoadResult
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackCheckpoint
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackProgressRepository
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsDataSource
import com.sluggyard.tv.ui.screens.player.PlayerEvent
import com.sluggyard.tv.ui.screens.player.PlayerScreen
import com.sluggyard.tv.ui.screens.player.RewritePlayerControlActions
import com.sluggyard.tv.ui.screens.player.RewritePlayerSystemActions
import com.sluggyard.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val CHECKPOINT_DISTANCE_MS = 15_000L
private const val CHECKPOINT_POLL_INTERVAL_MS = 1_000L

/**
 * A one-way observer around the owner-retained playback component. It writes small, throttled
 * checkpoints only; it neither configures Media3/mpv nor participates in stream selection.
 */
@Composable
fun RewriteRetainedPlayerHost(
    entry: NavBackStackEntry,
    progressRepository: RewritePlaybackProgressRepository,
    streamsDataSource: RewriteStreamsDataSource,
    detailsDataSource: RewriteDetailsDataSource,
    manualResolution: ManualStreamResolutionCoordinator,
    configuredDebrid: suspend () -> DebridService?,
    onBack: () -> Unit,
) {
    val viewModel: PlayerViewModel = hiltViewModel()
    val timeline by viewModel.playbackTimeline.collectAsState()
    val playerState by viewModel.uiState.collectAsState()
    val contentId = entry.arguments?.getString("contentId").orEmpty()
        .ifBlank { entry.arguments?.getString("videoId").orEmpty() }
    val contentType = entry.arguments?.getString("contentType").orEmpty().ifBlank { "movie" }
    val title = entry.arguments?.getString("contentName").orEmpty()
        .ifBlank { entry.arguments?.getString("title").orEmpty() }
        .ifBlank { playerState.title }
    val posterUrl = entry.arguments?.getString("posterUrl").orEmpty()
    val season = entry.arguments?.getString("season")?.toIntOrNull()
    val episode = entry.arguments?.getString("episode")?.toIntOrNull()
    val addonId = entry.arguments?.getString("addonId").orEmpty().ifBlank { null }
    val parentId = entry.arguments?.getString("parentId").orEmpty().ifBlank { null }
    val parentType = entry.arguments?.getString("parentType").orEmpty().ifBlank { null }
    var lastSavedPositionMs by remember(contentId) { mutableLongStateOf(0L) }
    val latestTimeline by rememberUpdatedState(timeline)
    val latestTitle by rememberUpdatedState(title)
    val latestPosterUrl by rememberUpdatedState(posterUrl)
    val latestSeason by rememberUpdatedState(season)
    val latestEpisode by rememberUpdatedState(episode)
    val scope = rememberCoroutineScope()
    var secondaryState by remember(contentId, season, episode) {
        mutableStateOf(RewritePlayerSecondaryState(title = title))
    }
    var secondaryJob by remember(contentId, season, episode) { mutableStateOf<Job?>(null) }

    fun loadPlayerSources(
        requestType: String,
        requestId: String,
        panel: RewritePlayerSecondaryPanel,
        forceRefresh: Boolean,
    ) {
        secondaryJob?.cancel()
        secondaryState = secondaryState.copy(
            panel = panel,
            sourceGroups = if (forceRefresh) emptyList() else secondaryState.sourceGroups,
            sourceLoading = true,
            sourceError = null,
            selectedSourceAddon = if (forceRefresh) null else secondaryState.selectedSourceAddon,
        )
        secondaryJob = scope.launch {
            try {
                val groups = streamsDataSource.streamGroups(
                    type = requestType,
                    id = requestId,
                    configuredDebrid = configuredDebrid(),
                )
                groups.collect { next ->
                    secondaryState = secondaryState.copy(sourceGroups = next, sourceLoading = true)
                }
                secondaryState = secondaryState.copy(sourceLoading = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                secondaryState = secondaryState.copy(
                    sourceLoading = false,
                    sourceError = failure.message ?: "Sources could not be loaded",
                )
            }
        }
    }

    fun openSources() {
        loadPlayerSources(contentType, contentId, RewritePlayerSecondaryPanel.SOURCES, forceRefresh = false)
    }

    fun openEpisodes() {
        secondaryJob?.cancel()
        secondaryState = secondaryState.copy(
            panel = RewritePlayerSecondaryPanel.EPISODES,
            episodeLoading = true,
            episodeError = null,
        )
        secondaryJob = scope.launch {
            val addon = addonId
            val parent = parentId
            val type = parentType
            if (addon == null || parent == null || type == null) {
                secondaryState = secondaryState.copy(
                    episodeLoading = false,
                    episodeError = "Episodes are unavailable for this playback item",
                )
                return@launch
            }
            when (val result = detailsDataSource.load(addon, type, parent)) {
                is RewriteDetailsLoadResult.Ready -> {
                    val details = result.state
                    val seasons = details.seasons.map { it.number }
                    val selected = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
                    secondaryState = secondaryState.copy(
                        episodeLoading = false,
                        seasons = seasons,
                        selectedSeason = selected,
                        episodes = details.seasons.firstOrNull { it.number == selected }?.episodes.orEmpty(),
                        episodeError = null,
                    )
                }
                is RewriteDetailsLoadResult.Unavailable -> secondaryState = secondaryState.copy(
                    episodeLoading = false,
                    episodeError = result.message,
                )
            }
        }
    }

    fun selectEpisodeSeason(selectedSeason: Int) {
        secondaryState = secondaryState.copy(
            selectedSeason = selectedSeason,
            episodes = secondaryState.episodes,
        )
        val addon = addonId
        val parent = parentId
        val type = parentType
        if (addon == null || parent == null || type == null) return
        secondaryJob?.cancel()
        secondaryJob = scope.launch {
            when (val result = detailsDataSource.load(addon, type, parent)) {
                is RewriteDetailsLoadResult.Ready -> secondaryState = secondaryState.copy(
                    episodes = result.state.seasons.firstOrNull { it.number == selectedSeason }?.episodes.orEmpty(),
                )
                is RewriteDetailsLoadResult.Unavailable -> secondaryState = secondaryState.copy(episodeError = result.message)
            }
        }
    }

    fun selectEpisode(selectedSeason: Int, selectedEpisode: RewriteDetailsEpisode) {
        loadPlayerSources(
            requestType = "series",
            requestId = selectedEpisode.id,
            panel = RewritePlayerSecondaryPanel.EPISODE_SOURCES,
            forceRefresh = true,
        )
        secondaryState = secondaryState.copy(
            episodeSourceId = selectedEpisode.id,
            episodeSourceTitle = "S${selectedSeason}E${selectedEpisode.number} · ${selectedEpisode.title}",
        )
    }

    fun selectRewriteSource(candidate: com.sluggyard.tv.ui.rewrite.streams.RewriteStreamCandidate) {
        secondaryState = secondaryState.copy(sourceLoading = true, sourceError = null)
        scope.launch {
            when (val result = manualResolution.prepare(
                ManualStreamSelection(candidate.id, candidate.directUrl, candidate.infoHash, candidate.fileIndex),
                configuredDebrid(),
            )) {
                is ManualResolutionResult.Ready -> {
                    viewModel.onEvent(PlayerEvent.OnRewritePlaybackSourceSelected(result.source))
                    secondaryState = secondaryState.copy(
                        panel = RewritePlayerSecondaryPanel.HIDDEN,
                        sourceLoading = false,
                        sourceError = null,
                    )
                }
                is ManualResolutionResult.Unavailable -> secondaryState = secondaryState.copy(
                    sourceLoading = false,
                    sourceError = result.message,
                )
                is ManualResolutionResult.Failed -> secondaryState = secondaryState.copy(
                    sourceLoading = false,
                    sourceError = result.message,
                )
            }
        }
    }

    LaunchedEffect(contentId, contentType) {
        while (true) {
            kotlinx.coroutines.delay(CHECKPOINT_POLL_INTERVAL_MS)
            val snapshot = latestTimeline
            if (
                contentId.isNotBlank() &&
                snapshot.duration > 0 &&
                snapshot.currentPosition > 0 &&
                snapshot.currentPosition - lastSavedPositionMs >= CHECKPOINT_DISTANCE_MS
            ) {
                progressRepository.save(
                    RewritePlaybackCheckpoint(
                        contentId = contentId,
                        contentType = contentType,
                        title = latestTitle.ifBlank { "Untitled" },
                        posterUrl = latestPosterUrl.ifBlank { null },
                        positionMs = snapshot.currentPosition,
                        durationMs = snapshot.duration,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        season = latestSeason,
                        episode = latestEpisode,
                    ),
                )
                lastSavedPositionMs = snapshot.currentPosition
            }
        }
    }

    DisposableEffect(contentId, contentType) {
        onDispose {
            val finalTimeline = latestTimeline
            if (contentId.isNotBlank() && finalTimeline.duration > 0) {
                // NonCancellable as the launch context (not a withContext inside the block)
                // detaches this coroutine from rememberCoroutineScope()'s own Job, which is
                // being cancelled as part of this same composition teardown -- without this,
                // the final checkpoint write on back-press/nav-away races its own scope's
                // cancellation and can be silently dropped.
                scope.launch(kotlinx.coroutines.NonCancellable) {
                    if (finalTimeline.currentPosition <= 0L) {
                        progressRepository.remove(contentId, latestSeason, latestEpisode)
                    } else {
                        progressRepository.save(
                            RewritePlaybackCheckpoint(
                                contentId = contentId,
                                contentType = contentType,
                                title = latestTitle.ifBlank { "Untitled" },
                                posterUrl = latestPosterUrl.ifBlank { null },
                                positionMs = finalTimeline.currentPosition,
                                durationMs = finalTimeline.duration,
                                updatedAtEpochMs = System.currentTimeMillis(),
                                season = latestSeason,
                                episode = latestEpisode,
                            ),
                        )
                    }
                }
            }
        }
    }

    PlayerScreen(
        viewModel = viewModel,
        onBackPress = { _, _, _, _, _ -> onBack() },
        onPlaybackErrorBack = onBack,
        legacyChromeEnabled = false,
        onOpenSourcesPanel = ::openSources,
        onOpenEpisodesPanel = ::openEpisodes,
        externalSecondaryOpen = { secondaryState.panel != RewritePlayerSecondaryPanel.HIDDEN },
        onDismissExternalSecondary = {
            secondaryJob?.cancel()
            secondaryState = secondaryState.copy(panel = RewritePlayerSecondaryPanel.HIDDEN)
        },
        controlsOverlay = { state, actions, playPauseFocusRequester, controlsTimeline ->
            RewritePlayerControls(
                state = state,
                actions = actions,
                playPauseFocusRequester = playPauseFocusRequester,
                currentPositionMs = controlsTimeline.currentPosition,
                durationMs = controlsTimeline.duration,
                bufferedPositionMs = controlsTimeline.bufferedPosition,
            )
        },
        secondaryOverlay = { _, _ ->
            RewritePlayerSecondaryOverlayV2(
                state = secondaryState,
                actions = RewritePlayerSecondaryActionsV2(
                    onDismiss = {
                        secondaryJob?.cancel()
                        secondaryState = secondaryState.copy(panel = RewritePlayerSecondaryPanel.HIDDEN)
                    },
                    onReloadSources = {
                        val episodePanel = secondaryState.panel == RewritePlayerSecondaryPanel.EPISODE_SOURCES
                        val requestId = if (episodePanel) secondaryState.episodeSourceId ?: contentId else contentId
                        loadPlayerSources(
                            requestType = if (episodePanel) "series" else contentType,
                            requestId = requestId,
                            panel = secondaryState.panel,
                            forceRefresh = true,
                        )
                    },
                    onSelectSourceAddon = { addon -> secondaryState = secondaryState.copy(selectedSourceAddon = addon) },
                    onSelectSource = ::selectRewriteSource,
                    onBackToEpisodes = {
                        secondaryJob?.cancel()
                        openEpisodes()
                    },
                    onReloadEpisodes = ::openEpisodes,
                    onSelectSeason = ::selectEpisodeSeason,
                    onSelectEpisode = ::selectEpisode,
                ),
            )
        },
        trackOverlay = { state, actions ->
            RewritePlayerTrackOverlay(state = state, actions = actions)
        },
        skipOverlay = { state, actions, focusRequester ->
            RewritePlayerSkipOverlay(
                state = state,
                actions = actions,
                focusRequester = focusRequester,
            )
        },
        postPlayOverlay = { state, actions ->
            RewritePlayerPostPlayOverlay(state = state, actions = actions)
        },
        diagnosticsOverlay = { state, actions ->
            RewritePlayerDiagnosticsOverlay(state = state, actions = actions)
        },
        subtitleTimingOverlay = { state, positionMs, actions ->
            RewritePlayerSubtitleTimingOverlay(
                state = state,
                currentPositionMs = positionMs,
                actions = actions,
            )
        },
        systemOverlay = { state, actions ->
            RewritePlayerSystemOverlay(state = state, actions = actions)
        },
        playbackPositionMs = timeline.currentPosition,
    )
}
