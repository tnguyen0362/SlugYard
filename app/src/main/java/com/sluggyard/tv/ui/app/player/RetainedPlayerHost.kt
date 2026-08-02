package com.sluggyard.tv.ui.app.player

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.streamresolution.ManualResolutionResult
import com.sluggyard.tv.core.streamresolution.ManualStreamResolutionCoordinator
import com.sluggyard.tv.core.streamresolution.ManualStreamSelection
import com.sluggyard.tv.ui.app.streams.toManualSelection
import com.sluggyard.tv.core.streamresolution.PlaybackHandoff
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.ui.app.details.DetailsDataSource
import com.sluggyard.tv.ui.app.details.DetailsEpisode
import com.sluggyard.tv.ui.app.details.DetailsLoadResult
import com.sluggyard.tv.ui.app.data.PlaybackCheckpoint
import com.sluggyard.tv.ui.app.data.PlaybackProgressRepository
import com.sluggyard.tv.core.streams.StreamBadgeRules
import com.sluggyard.tv.ui.app.episodeLabel
import com.sluggyard.tv.ui.app.streams.StreamBadgeApplicator
import com.sluggyard.tv.ui.app.streams.StreamGroup
import com.sluggyard.tv.ui.app.streams.StreamGroupState
import com.sluggyard.tv.ui.app.streams.StreamsDataSource
import com.sluggyard.tv.ui.app.streams.SubtitleDataSource
import com.sluggyard.tv.ui.app.streams.SubtitleGroupState
import com.sluggyard.tv.ui.app.streams.DigitalReleaseLookup
import com.sluggyard.tv.ui.app.streams.selectAutoPlayCandidate
import com.sluggyard.tv.domain.model.Subtitle
import com.sluggyard.tv.ui.screens.player.PlayerEvent
import com.sluggyard.tv.ui.screens.player.PlayerScreen
import com.sluggyard.tv.ui.screens.player.PlayerControlActions
import com.sluggyard.tv.ui.screens.player.PlayerSystemActions
import com.sluggyard.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// First periodic checkpoint lands at ~10s so a short sample still reaches Continue Watching,
// matching WatchStatePolicy.STARTED_MIN_POSITION_MS.
private const val CHECKPOINT_DISTANCE_MS = 10_000L
private const val CHECKPOINT_POLL_INTERVAL_MS = 5_000L
// Hard cap for next-episode stream discovery, matching the legacy player path's
// NEXT_EPISODE_SEARCH_HARD_CAP_MS contract. A hung provider must not leave the
// next-episode coroutine waiting indefinitely.
private const val NEXT_EPISODE_SEARCH_HARD_CAP_MS = 120_000L
private const val REWRITE_SUBTITLE_DIAGNOSTIC_TAG = "SubtitleDiag"

private data class ActivePlaybackContext(
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
fun RetainedPlayerHost(
    entry: NavBackStackEntry,
    initialSource: ResolvedPlaybackSource,
    progressRepository: PlaybackProgressRepository,
    streamsDataSource: StreamsDataSource,
    subtitleDataSource: SubtitleDataSource,
    detailsDataSource: DetailsDataSource,
    manualResolution: ManualStreamResolutionCoordinator,
    configuredDebrid: suspend () -> DebridService?,
    digitalReleaseLookup: DigitalReleaseLookup? = null,
    streamBadgeRules: StreamBadgeRules = StreamBadgeRules(),
    onBack: () -> Unit,
) {
    val viewModel: PlayerViewModel = hiltViewModel()
    // Must configure before PlayerScreen's startInitialPlayback LaunchedEffect runs — doing this
    // only inside LaunchedEffect races and can start with an empty stream URL.
    viewModel.configureLaunch(initialSource)
    viewModel.enableSubtitleMode()
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
            ActivePlaybackContext(
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
    val latestStreamBadgeRules by rememberUpdatedState(streamBadgeRules)
    var lastSavedPositionMs by remember(contentId, season, episode) { mutableLongStateOf(0L) }
    val latestTimeline by rememberUpdatedState(timeline)
    val latestTitle by rememberUpdatedState(title)
    val latestPosterUrl by rememberUpdatedState(posterUrl)
    val scope = rememberCoroutineScope()
    var checkpointSavedOnExit by remember { mutableStateOf(false) }
    var secondaryState by remember(contentId, season, episode) {
        mutableStateOf(PlayerSecondaryState(title = title, contentType = contentType))
    }
    var secondaryJob by remember(contentId, season, episode) { mutableStateOf<Job?>(null) }
    var episodeHandoffPreparing by remember { mutableStateOf(false) }
    var episodeHandoffTitle by remember { mutableStateOf("") }
    var episodeHandoffArtUrl by remember { mutableStateOf<String?>(null) }

    // Hand off prepare chrome to PlayerSystemOverlay once the player owns loading.
    LaunchedEffect(episodeHandoffPreparing, playerState.showLoadingOverlay, playerState.error) {
        if (!episodeHandoffPreparing) return@LaunchedEffect
        if (playerState.error != null || playerState.showLoadingOverlay) {
            episodeHandoffPreparing = false
        }
    }

    // Re-apply when the default pack finishes installing after sources already loaded.
    LaunchedEffect(streamBadgeRules) {
        val groups = secondaryState.sourceGroups
        if (groups.isEmpty()) return@LaunchedEffect
        secondaryState = secondaryState.copy(
            sourceGroups = StreamBadgeApplicator.apply(groups, streamBadgeRules),
        )
    }

    LaunchedEffect(contentId, contentType, season, episode) {
        viewModel.setAddonSubtitles(emptyList())
        subtitleDataSource.subtitleGroups(contentType, contentId).collect { groups ->
            val tracks = groups.flatMap { group ->
                    (group.state as? SubtitleGroupState.Content)?.tracks.orEmpty().map { track ->
                        Subtitle(
                            id = "rewrite:${group.addonId}:${track.id}",
                            url = track.url,
                            lang = track.language.orEmpty().ifBlank { "und" },
                            addonName = group.addonName,
                            addonLogo = null,
                            format = track.format,
                        )
                    }
                }.distinctBy(Subtitle::id)
            Log.d(
                REWRITE_SUBTITLE_DIAGNOSTIC_TAG,
                "content=$contentId season=$season episode=$episode groups=${groups.size} " +
                    "tracks=${tracks.joinToString(" | ") { "${it.id}:${it.lang}:${it.format ?: "unknown"}" }}",
            )
            viewModel.setAddonSubtitles(tracks)
        }
    }

    fun loadPlayerSources(
        requestType: String,
        requestId: String,
        panel: PlayerSecondaryPanel,
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
            preferredSubtitleLanguage = playerState.subtitleStyle.preferredLanguage,
        )
        secondaryJob = scope.launch {
            try {
                val digitalReleaseStatus = digitalReleaseLookup?.movieStatus(
                    contentId = requestId,
                    contentType = requestType,
                )
                val groups = streamsDataSource.streamGroups(
                    type = requestType,
                    id = requestId,
                    configuredDebrid = configuredDebrid(),
                )
                groups.collectLatest { next ->
                    // Badge regex packs must not run on main — that ANR'd Sources on Onn (~179% CPU).
                    val applied = withContext(Dispatchers.Default) {
                        StreamBadgeApplicator.apply(next, latestStreamBadgeRules)
                    }
                    secondaryState = secondaryState.copy(
                        sourceGroups = applied,
                        sourceLoading = applied.isEmpty() || applied.all { it.state is StreamGroupState.Loading },
                        digitalReleaseStatus = digitalReleaseStatus ?: secondaryState.digitalReleaseStatus,
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
        val episodeName = activeContext.episodeTitle
            ?: playerState.currentEpisodeTitle?.takeIf { it.isNotBlank() }
        val seasonEpisodeLabel = episodeLabel(season, episode)
        val episodeSourceTitle = when {
            seasonEpisodeLabel != null && !episodeName.isNullOrBlank() ->
                "$seasonEpisodeLabel · $episodeName"
            seasonEpisodeLabel != null -> seasonEpisodeLabel
            !episodeName.isNullOrBlank() -> episodeName
            else -> null
        }
        secondaryState = secondaryState.copy(
            episodeSourceId = null,
            episodeSourceTitle = episodeSourceTitle,
            episodeSourceSeason = season,
            episodeSourceEpisode = episode,
            episodeSourceEpisodeTitle = episodeName,
        )
        loadPlayerSources(contentType, contentId, PlayerSecondaryPanel.SOURCES, forceRefresh = false)
    }

    fun openEpisodes() {
        secondaryJob?.cancel()
        secondaryState = secondaryState.copy(
            panel = PlayerSecondaryPanel.EPISODES,
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
                is DetailsLoadResult.Ready -> {
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
                is DetailsLoadResult.Unavailable -> secondaryState = secondaryState.copy(
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
                is DetailsLoadResult.Ready -> secondaryState = secondaryState.copy(
                    episodeLoading = false,
                    episodes = result.state.seasons.firstOrNull { it.number == selectedSeason }?.episodes.orEmpty(),
                )
                is DetailsLoadResult.Unavailable -> secondaryState = secondaryState.copy(
                    episodeLoading = false,
                    episodeError = result.message,
                )
            }
        }
    }

    lateinit var selectSource: (
        com.sluggyard.tv.ui.app.streams.StreamCandidate,
        Boolean,
    ) -> Unit

    fun selectEpisode(selectedSeason: Int, selectedEpisode: DetailsEpisode) {
        // Same full-bleed prepare chrome as outside-player auto-pick (BuildingPlayerScreen).
        secondaryJob?.cancel()
        episodeHandoffPreparing = true
        episodeHandoffTitle = "S${selectedSeason}E${selectedEpisode.number} · ${selectedEpisode.title}"
        episodeHandoffArtUrl = playPreparingArtUrl(
            selectedEpisode.thumbnailUrl,
            activeContext.posterUrl ?: latestPosterUrl.ifBlank { null },
        )
        secondaryState = secondaryState.copy(
            panel = PlayerSecondaryPanel.HIDDEN,
            sourceGroups = emptyList(),
            sourceLoading = true,
            sourceError = null,
            failedSourceIds = emptySet(),
            episodeSourceId = selectedEpisode.id,
            episodeSourceTitle = episodeHandoffTitle,
            episodeSourceSeason = selectedSeason,
            episodeSourceEpisode = selectedEpisode.number,
            episodeSourceEpisodeTitle = selectedEpisode.title,
        )
        secondaryJob = scope.launch {
            try {
                var groups = emptyList<StreamGroup>()
                val settled = withTimeoutOrNull(NEXT_EPISODE_SEARCH_HARD_CAP_MS) {
                    streamsDataSource.streamGroups(
                        type = "series",
                        id = selectedEpisode.id,
                        configuredDebrid = configuredDebrid(),
                    ).collect { groups = it }
                }
                if (settled == null) {
                    episodeHandoffPreparing = false
                    secondaryState = secondaryState.copy(
                        panel = PlayerSecondaryPanel.EPISODES,
                        sourceLoading = false,
                        sourceError = "Episode sources took too long to respond. Try again.",
                    )
                    return@launch
                }
                val candidate = selectAutoPlayCandidate(
                    groups = groups,
                    context = com.sluggyard.tv.ui.app.streams.StreamScoringEngine.Context(
                        title = activeContext.title,
                        contentType = "series",
                        genres = viewModel.contentGenres()
                            ?.split(',', '|', '/')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty(),
                        language = viewModel.contentLanguage(),
                        preferredSubtitleLanguage = playerState.subtitleStyle.preferredLanguage,
                    ),
                )
                if (candidate == null) {
                    episodeHandoffPreparing = false
                    secondaryState = secondaryState.copy(
                        panel = PlayerSecondaryPanel.EPISODES,
                        sourceLoading = false,
                        sourceError = "No cached playable source was found for this episode.",
                    )
                    return@launch
                }
                selectSource(candidate, true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                episodeHandoffPreparing = false
                throw cancelled
            } catch (failure: Exception) {
                episodeHandoffPreparing = false
                secondaryState = secondaryState.copy(
                    panel = PlayerSecondaryPanel.EPISODES,
                    sourceLoading = false,
                    sourceError = failure.message ?: "Episode playback could not start",
                )
            }
        }
    }

    selectSource = { candidate, forceDebridForTorrent ->
        secondaryState = secondaryState.copy(sourceLoading = true, sourceError = null)
        scope.launch {
            when (val result = manualResolution.prepare(
                candidate.toManualSelection(
                    season = secondaryState.episodeSourceSeason ?: season,
                    episode = secondaryState.episodeSourceEpisode ?: episode,
                    forceDebridForTorrent = forceDebridForTorrent,
                ),
                configuredDebrid(),
            )) {
                is ManualResolutionResult.Ready -> {
                    val switchingEpisode = secondaryState.episodeSourceId != null
                    val handoff = PlaybackHandoff(
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
                    // Reset the checkpoint throttle baseline so the new source can save
                    // checkpoints from its own start position. Without this, a same-episode
                    // source switch inherits the old source's lastSavedPositionMs, and the
                    // distance-gate blocks all checkpoint writes for the new source until
                    // the user scrubs past the old baseline.
                    lastSavedPositionMs = 0L
                    // Fire handoff first so PlayerSystemOverlay can show Building player
                    // on the same art. Clearing preparing before that flashed the old
                    // Lottie/black loading gap between Finding stream and Building player.
                    viewModel.onEvent(PlayerEvent.OnPlaybackSourceSelected(handoff))
                    secondaryState = secondaryState.copy(
                        panel = PlayerSecondaryPanel.HIDDEN,
                        sourceLoading = false,
                        sourceError = null,
                    )
                }
                is ManualResolutionResult.Unavailable -> {
                    val restoreEpisodes = episodeHandoffPreparing
                    episodeHandoffPreparing = false
                    secondaryState = secondaryState.copy(
                        panel = if (restoreEpisodes) PlayerSecondaryPanel.EPISODES else secondaryState.panel,
                        sourceLoading = false,
                        sourceError = result.message,
                        failedSourceIds = secondaryState.failedSourceIds + candidate.id,
                    )
                }
                is ManualResolutionResult.Failed -> {
                    val restoreEpisodes = episodeHandoffPreparing
                    episodeHandoffPreparing = false
                    secondaryState = secondaryState.copy(
                        panel = if (restoreEpisodes) PlayerSecondaryPanel.EPISODES else secondaryState.panel,
                        sourceLoading = false,
                        sourceError = result.message,
                        failedSourceIds = secondaryState.failedSourceIds + candidate.id,
                    )
                }
            }
        }
    }

    fun playNextEpisode(): Boolean {
        val next = playerState.nextEpisode ?: return false
        if (addonId == null || parentId == null || parentType == null) return false
        // Keep preparing chrome up while we search — previously we only dismissed the
        // Up-next card, so Play next looked dead and left a blank gap until handoff.
        episodeHandoffPreparing = true
        episodeHandoffTitle = "S${next.season}E${next.episode} · ${next.title}"
        episodeHandoffArtUrl = playPreparingArtUrl(
            next.thumbnail,
            activeContext.posterUrl ?: latestPosterUrl.ifBlank { null },
        )
        viewModel.onEvent(PlayerEvent.OnDismissNextEpisodeCard)
        scope.launch {
            try {
                var groups = emptyList<StreamGroup>()
                val settled = withTimeoutOrNull(NEXT_EPISODE_SEARCH_HARD_CAP_MS) {
                    streamsDataSource.streamGroups(
                        type = "series",
                        id = next.videoId,
                        configuredDebrid = configuredDebrid(),
                    ).collect { groups = it }
                }
                if (settled == null) {
                    episodeHandoffPreparing = false
                    openSources()
                    return@launch
                }
                val candidate = selectAutoPlayCandidate(
                    groups = groups,
                    context = com.sluggyard.tv.ui.app.streams.StreamScoringEngine.Context(
                        title = activeContext.title,
                        contentType = "series",
                        genres = viewModel.contentGenres()
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty(),
                        language = viewModel.contentLanguage(),
                        preferredSubtitleLanguage = playerState.subtitleStyle.preferredLanguage,
                    ),
                ) ?: run {
                    episodeHandoffPreparing = false
                    openSources()
                    return@launch
                }
                when (val result = manualResolution.prepare(
                    candidate.toManualSelection(
                        season = next.season,
                        episode = next.episode,
                        forceDebridForTorrent = !candidate.infoHash.isNullOrBlank(),
                    ),
                    configuredDebrid(),
                )) {
                    is ManualResolutionResult.Ready -> {
                        val handoff = PlaybackHandoff(
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
                        // Keep preparing until the player loading overlay owns the frame.
                        viewModel.onEvent(PlayerEvent.OnPlaybackSourceSelected(handoff))
                    }
                    is ManualResolutionResult.Unavailable,
                    is ManualResolutionResult.Failed,
                    -> {
                        episodeHandoffPreparing = false
                        openSources()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                episodeHandoffPreparing = false
                throw cancelled
            } catch (_: Exception) {
                episodeHandoffPreparing = false
                openSources()
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
                    PlaybackCheckpoint(
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
                        contentGenres = viewModel.contentGenres(),
                        contentLanguage = viewModel.contentLanguage(),
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
                    // Never remove on dispose when position is 0 — that races teardown/timeline
                    // reset and drops Continue Watching right after Back. Only persist forward.
                    if (finalTimeline.currentPosition > 0L) {
                        progressRepository.save(
                            PlaybackCheckpoint(
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
                                contentGenres = viewModel.contentGenres(),
                                contentLanguage = viewModel.contentLanguage(),
                            ),
                        )
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    PlayerScreen(
        viewModel = viewModel,
        onBackPress = { _, _, _, _, _ -> onBack() },
        onPlaybackErrorBack = onBack,
        onNextEpisode = ::playNextEpisode,
        onBeforeExit = { snapshot ->
            val exitDurationMs = if (snapshot.duration > 0L) snapshot.duration else latestTimeline.duration
            if (snapshot.currentPosition > 0L && exitDurationMs > 0L) {
                checkpointSavedOnExit = true
                val contextAtExit = activeContext
                scope.launch(kotlinx.coroutines.NonCancellable) {
                    progressRepository.save(
                        PlaybackCheckpoint(
                            contentId = contextAtExit.contentId,
                            contentType = contextAtExit.contentType,
                            title = contextAtExit.title.ifBlank { "Untitled" },
                            posterUrl = contextAtExit.posterUrl,
                            addonId = contextAtExit.addonId,
                            parentId = contextAtExit.parentId,
                            parentType = contextAtExit.parentType,
                            positionMs = snapshot.currentPosition,
                            durationMs = exitDurationMs,
                            updatedAtEpochMs = System.currentTimeMillis(),
                            season = contextAtExit.season,
                            episode = contextAtExit.episode,
                            contentGenres = viewModel.contentGenres(),
                            contentLanguage = viewModel.contentLanguage(),
                        ),
                    )
                }
            }
        },
        legacyChromeEnabled = false,
        onOpenSourcesPanel = ::openSources,
        onOpenEpisodesPanel = ::openEpisodes,
        externalSecondaryOpen = { secondaryState.panel != PlayerSecondaryPanel.HIDDEN },
        onDismissExternalSecondary = {
            secondaryJob?.cancel()
            secondaryState = secondaryState.copy(panel = PlayerSecondaryPanel.HIDDEN)
        },
        controlsOverlay = { state, actions, playPauseFocusRequester, controlsTimeline ->
            PlayerControls(
                state = state,
                actions = actions,
                playPauseFocusRequester = playPauseFocusRequester,
                currentPositionMs = controlsTimeline.currentPosition,
                durationMs = controlsTimeline.duration,
                bufferedPositionMs = controlsTimeline.bufferedPosition,
            )
        },
        secondaryOverlay = { _, _ ->
            PlayerSecondaryOverlayV2(
                state = secondaryState,
                actions = PlayerSecondaryActionsV2(
                    onDismiss = {
                        secondaryJob?.cancel()
                        secondaryState = secondaryState.copy(panel = PlayerSecondaryPanel.HIDDEN)
                    },
                    onReloadSources = {
                        val episodePanel = secondaryState.panel == PlayerSecondaryPanel.EPISODE_SOURCES
                        val requestId = if (episodePanel) secondaryState.episodeSourceId ?: contentId else contentId
                        loadPlayerSources(
                            requestType = if (episodePanel) "series" else contentType,
                            requestId = requestId,
                            panel = secondaryState.panel,
                            forceRefresh = true,
                        )
                    },
                    onSelectSourceAddon = { addon -> secondaryState = secondaryState.copy(selectedSourceAddon = addon) },
                    onSelectSource = { candidate -> selectSource(candidate, false) },
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
            PlayerTrackOverlay(state = state, actions = actions)
        },
        skipOverlay = { state, actions, focusRequester ->
            PlayerSkipOverlay(
                state = state,
                actions = actions,
                focusRequester = focusRequester,
            )
        },
        postPlayOverlay = { state, actions ->
            PlayerPostPlayOverlay(state = state, actions = actions)
        },
        diagnosticsOverlay = { state, actions ->
            PlayerDiagnosticsOverlay(state = state, actions = actions)
        },
        subtitleTimingOverlay = { state, positionMs, actions ->
            PlayerSubtitleTimingOverlay(
                state = state,
                currentPositionMs = positionMs,
                actions = actions,
            )
        },
        systemOverlay = { state, actions ->
            PlayerSystemOverlay(state = state, actions = actions)
        },
        playbackPositionMs = timeline.currentPosition,
    )
        if (episodeHandoffPreparing) {
            BackHandler {
                secondaryJob?.cancel()
                episodeHandoffPreparing = false
                openEpisodes()
            }
            val backFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                runCatching { backFocus.requestFocus() }
            }
            PlayPreparingSurface(
                artUrl = episodeHandoffArtUrl,
                contentId = secondaryState.episodeSourceId ?: contentId,
                title = episodeHandoffTitle.ifBlank { title },
                statusMessage = "Finding a playable stream...",
                showChooser = false,
                onChooseSource = {},
                onBack = {
                    secondaryJob?.cancel()
                    episodeHandoffPreparing = false
                    openEpisodes()
                },
                backFocusRequester = backFocus,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(20f),
            )
        }
    }
}
