package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackCheckpoint
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackProgressRepository
import com.sluggyard.tv.ui.screens.player.PlayerScreen
import com.sluggyard.tv.ui.screens.player.RewritePlayerControlActions
import com.sluggyard.tv.ui.screens.player.RewritePlayerSystemActions
import com.sluggyard.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.launch

private const val CHECKPOINT_DISTANCE_MS = 15_000L

/**
 * A one-way observer around the owner-retained playback component. It writes small, throttled
 * checkpoints only; it neither configures Media3/mpv nor participates in stream selection.
 */
@Composable
fun RewriteRetainedPlayerHost(
    entry: NavBackStackEntry,
    progressRepository: RewritePlaybackProgressRepository,
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
    var lastSavedPositionMs by remember(contentId) { mutableLongStateOf(0L) }
    val latestTimeline by rememberUpdatedState(timeline)
    val latestTitle by rememberUpdatedState(title)
    val latestPosterUrl by rememberUpdatedState(posterUrl)
    val scope = rememberCoroutineScope()

    LaunchedEffect(contentId, contentType, title, timeline.currentPosition, timeline.duration) {
        if (
            contentId.isNotBlank() &&
            timeline.duration > 0 &&
            timeline.currentPosition > 0 &&
            timeline.currentPosition - lastSavedPositionMs >= CHECKPOINT_DISTANCE_MS
        ) {
            progressRepository.save(
                RewritePlaybackCheckpoint(
                    contentId = contentId,
                    contentType = contentType,
                    title = title.ifBlank { "Untitled" },
                    posterUrl = posterUrl.ifBlank { null },
                    positionMs = timeline.currentPosition,
                    durationMs = timeline.duration,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            lastSavedPositionMs = timeline.currentPosition
        }
    }

    DisposableEffect(contentId, contentType) {
        onDispose {
            val finalTimeline = latestTimeline
            if (contentId.isNotBlank() && finalTimeline.duration > 0 && finalTimeline.currentPosition > 0) {
                scope.launch {
                    progressRepository.save(
                        RewritePlaybackCheckpoint(
                            contentId = contentId,
                            contentType = contentType,
                            title = latestTitle.ifBlank { "Untitled" },
                            posterUrl = latestPosterUrl.ifBlank { null },
                            positionMs = finalTimeline.currentPosition,
                            durationMs = finalTimeline.duration,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    PlayerScreen(
        viewModel = viewModel,
        onBackPress = { _, _, _, _, _ -> onBack() },
        onPlaybackErrorBack = onBack,
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
        secondaryOverlay = { state, actions ->
            RewritePlayerSecondaryOverlay(state = state, actions = actions)
        },
        trackOverlay = { state, actions ->
            RewritePlayerTrackOverlay(state = state, actions = actions)
        },
        skipOverlay = { state, actions ->
            RewritePlayerSkipOverlay(state = state, actions = actions)
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
