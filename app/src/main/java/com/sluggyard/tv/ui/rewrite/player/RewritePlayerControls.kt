package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
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
    val rewindFocusRequester = remember { FocusRequester() }
    val forwardFocusRequester = remember { FocusRequester() }
    val sourcesFocusRequester = remember { FocusRequester() }
    val audioFocusRequester = remember { FocusRequester() }
    val subtitlesFocusRequester = remember { FocusRequester() }
    val speedFocusRequester = remember { FocusRequester() }
    val episodesFocusRequester = remember { FocusRequester() }
    val hasEpisodes = state.contentType == "series"
    val streamLabel = state.currentStreamName?.cleanPlayerStreamLabel()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
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
                downFocusRequester = playPauseFocusRequester,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.contentName ?: state.title,
                    style = MaterialTheme.typography.headlineLarge,
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

        // Bottom chrome: scrubber, then primary transport, then subordinate actions.
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PlayerScrubber(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIconButton(
                        icon = Icons.Filled.Replay10,
                        contentDescription = "Rewind 10 seconds",
                        onClick = actions.onSeekBackward,
                        emphasis = ButtonEmphasis.SECONDARY,
                        focusRequester = rewindFocusRequester,
                        upFocusRequester = backFocusRequester,
                        downFocusRequester = sourcesFocusRequester,
                        rightFocusRequester = playPauseFocusRequester,
                    )
                    PlayerIconButton(
                        icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        onClick = actions.onPlayPause,
                        emphasis = ButtonEmphasis.PRIMARY,
                        focusRequester = playPauseFocusRequester,
                        upFocusRequester = backFocusRequester,
                        downFocusRequester = subtitlesFocusRequester,
                        leftFocusRequester = rewindFocusRequester,
                        rightFocusRequester = forwardFocusRequester,
                    )
                    PlayerIconButton(
                        icon = Icons.Filled.Forward10,
                        contentDescription = "Forward 10 seconds",
                        onClick = actions.onSeekForward,
                        emphasis = ButtonEmphasis.SECONDARY,
                        focusRequester = forwardFocusRequester,
                        upFocusRequester = backFocusRequester,
                        downFocusRequester = speedFocusRequester,
                        leftFocusRequester = playPauseFocusRequester,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp, alignment = Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerLabeledIconButton(
                    icon = Icons.Filled.Layers,
                    label = "Sources",
                    onClick = actions.onSources,
                    focusRequester = sourcesFocusRequester,
                    upFocusRequester = rewindFocusRequester,
                    rightFocusRequester = audioFocusRequester,
                )
                PlayerLabeledIconButton(
                    icon = Icons.Filled.Audiotrack,
                    label = "Audio",
                    onClick = actions.onAudio,
                    focusRequester = audioFocusRequester,
                    upFocusRequester = playPauseFocusRequester,
                    leftFocusRequester = sourcesFocusRequester,
                    rightFocusRequester = subtitlesFocusRequester,
                )
                PlayerLabeledIconButton(
                    icon = Icons.Filled.Subtitles,
                    label = "Subtitles",
                    onClick = actions.onSubtitles,
                    focusRequester = subtitlesFocusRequester,
                    upFocusRequester = playPauseFocusRequester,
                    leftFocusRequester = audioFocusRequester,
                    rightFocusRequester = speedFocusRequester,
                )
                PlayerLabeledIconButton(
                    icon = Icons.Filled.Speed,
                    label = "Speed",
                    onClick = actions.onSpeed,
                    focusRequester = speedFocusRequester,
                    upFocusRequester = forwardFocusRequester,
                    leftFocusRequester = subtitlesFocusRequester,
                    rightFocusRequester = if (hasEpisodes) episodesFocusRequester else null,
                )
                if (hasEpisodes) {
                    PlayerLabeledIconButton(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        label = "Episodes",
                        onClick = actions.onEpisodes,
                        focusRequester = episodesFocusRequester,
                        upFocusRequester = forwardFocusRequester,
                        leftFocusRequester = speedFocusRequester,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerScrubber(
    currentPositionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
) {
    val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedProgress = if (durationMs > 0) {
        (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(progress, animationSpec = tween(150), label = "scrubber progress")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SlugYardPalette.OnCanvasMuted.copy(alpha = .28f), RoundedCornerShape(2.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedProgress)
                    .height(4.dp)
                    .background(SlugYardPalette.OnCanvasMuted.copy(alpha = .5f), RoundedCornerShape(2.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(4.dp)
                    .background(SlugYardPalette.Accent, RoundedCornerShape(2.dp)),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatPlayerTime(currentPositionMs),
                color = SlugYardPalette.OnCanvasMuted,
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
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, animationSpec = tween(180), label = "$contentDescription focus scale")
    val diameter = if (emphasis == ButtonEmphasis.PRIMARY) 64.dp else 48.dp
    val background = when {
        emphasis == ButtonEmphasis.PRIMARY -> SlugYardPalette.Accent
        focused -> SlugYardPalette.SurfaceElevated
        else -> SlugYardPalette.Surface.copy(alpha = .55f)
    }
    val tint = if (emphasis == ButtonEmphasis.PRIMARY) SlugYardPalette.Canvas else SlugYardPalette.OnCanvas

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
            modifier = Modifier.size(if (emphasis == ButtonEmphasis.PRIMARY) 32.dp else 22.dp),
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
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, animationSpec = tween(180), label = "$label focus scale")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            .clip(RoundedCornerShape(20.dp))
            .background(if (focused) SlugYardPalette.SurfaceElevated else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = label; role = Role.Button },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = if (focused) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
