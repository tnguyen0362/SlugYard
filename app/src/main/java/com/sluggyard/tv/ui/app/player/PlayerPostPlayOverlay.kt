package com.sluggyard.tv.ui.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.requestFocusReliably
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PostPlayMode
import com.sluggyard.tv.ui.screens.player.PlayerPostPlayActions

/**
 * Rewrite-owned TV card for autoplay and still-watching decisions.
 *
 * Vertical stack (thumb + title above actions) so Play next / Not now stay selectable on TV
 * without fighting a wide side-by-side chrome strip. Owns D-pad while visible.
 */
@Composable
fun PlayerPostPlayOverlay(
    state: PlayerUiState,
    actions: PlayerPostPlayActions,
    modifier: Modifier = Modifier,
) {
    val mode = state.postPlayMode
    AnimatedVisibility(visible = mode != null, enter = fadeIn(), exit = fadeOut()) {
        val activeMode = mode ?: return@AnimatedVisibility
        val episode = activeMode.nextEpisode
        val isStillWatching = activeMode is PostPlayMode.StillWatching
        val primaryFocusRequester = remember { FocusRequester() }
        val dismissFocusRequester = remember { FocusRequester() }
        val playEnabled = isStillWatching || episode.hasAired

        // Seed Play next / Continue once when the card appears. Do not reclaim on chrome flips —
        // that stole OK from transport and fought L/R between the two actions (same pattern as Skip).
        LaunchedEffect(episode.videoId, isStillWatching) {
            if (playEnabled) {
                primaryFocusRequester.requestFocusReliably(retries = 10)
            } else {
                dismissFocusRequester.requestFocusReliably(retries = 10)
            }
        }

        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = SlugYardTvMetrics.ScreenHorizontalInset,
                        bottom = 34.dp,
                    )
                    .widthIn(min = 280.dp, max = 360.dp)
                    .background(SlugYardPalette.SurfaceElevated.copy(alpha = .94f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                episode.thumbnail?.takeIf { it.isNotBlank() }?.let { thumbnail ->
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(SlugYardPalette.Surface),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (isStillWatching) "Still watching?" else "Up next",
                        style = MaterialTheme.typography.labelLarge,
                        color = SlugYardPalette.Accent,
                    )
                    Text(
                        "S${episode.season} E${episode.episode}  ${episode.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = SlugYardPalette.OnCanvas,
                        maxLines = 2,
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
                // Stack actions under the title (vertical card). Left/Right still move between them.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PostPlayActionButton(
                        label = if (isStillWatching) "Continue" else "Play next",
                        primary = true,
                        enabled = playEnabled,
                        onClick = {
                            if (isStillWatching) actions.onContinueStillWatching()
                            else actions.onPlayNext()
                        },
                        modifier = Modifier
                            .focusRequester(primaryFocusRequester)
                            .focusProperties {
                                right = dismissFocusRequester
                                left = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                    )
                    PostPlayActionButton(
                        label = "Not now",
                        primary = false,
                        enabled = true,
                        onClick = actions.onDismiss,
                        modifier = Modifier
                            .focusRequester(dismissFocusRequester)
                            .focusProperties {
                                left = if (playEnabled) primaryFocusRequester else FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun PostPlayActionButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(160),
        label = "post-play focus scale",
    )
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    val background = when {
        !enabled -> SlugYardPalette.SurfaceElevated.copy(alpha = 0.55f)
        focused && primary -> Color.White
        focused -> SlugYardPalette.Accent
        primary -> SlugYardPalette.Accent
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> SlugYardPalette.OnCanvasMuted
        focused -> Color(0xFF141414)
        primary -> SlugYardPalette.Canvas
        else -> SlugYardPalette.OnCanvas
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .widthIn(min = 120.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(background, shape)
            .border(
                width = if (focused) 3.dp else SlugYardTvMetrics.FocusRingWidth,
                color = when {
                    focused -> SlugYardPalette.FocusRing
                    primary -> Color.Transparent
                    else -> SlugYardPalette.OnCanvasMuted.copy(alpha = 0.45f)
                },
                shape = shape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
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
