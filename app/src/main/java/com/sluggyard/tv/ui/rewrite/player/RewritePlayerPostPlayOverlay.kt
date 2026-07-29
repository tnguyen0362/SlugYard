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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
        val primaryFocusRequester = remember { FocusRequester() }
        val secondaryFocusRequester = remember { FocusRequester() }
        LaunchedEffect(episode.videoId, activeMode is PostPlayMode.StillWatching) {
            primaryFocusRequester.requestRewritePlayerFocus()
        }
        // Landscape banner, bottom-anchored: small enough to keep the closing shot on screen,
        // wide enough to read the upcoming episode from across the room.
        Box(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = SlugYardTvMetrics.ScreenHorizontalInset,
                        bottom = 34.dp,
                    )
                    .background(SlugYardPalette.SurfaceElevated.copy(alpha = .94f), RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                episode.thumbnail?.takeIf { it.isNotBlank() }?.let { thumbnail ->
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .width(148.dp)
                            .height(84.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(SlugYardPalette.Surface),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier = Modifier.widthIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (activeMode is PostPlayMode.StillWatching) "Still watching?" else "Up next",
                        style = MaterialTheme.typography.labelLarge,
                        color = SlugYardPalette.Accent,
                    )
                    Text(
                        "S${episode.season} E${episode.episode}  ${episode.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = SlugYardPalette.OnCanvas,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    postPlayStatus(activeMode)?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = SlugYardPalette.OnCanvasMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (activeMode is PostPlayMode.StillWatching) actions.onContinueStillWatching()
                            else actions.onPlayNext()
                        },
                        modifier = Modifier
                            .focusRequester(primaryFocusRequester)
                            .focusProperties { right = secondaryFocusRequester },
                        enabled = activeMode is PostPlayMode.StillWatching || episode.hasAired,
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlugYardPalette.Accent,
                            contentColor = SlugYardPalette.Canvas,
                        ),
                    ) { Text(if (activeMode is PostPlayMode.StillWatching) "Continue" else "Play next") }
                    if (activeMode is PostPlayMode.StillWatching) {
                        OutlinedButton(
                            onClick = actions.onDismissStillWatching,
                            modifier = Modifier
                                .focusRequester(secondaryFocusRequester)
                                .focusProperties { left = primaryFocusRequester },
                            shape = RoundedCornerShape(4.dp),
                        ) {
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
