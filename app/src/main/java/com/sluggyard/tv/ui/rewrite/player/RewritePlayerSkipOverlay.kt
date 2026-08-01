package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerSkipActions
import com.sluggyard.tv.ui.rewrite.requestFocusReliably

/** Rewrite-owned, TV-focusable skip prompt. The retained controller supplies only state and intents. */
@Composable
fun RewritePlayerSkipOverlay(
    state: PlayerUiState,
    actions: RewritePlayerSkipActions,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val notNowFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
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
    // Prefer Skip Intro over transport chrome. Auto-showing controls previously stole focus
    // from the skip button and made OK/center hit Play/Pause instead.
    LaunchedEffect(visible, interval?.startTime, interval?.type, state.showControls) {
        if (!visible) return@LaunchedEffect
        if (state.showControls) {
            // Let chrome compose, then reclaim focus for the skip action.
            kotlinx.coroutines.delay(48)
        }
        focusRequester.requestFocusReliably(retries = 10)
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = SlugYardTvMetrics.ScreenHorizontalInset,
                        bottom = if (state.showControls) 118.dp else 34.dp,
                    ),
            ) {
                Button(
                    onClick = actions.onSkip,
                     modifier = Modifier
                         .focusRequester(focusRequester)
                         .focusProperties { right = notNowFocusRequester },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlugYardPalette.Accent,
                        contentColor = SlugYardPalette.Canvas,
                    ),
                ) { Text(skipLabel(intervalType)) }
                Spacer(Modifier.width(10.dp))
                 OutlinedButton(
                     onClick = actions.onDismiss,
                     modifier = Modifier.focusRequester(notNowFocusRequester),
                     shape = RoundedCornerShape(4.dp),
                 ) {
                    Text("Not now")
                }
            }
        }
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
