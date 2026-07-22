package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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

/** Rewrite-owned ten-foot control layer; playback and source-selection mechanics stay below it. */
@Composable
fun RewritePlayerControls(
    state: PlayerUiState,
    actions: RewritePlayerControlActions,
    modifier: Modifier = Modifier,
) {
    val streamLabel = state.currentStreamName?.cleanPlayerStreamLabel()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        SlugYardPalette.Canvas.copy(alpha = .86f),
                        Color.Transparent,
                        SlugYardPalette.Canvas.copy(alpha = .94f),
                    ),
                ),
            )
            .padding(
                horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                vertical = SlugYardTvMetrics.ScreenVerticalInset,
            ),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PlayerControlButton(label = "Back", onClick = actions.onBack, emphasis = ButtonEmphasis.SECONDARY)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = state.contentName ?: state.title,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 1,
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
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerControlButton(label = "Rewind 10 seconds", onClick = actions.onSeekBackward, emphasis = ButtonEmphasis.SECONDARY)
                PlayerControlButton(label = if (state.isPlaying) "Pause" else "Play", onClick = actions.onPlayPause, emphasis = ButtonEmphasis.PRIMARY)
                PlayerControlButton(label = "Forward 10 seconds", onClick = actions.onSeekForward, emphasis = ButtonEmphasis.SECONDARY)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerControlButton(label = "Sources", onClick = actions.onSources, emphasis = ButtonEmphasis.SUBORDINATE)
                PlayerControlButton(label = "Audio", onClick = actions.onAudio, emphasis = ButtonEmphasis.SUBORDINATE)
                PlayerControlButton(label = "Subtitles", onClick = actions.onSubtitles, emphasis = ButtonEmphasis.SUBORDINATE)
                PlayerControlButton(label = "Speed", onClick = actions.onSpeed, emphasis = ButtonEmphasis.SUBORDINATE)
                if (state.contentType == "series") {
                    PlayerControlButton(label = "Episodes", onClick = actions.onEpisodes, emphasis = ButtonEmphasis.SUBORDINATE)
                }
            }
            streamLabel?.let { stream ->
                Text(
                    text = stream,
                    color = SlugYardPalette.OnCanvasMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

@Composable
private fun PlayerControlButton(label: String, onClick: () -> Unit, emphasis: ButtonEmphasis) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "$label focus")
    val height = if (emphasis == ButtonEmphasis.PRIMARY) 56.dp else 44.dp
    val modifier = Modifier
        .height(height)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics { contentDescription = label; role = Role.Button }
    when (emphasis) {
        ButtonEmphasis.PRIMARY -> Button(
            onClick = onClick,
            modifier = modifier,
            interactionSource = interactions,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlugYardPalette.Accent,
                contentColor = SlugYardPalette.Canvas,
            ),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        ButtonEmphasis.SECONDARY -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            interactionSource = interactions,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SlugYardPalette.OnCanvas),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        ButtonEmphasis.SUBORDINATE -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            interactionSource = interactions,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SlugYardPalette.OnCanvasMuted),
        ) { Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}
