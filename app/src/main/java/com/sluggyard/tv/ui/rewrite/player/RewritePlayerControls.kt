package com.sluggyard.tv.ui.rewrite.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerControlActions
import java.util.concurrent.TimeUnit

/** Rewrite-owned ten-foot control layer; playback and source-selection mechanics stay below it. */
@Composable
fun RewritePlayerControls(
    state: PlayerUiState,
    actions: RewritePlayerControlActions,
    playPauseFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    currentPositionMs: Long = 0L,
    durationMs: Long = 0L,
    bufferedPositionMs: Long = 0L,
) {
    val backFocusRequester = remember { FocusRequester() }
    val scrubberFocusRequester = remember { FocusRequester() }
    val rewindFocusRequester = remember { FocusRequester() }
    val forwardFocusRequester = remember { FocusRequester() }
    val sourcesFocusRequester = remember { FocusRequester() }
    val audioFocusRequester = remember { FocusRequester() }
    val subtitlesFocusRequester = remember { FocusRequester() }
    val speedFocusRequester = remember { FocusRequester() }
    val aspectRatioFocusRequester = remember { FocusRequester() }
    val episodesFocusRequester = remember { FocusRequester() }
    val hasEpisodes = state.contentType?.lowercase() in setOf("series", "tv")
    val streamLabel = state.currentStreamName?.cleanPlayerStreamLabel()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to SlugYardPalette.Canvas.copy(alpha = .78f),
                    0.22f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to SlugYardPalette.Canvas.copy(alpha = .92f),
                ),
            )
            .padding(
                horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                vertical = SlugYardTvMetrics.ScreenVerticalInset,
            ),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top row: back action + title. Minimal chrome, no button plate.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = actions.onBack,
                emphasis = ButtonEmphasis.SECONDARY,
                focusRequester = backFocusRequester,
                downFocusRequester = scrubberFocusRequester,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.contentName ?: state.title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = SlugYardPalette.OnCanvas,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                streamLabel?.let {
                    Text(
                        it,
                        color = SlugYardPalette.OnCanvasMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Bottom chrome: scrubber and one compact, explicitly traversable control rail.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerScrubber(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                previewPositionMs = state.pendingPreviewSeekPosition,
                onSeekPreview = actions.onSeekPreview,
                onSeekCommit = actions.onSeekCommit,
                focusRequester = scrubberFocusRequester,
                upFocusRequester = backFocusRequester,
                 downFocusRequester = rewindFocusRequester,
             )
             Row(
                 modifier = Modifier.fillMaxWidth(),
                 horizontalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.CenterHorizontally),
                 verticalAlignment = Alignment.CenterVertically,
             ) {
                 PlayerLabeledIconButton(
                     icon = Icons.Filled.Replay10,
                     label = "Rewind 10 seconds",
                     onClick = actions.onSeekBackward,
                     focusRequester = rewindFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     rightFocusRequester = playPauseFocusRequester,
                 )
                 PlayerIconButton(
                     icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                     contentDescription = if (state.isPlaying) "Pause" else "Play",
                     onClick = actions.onPlayPause,
                     emphasis = ButtonEmphasis.PRIMARY,
                     focusRequester = playPauseFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     leftFocusRequester = rewindFocusRequester,
                     rightFocusRequester = forwardFocusRequester,
                 )
                 PlayerLabeledIconButton(
                     icon = Icons.Filled.Forward10,
                     label = "Forward 10 seconds",
                     onClick = actions.onSeekForward,
                     focusRequester = forwardFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     leftFocusRequester = playPauseFocusRequester,
                     rightFocusRequester = sourcesFocusRequester,
                 )
                 PlayerLabeledIconButton(
                     icon = Icons.Filled.Layers,
                     label = "Sources",
                     onClick = actions.onSources,
                     focusRequester = sourcesFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     leftFocusRequester = forwardFocusRequester,
                     rightFocusRequester = audioFocusRequester,
                 )
                 PlayerLabeledIconButton(
                    icon = Icons.Filled.Audiotrack,
                     label = "Audio",
                     onClick = actions.onAudio,
                     focusRequester = audioFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     leftFocusRequester = sourcesFocusRequester,
                     rightFocusRequester = subtitlesFocusRequester,
                )
                PlayerLabeledIconButton(
                    icon = Icons.Filled.Subtitles,
                     label = "Subtitles",
                     onClick = actions.onSubtitles,
                     focusRequester = subtitlesFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     leftFocusRequester = audioFocusRequester,
                     rightFocusRequester = speedFocusRequester,
                )
                PlayerLabeledIconButton(
                    icon = Icons.Filled.Speed,
                     label = "Speed",
                     onClick = actions.onSpeed,
                     focusRequester = speedFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     leftFocusRequester = subtitlesFocusRequester,
                     rightFocusRequester = aspectRatioFocusRequester,
                )
                PlayerLabeledIconButton(
                    icon = Icons.Filled.AspectRatio,
                     label = stringResource(state.aspectMode.labelResId),
                     onClick = actions.onToggleAspectRatio,
                     focusRequester = aspectRatioFocusRequester,
                     upFocusRequester = scrubberFocusRequester,
                     leftFocusRequester = speedFocusRequester,
                     rightFocusRequester = if (hasEpisodes) episodesFocusRequester else null,
                )
                if (hasEpisodes) {
                    PlayerLabeledIconButton(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                         label = "Episodes",
                         onClick = actions.onEpisodes,
                         focusRequester = episodesFocusRequester,
                         upFocusRequester = scrubberFocusRequester,
                         leftFocusRequester = aspectRatioFocusRequester,
                     )
                 }
             }
         }
    }
}

/**
 * Focusable, D-pad-scrubbable transport bar. LEFT/RIGHT while focused preview-seeks in
 * accelerating steps (repeat-aware); releasing the key commits the seek. This is the primary
 * fine-grained seek entry point for the rewrite controls -- the Rewind10/Forward10 buttons only
 * cover fixed 10s jumps.
 */
@Composable
private fun PlayerScrubber(
    currentPositionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    previewPositionMs: Long?,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val displayPositionMs = previewPositionMs ?: currentPositionMs
    val progress = if (durationMs > 0) (displayPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedProgress = if (durationMs > 0) {
        (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val isScrubbing = previewPositionMs != null
    val animatedProgress by animateFloatAsState(
        progress,
        animationSpec = if (isScrubbing) tween(0) else tween(150),
        label = "scrubber progress",
    )
    val trackHeight = if (isFocused) 8.dp else 4.dp

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .focusRequester(focusRequester)
                .then(
                    if (upFocusRequester != null || downFocusRequester != null) {
                        Modifier.focusProperties {
                            upFocusRequester?.let { up = it }
                            downFocusRequester?.let { down = it }
                        }
                    } else Modifier
                )
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (isScrubbing) onSeekCommit()
                                true
                            }
                            else -> false
                        }
                    } else if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val repeatCount = native.repeatCount
                                val stepMs = when {
                                    repeatCount >= 8 -> 30_000L
                                    repeatCount >= 3 -> 20_000L
                                    else -> 10_000L
                                }
                                val isLeft = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                                onSeekPreview(if (isLeft) -stepMs else stepMs)
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackWidth = maxWidth
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SlugYardPalette.OnCanvasMuted.copy(alpha = .28f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(bufferedProgress)
                        .fillMaxHeight()
                        .background(SlugYardPalette.OnCanvasMuted.copy(alpha = .5f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .background(if (isScrubbing) SlugYardPalette.OnCanvas else SlugYardPalette.Accent),
                )
            }
            if (isFocused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = trackWidth * animatedProgress - 7.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (isScrubbing) SlugYardPalette.OnCanvas else SlugYardPalette.Accent),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatPlayerTime(displayPositionMs),
                color = if (isScrubbing) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatPlayerTime(durationMs),
                color = SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private enum class ButtonEmphasis { PRIMARY, SECONDARY, SUBORDINATE }

private fun String.cleanPlayerStreamLabel(): String =
    replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(96)
        .ifBlank { "Selected stream" }

private fun formatPlayerTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    emphasis: ButtonEmphasis,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, animationSpec = tween(180), label = "$contentDescription focus scale")
    val diameter = 32.dp
    val background = if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface.copy(alpha = .55f)
    val tint = SlugYardPalette.OnCanvas

    Box(
        modifier = Modifier
            .size(diameter)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (upFocusRequester != null || downFocusRequester != null || leftFocusRequester != null || rightFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                        leftFocusRequester?.let { left = it }
                        rightFocusRequester?.let { right = it }
                    }
                } else Modifier
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(2.dp, if (focused) SlugYardPalette.Accent else Color.Transparent, CircleShape)
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PlayerLabeledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, animationSpec = tween(180), label = "$label focus scale")

    Box(
        modifier = Modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (upFocusRequester != null || downFocusRequester != null || leftFocusRequester != null || rightFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                        leftFocusRequester?.let { left = it }
                        rightFocusRequester?.let { right = it }
                    }
                } else Modifier
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(32.dp)
            .clip(CircleShape)
            .background(if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface.copy(alpha = .55f), CircleShape)
            .border(2.dp, if (focused) SlugYardPalette.Accent else Color.Transparent, CircleShape)
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            // Icon-only by design: with six actions on this row (Sources/Audio/Subtitles/
            // Speed/Aspect ratio/Episodes), visible captions made the row read as cluttered
            // and pushed its total width unpredictably close to the screen edge. The label is
            // kept as the accessibility contentDescription only.
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}
