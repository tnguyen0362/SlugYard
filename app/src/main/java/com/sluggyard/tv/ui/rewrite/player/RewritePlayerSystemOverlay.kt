package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerSystemActions

/**
 * Rewrite-owned presentation for retained-player lifecycle state. It intentionally describes
 * source preparation in user terms and never exposes torrent implementation telemetry.
 */
@Composable
fun RewritePlayerSystemOverlay(
    state: PlayerUiState,
    actions: RewritePlayerSystemActions,
    modifier: Modifier = Modifier,
) {
    when {
        state.error != null -> PlayerFailure(state.error, actions.onExitError, modifier)
        state.showLoadingOverlay -> PlayerPreparing(state, modifier)
        state.showPauseOverlay -> PlayerPaused(state, actions.onDismissPause, modifier)
    }
}

@Composable
private fun PlayerPreparing(state: PlayerUiState, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(SlugYardPalette.Canvas.copy(alpha = .42f), SlugYardPalette.Canvas.copy(alpha = .94f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.contentName ?: state.title ?: "PlayFlix", style = MaterialTheme.typography.headlineLarge)
            Text(
                state.loadingMessage?.cleanPlayerStatus() ?: "Preparing stream",
                style = MaterialTheme.typography.bodyLarge,
                color = SlugYardPalette.OnCanvasMuted,
            )
            state.loadingProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.38f),
                    color = SlugYardPalette.Accent,
                    trackColor = SlugYardPalette.SurfaceElevated,
                )
            }
        }
    }
}

@Composable
private fun PlayerPaused(state: PlayerUiState, onResume: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(SlugYardPalette.Surface.copy(alpha = .96f), RoundedCornerShape(8.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Paused", style = MaterialTheme.typography.headlineLarge)
            Text(state.contentName ?: state.title ?: "", color = SlugYardPalette.OnCanvasMuted)
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlugYardPalette.OnCanvas,
                    contentColor = SlugYardPalette.Canvas,
                ),
            ) { Text("Resume") }
        }
    }
}

@Composable
private fun PlayerFailure(message: String, onBack: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas.copy(alpha = .94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("This stream could not play", style = MaterialTheme.typography.headlineLarge)
            Text(
                message.cleanPlayerStatus(),
                color = SlugYardPalette.OnCanvasMuted,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onBack) { Text("Back to streams") }
        }
    }
}

private fun String.cleanPlayerStatus(): String =
    replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
        .ifBlank { "Please try another stream." }
