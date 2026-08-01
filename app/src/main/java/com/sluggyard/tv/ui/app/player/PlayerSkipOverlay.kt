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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.requestFocusReliably
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PlayerSkipActions

/** Rewrite-owned, TV-focusable skip prompt. The retained controller supplies only state and intents. */
@Composable
fun PlayerSkipOverlay(
    state: PlayerUiState,
    actions: PlayerSkipActions,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val notNowFocusRequester = remember { FocusRequester() }
    val interval = state.activeSkipInterval
    val intervalType = interval?.type.orEmpty()
    val visible = interval != null &&
        !state.skipIntervalDismissed &&
        !state.showPauseOverlay &&
        !state.showLoadingOverlay &&
        state.postPlayMode == null
    LaunchedEffect(visible) {
        actions.onVisibilityChanged(visible)
    }
    // Seed Skip once when the prompt appears / interval changes. Do NOT reclaim whenever
    // chrome flips — that stole OK from play/pause and fought L/R between Skip / Not now.
    LaunchedEffect(visible, interval?.startTime, interval?.type) {
        if (!visible) return@LaunchedEffect
        focusRequester.requestFocusReliably(retries = 10)
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = fadeOut(animationSpec = tween(durationMillis = 220)),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = SlugYardTvMetrics.ScreenHorizontalInset,
                        bottom = if (state.showControls) 118.dp else 34.dp,
                    )
                    // Keep D-pad Left/Right inside Skip ↔ Not now while the prompt is up.
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipPromptButton(
                    label = skipLabel(intervalType),
                    primary = true,
                    onClick = actions.onSkip,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusProperties {
                            right = notNowFocusRequester
                            left = FocusRequester.Cancel
                        },
                )
                Spacer(Modifier.width(12.dp))
                SkipPromptButton(
                    label = "Not now",
                    primary = false,
                    onClick = actions.onDismiss,
                    modifier = Modifier
                        .focusRequester(notNowFocusRequester)
                        .focusProperties {
                            left = focusRequester
                            right = FocusRequester.Cancel
                        },
                )
            }
        }
    }
}

@Composable
private fun SkipPromptButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(160),
        label = "skip focus scale",
    )
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    val background = when {
        focused && primary -> Color.White
        focused -> SlugYardPalette.Accent
        primary -> SlugYardPalette.Accent
        else -> SlugYardPalette.SurfaceElevated.copy(alpha = 0.92f)
    }
    val content = when {
        focused && primary -> Color(0xFF141414)
        focused -> Color(0xFF141414)
        primary -> SlugYardPalette.Canvas
        else -> SlugYardPalette.OnCanvas
    }
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .widthIn(min = 132.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(background, shape)
            .border(
                width = if (focused) 3.dp else SlugYardTvMetrics.FocusRingWidth,
                color = if (focused) SlugYardPalette.FocusRing else {
                    if (primary) Color.Transparent else SlugYardPalette.OnCanvasMuted.copy(alpha = 0.45f)
                },
                shape = shape,
            )
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontWeight = FontWeight.SemiBold,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}

private fun skipLabel(type: String): String = when {
    type.contains("recap", ignoreCase = true) -> "Skip recap"
    type.equals("ed", ignoreCase = true) ||
        type.contains("mixed-ed", ignoreCase = true) ||
        type.contains("outro", ignoreCase = true) ||
        type.contains("ending", ignoreCase = true) ||
        type.contains("credits", ignoreCase = true) -> "Skip outro"
    else -> "Skip intro"
}
