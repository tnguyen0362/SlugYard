package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PostPlayMode
import com.sluggyard.tv.ui.screens.player.RewritePlayerPostPlayActions

/** Rewrite-owned TV card for autoplay and still-watching decisions. */
@Composable
fun RewritePlayerPostPlayOverlay(
    state: PlayerUiState,
    actions: RewritePlayerPostPlayActions,
    modifier: Modifier = Modifier,
) {
    val mode = state.postPlayMode
    AnimatedVisibility(visible = mode != null, enter = fadeIn(), exit = fadeOut()) {
        val activeMode = mode ?: return@AnimatedVisibility
        val episode = activeMode.nextEpisode
        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = SlugYardTvMetrics.ScreenHorizontalInset)
                    .background(SlugYardPalette.SurfaceElevated, RoundedCornerShape(8.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (activeMode is PostPlayMode.StillWatching) "Still watching?" else "Up next",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "S${episode.season} E${episode.episode}  ${episode.title}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SlugYardPalette.OnCanvasMuted,
                )
                postPlayStatus(activeMode)?.let { status ->
                    Text(status, style = MaterialTheme.typography.labelLarge, color = SlugYardPalette.OnCanvasMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (activeMode is PostPlayMode.StillWatching) actions.onContinueStillWatching()
                            else actions.onPlayNext()
                        },
                        enabled = activeMode is PostPlayMode.StillWatching || episode.hasAired,
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlugYardPalette.Accent,
                            contentColor = SlugYardPalette.Canvas,
                        ),
                    ) { Text(if (activeMode is PostPlayMode.StillWatching) "Continue" else "Play next") }
                    if (activeMode is PostPlayMode.StillWatching) {
                        OutlinedButton(onClick = actions.onDismissStillWatching, shape = RoundedCornerShape(4.dp)) {
                            Text("Not now")
                        }
                    }
                }
            }
        }
    }
}

private fun postPlayStatus(mode: PostPlayMode): String? = when (mode) {
    is PostPlayMode.AutoPlay -> when {
        !mode.nextEpisode.hasAired -> mode.nextEpisode.unairedMessage ?: "Not yet available"
        mode.searching -> "Finding a playable source..."
        mode.countdownSec != null -> "Starting in ${mode.countdownSec}s"
        else -> null
    }
    is PostPlayMode.StillWatching -> mode.countdownSec?.let { "Continuing in ${it}s" }
}
