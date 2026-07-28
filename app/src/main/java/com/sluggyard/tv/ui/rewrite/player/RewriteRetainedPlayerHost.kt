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
import com.sluggyard.tv.core.streamresolution.RewritePlaybackHandoff
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsDataSource
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsEpisode
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsLoadResult
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackCheckpoint
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackProgressRepository
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsDataSource
import com.sluggyard.tv.ui.rewrite.streams.RewriteSubtitleDataSource
import com.sluggyard.tv.ui.rewrite.streams.RewriteSubtitleGroupState
import com.sluggyard.tv.ui.rewrite.streams.selectRewriteAutoPlayCandidate
import com.sluggyard.tv.domain.model.Subtitle
import com.sluggyard.tv.ui.screens.player.PlayerEvent
import com.sluggyard.tv.ui.screens.player.PlayerScreen
import com.sluggyard.tv.ui.screens.player.RewritePlayerControlActions
import com.sluggyard.tv.ui.screens.player.RewritePlayerSystemActions
import com.sluggyard.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val CHECKPOINT_DISTANCE_MS = 15_000L
private const val CHECKPOINT_POLL_INTERVAL_MS = 5_000L

private data class RewriteActivePlaybackContext(
    val contentId: String,
    val contentType: String,
    val title: String,
    val posterUrl: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val addonId: String?,
    val parentId: String?,
    val parentType: String?,
)

/**
 * A one-way observer around the owner-retained playback component. It writes small, throttled
 * checkpoints only; it neither configures Media3/mpv nor participates in stream selection.
 */
@Composable
fun RewriteRetainedPlayerHost(
    entry: NavBackStackEntry,
    initialSource: ResolvedPlaybackSource,
    progressRepository: RewritePlaybackProgressRepository,
    streamsDataSource: RewriteStreamsDataSource,
    subtitleDataSource: RewriteSubtitleDataSource,
    detailsDataSource: RewriteDetailsDataSource,
    manualResolution: ManualStreamResolutionCoordinator,
    configuredDebrid: suspend () -> DebridService?,
    onBack: () -> Unit,
) {
    val viewModel: PlayerViewModel = hiltViewModel()
    viewModel.configureRewriteLaunch(initialSource)
    viewModel.enableRewriteSubtitleMode()
    val timeline by viewModel.playbackTimeline.collectAsState()
    val playerState by viewModel.uiState.collectAsState()
    val initialContentId = entry.arguments?.getString("contentId").orEmpty()
        .ifBlank { entry.arguments?.getString("videoId").orEmpty() }
    val initialContentType = entry.arguments?.getString("contentType").orEmpty().ifBlank { "movie" }
    val initialTitle = entry.arguments?.getString("contentName").orEmpty()
        .ifBlank { entry.arguments?.getString("title").orEmpty() }
        .ifBlank { playerState.title }
    val initialPosterUrl = entry.arguments?.getString("posterUrl").orEmpty().ifBlank { null }
    val initialSeason = entry.arguments?.getString("season")?.toIntOrNull()
    val initialEpisode = entry.arguments?.getString("episode")?.toIntOrNull()
    val initialAddonId = entry.arguments?.getString("addonId").orEmpty().ifBlank { null }
    val initialParentId = entry.arguments?.getString("parentId").orEmpty().ifBlank { null }
    val initialParentType = entry.arguments?.getString("parentType").orEmpty().ifBlank { null }
    var activeContext by remember(initialContentId, initialSeason, initialEpisode) {
        mutableStateOf(
            RewriteActivePlaybackContext(
                initialContentId,
                initialContentType,
                initialTitle,
                initialPosterUrl,
                initialSeason,
                initialEpisode,
                null,
                initialAddonId,
                initialParentId,
                initialParentType,
            ),
        )
    }
    val contentId = activeContext.contentId
    val contentType = activeContext.contentType
    val title = activeContext.title
    val posterUrl = activeContext.posterUrl.orEmpty()
    val season = activeContext.season
    val episode = activeContext.episode
    val addonId = activeContext.addonId
    val parentId = activeContext.parentId
    val parentType = activeContext.parentType
    val latestContext by rememberUpdatedState(activeContext)
    var lastSavedPositionMs by remember(contentId, season, episode) { mutableLongStateOf(0L) }
    val latestTimeline by rememberUpdatedState(timeline)
    val latestTitle by rememberUpdatedState(title)
    val latestPosterUrl by rememberUpdatedState(posterUrl)
    val scope = rememberCoroutineScope()
    var checkpointSavedOnExit by remember { mutableStateOf(false) }
    var secondaryState by remember(contentId, season, episode) {
        mutableStateOf(RewritePlayerSecondaryState(title = title, contentType = contentType))
    }
    var secondaryJob by remember(contentId, season, episode) { mutableStateOf<Job?>(null) }

    LaunchedEffect(contentId, contentType, season, episode) {
        viewModel.setRewriteAddonSubtitles(emptyList())
        subtitleDataSource.subtitleGroups(contentType, contentId).collect { groups ->
            viewModel.setRewriteAddonSubtitles(
                groups.flatMap { group ->
                    (group.state as? RewriteSubtitleGroupState.Content)?.tracks.orEmpty().map { track ->
                        Subtitle(
                            id = "rewrite:${group.addonId}:${track.id}",
                            url = track.url,
                            lang = track.language.orEmpty().ifBlank { "und" },
                            addonName = group.addonName,
                            addonLogo = null,
                            format = track.format,
                        )
                    }
                }.distinctBy(Subtitle::id),
            )
        }
    }

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
            failedSourceIds = if (forceRefresh) emptySet() else secondaryState.failedSourceIds,
        )
        secondaryJob = scope.launch {
            try {
                val groups = streamsDataSource.streamGroups(
                    type = requestType,
                    id = requestId,
                    configuredDebrid = configuredDebrid(),
                )
                groups.collect { next ->
                    secondaryState = secondaryState.copy(
                        sourceGroups = next,
                        sourceLoading = next.isEmpty() || next.all { it.state is RewriteStreamGroupState.Loading },
                    )
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
        secondaryState = secondaryState.copy(
            episodeSourceId = null,
            episodeSourceTitle = null,
            episodeSourceSeason = null,
            episodeSourceEpisode = null,
            episodeSourceEpisodeTitle = null,
        )
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
            episodes = emptyList(),
            episodeLoading = true,
            episodeError = null,
        )
        val addon = addonId
        val parent = parentId
        val type = parentType
        if (addon == null || parent == null || type == null) return
        secondaryJob?.cancel()
        secondaryJob = scope.launch {
            when (val result = detailsDataSource.load(addon, type, parent)) {
                is RewriteDetailsLoadResult.Ready -> secondaryState = secondaryState.copy(
                    episodeLoading = false,
                    episodes = result.state.seasons.firstOrNull { it.number == selectedSeason }?.episodes.orEmpty(),
                )
                is RewriteDetailsLoadResult.Unavailable -> secondaryState = secondaryState.copy(
                    episodeLoading = false,
                    episodeError = result.message,
                )
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
            episodeSourceSeason = selectedSeason,
            episodeSourceEpisode = selectedEpisode.number,
            episodeSourceEpisodeTitle = selectedEpisode.title,
        )
    }

    fun selectRewriteSource(candidate: com.sluggyard.tv.ui.rewrite.streams.RewriteStreamCandidate) {
        secondaryState = secondaryState.copy(sourceLoading = true, sourceError = null)
        scope.launch {
            when (val result = manualResolution.prepare(
                ManualStreamSelection(
                    candidate.id,
                    candidate.directUrl,
                    candidate.infoHash,
                    candidate.fileIndex,
                    secondaryState.episodeSourceSeason ?: season,
                    secondaryState.episodeSourceEpisode ?: episode,
                ),
                configuredDebrid(),
            )) {
                is ManualResolutionResult.Ready -> {
                    val switchingEpisode = secondaryState.episodeSourceId != null
                    val handoff = RewritePlaybackHandoff(
                        source = result.source,
                        contentId = secondaryState.episodeSourceId ?: contentId,
                        contentType = if (switchingEpisode) "series" else contentType,
                        title = title,
                        season = secondaryState.episodeSourceSeason ?: season,
                        episode = secondaryState.episodeSourceEpisode ?: episode,
                        episodeTitle = secondaryState.episodeSourceEpisodeTitle ?: activeContext.episodeTitle,
                        addonId = addonId,
                        parentId = parentId,
                        parentType = parentType,
                        resumePositionMs = if (switchingEpisode) 0L else latestTimeline.currentPosition,
                    )
                    activeContext = activeContext.copy(
                        contentId = handoff.contentId,
                        contentType = handoff.contentType,
                        season = handoff.season,
                        episode = handoff.episode,
                        episodeTitle = handoff.episodeTitle,
                    )
                    viewModel.onEvent(PlayerEvent.OnRewritePlaybackSourceSelected(handoff))
                    secondaryState = secondaryState.copy(
                        panel = RewritePlayerSecondaryPanel.HIDDEN,
                        sourceLoading = false,
                        sourceError = null,
                    )
                }
                is ManualResolutionResult.Unavailable -> secondaryState = secondaryState.copy(
                    sourceLoading = false,
                    sourceError = result.message,
                    failedSourceIds = secondaryState.failedSourceIds + candidate.id,
                )
                is ManualResolutionResult.Failed -> secondaryState = secondaryState.copy(
                    sourceLoading = false,
                    sourceError = result.message,
                    failedSourceIds = secondaryState.failedSourceIds + candidate.id,
                )
            }
        }
    }

    fun playNextRewriteEpisode(): Boolean {
        val next = playerState.nextEpisode ?: return false
        if (addonId == null || parentId == null || parentType == null) return false
        viewModel.onEvent(PlayerEvent.OnDismissNextEpisodeCard)
        scope.launch {
            var groups = emptyList<RewriteStreamGroup>()
            streamsDataSource.streamGroups(
                type = "series",
                id = next.videoId,
                configuredDebrid = configuredDebrid(),
            ).collect { groups = it }
            val candidate = selectRewriteAutoPlayCandidate(
                groups = groups,
                context = com.sluggyard.tv.ui.rewrite.streams.RewriteStreamScoringEngine.Context(
                    title = activeContext.title,
                    contentType = "series",
                ),
            ) ?: run {
                openSources()
                return@launch
            }
            when (val result = manualResolution.prepare(
                ManualStreamSelection(candidate.id, candidate.directUrl, candidate.infoHash, candidate.fileIndex, next.season, next.episode),
                configuredDebrid(),
            )) {
                is ManualResolutionResult.Ready -> {
                    val handoff = RewritePlaybackHandoff(
                        source = result.source,
                        contentId = next.videoId,
                        contentType = "series",
                        title = activeContext.title,
                        season = next.season,
                        episode = next.episode,
                        episodeTitle = next.title,
                        addonId = addonId,
                        parentId = parentId,
                        parentType = parentType,
                    )
                    activeContext = activeContext.copy(
                        contentId = next.videoId,
                        season = next.season,
                        episode = next.episode,
                        episodeTitle = next.title,
                    )
                    viewModel.onEvent(PlayerEvent.OnRewritePlaybackSourceSelected(handoff))
                }
                is ManualResolutionResult.Unavailable,
                is ManualResolutionResult.Failed,
                -> openSources()
            }
        }
        return true
    }

    LaunchedEffect(contentId, contentType, season, episode) {
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
                        title = latestContext.title.ifBlank { latestTitle.ifBlank { "Untitled" } },
                        posterUrl = latestContext.posterUrl ?: latestPosterUrl.ifBlank { null },
                        addonId = latestContext.addonId,
                        parentId = latestContext.parentId,
                        parentType = latestContext.parentType,
                        positionMs = snapshot.currentPosition,
                        durationMs = snapshot.duration,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        season = latestContext.season,
                        episode = latestContext.episode,
                    ),
                )
                lastSavedPositionMs = snapshot.currentPosition
            }
        }
    }

    DisposableEffect(contentId, contentType, season, episode) {
        val contextAtEffectStart = activeContext
        onDispose {
            if (checkpointSavedOnExit) return@onDispose
            val finalTimeline = latestTimeline
            if (contentId.isNotBlank() && finalTimeline.duration > 0) {
                // NonCancellable as the launch context (not a withContext inside the block)
                // detaches this coroutine from rememberCoroutineScope()'s own Job, which is
                // being cancelled as part of this same composition teardown -- without this,
                // the final checkpoint write on back-press/nav-away races its own scope's
                // cancellation and can be silently dropped.
                scope.launch(kotlinx.coroutines.NonCancellable) {
                    if (finalTimeline.currentPosition <= 0L) {
                        progressRepository.remove(
                            contextAtEffectStart.contentId,
                            contextAtEffectStart.season,
                            contextAtEffectStart.episode,
                        )
                    } else {
                        progressRepository.save(
                            RewritePlaybackCheckpoint(
                                contentId = contextAtEffectStart.contentId,
                                contentType = contextAtEffectStart.contentType,
                                title = contextAtEffectStart.title.ifBlank { latestTitle.ifBlank { "Untitled" } },
                                posterUrl = contextAtEffectStart.posterUrl ?: latestPosterUrl.ifBlank { null },
                                addonId = contextAtEffectStart.addonId,
                                parentId = contextAtEffectStart.parentId,
                                parentType = contextAtEffectStart.parentType,
                                positionMs = finalTimeline.currentPosition,
                                durationMs = finalTimeline.duration,
                                updatedAtEpochMs = System.currentTimeMillis(),
                                season = contextAtEffectStart.season,
                                episode = contextAtEffectStart.episode,
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
        onRewriteNextEpisode = ::playNextRewriteEpisode,
        onBeforeExit = { snapshot ->
            checkpointSavedOnExit = true
            if (snapshot.currentPosition > 0L && snapshot.duration > 0L) {
                val contextAtExit = activeContext
                scope.launch(kotlinx.coroutines.NonCancellable) {
                    progressRepository.save(
                        RewritePlaybackCheckpoint(
                            contentId = contextAtExit.contentId,
                            contentType = contextAtExit.contentType,
                            title = contextAtExit.title.ifBlank { "Untitled" },
                            posterUrl = contextAtExit.posterUrl,
                            addonId = contextAtExit.addonId,
                            parentId = contextAtExit.parentId,
                            parentType = contextAtExit.parentType,
                            positionMs = snapshot.currentPosition,
                            durationMs = snapshot.duration,
                            updatedAtEpochMs = System.currentTimeMillis(),
                            season = contextAtExit.season,
                            episode = contextAtExit.episode,
                        ),
                    )
                }
            }
        },
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
