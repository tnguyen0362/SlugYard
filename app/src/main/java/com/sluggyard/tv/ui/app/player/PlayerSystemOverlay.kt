package com.sluggyard.tv.ui.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PlayerSystemActions

/**
 * Rewrite-owned presentation for retained-player lifecycle state. It intentionally describes
 * source preparation in user terms and never exposes torrent implementation telemetry.
 */
@Composable
fun PlayerSystemOverlay(
    state: PlayerUiState,
    actions: PlayerSystemActions,
    modifier: Modifier = Modifier,
) {
    when {
        state.error != null -> PlayerFailure(state.error, actions.onExitError, modifier)
        state.showLoadingOverlay -> PlayerPreparing(state, modifier)
        // Pausing is a deliberate user action; the transport controls already show the
        // paused state via the play icon, so a blocking "Paused" card is just noise.
        // (state.showPauseOverlay intentionally has no visual here.)
    }
}

@Composable
private fun PlayerPreparing(state: PlayerUiState, modifier: Modifier) {
    val title = state.contentName ?: state.title.takeIf { it.isNotBlank() } ?: "SlugYard"
    val status = state.loadingMessage?.cleanPlayerStatus() ?: "Preparing stream"
    PlayPreparingSurface(
        artUrl = playPreparingArtUrl(state.backdrop, state.poster),
        title = title,
        statusMessage = status,
        modifier = modifier,
        loadingProgress = state.loadingProgress,
    )
}

@Composable
private fun PlayerFailure(message: String, onBack: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas.copy(alpha = .94f)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("This stream could not play", style = MaterialTheme.typography.headlineLarge, color = SlugYardPalette.OnCanvas)
            Text(
                message.cleanPlayerStatus(),
                color = SlugYardPalette.OnCanvasMuted,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

private fun String.cleanPlayerStatus(): String =
    replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
        .ifBlank { "Please try another stream." }
