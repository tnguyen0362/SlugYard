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
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerSkipActions

/** Rewrite-owned, TV-focusable skip prompt. The retained controller supplies only state and intents. */
@Composable
fun RewritePlayerSkipOverlay(
    state: PlayerUiState,
    actions: RewritePlayerSkipActions,
    modifier: Modifier = Modifier,
) {
    val interval = state.activeSkipInterval
    val intervalType = interval?.type.orEmpty()
    val visible = interval != null &&
        !state.skipIntervalDismissed &&
        !state.showPauseOverlay &&
        !state.showLoadingOverlay &&
        state.postPlayMode == null
    LaunchedEffect(visible) { actions.onVisibilityChanged(visible) }
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
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlugYardPalette.OnCanvas,
                        contentColor = SlugYardPalette.Canvas,
                    ),
                ) { Text(skipLabel(intervalType)) }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = actions.onDismiss, shape = RoundedCornerShape(4.dp)) {
                    Text("Not now")
                }
            }
        }
    }
}

private fun skipLabel(type: String): String = when {
    type.contains("recap", ignoreCase = true) -> "Skip recap"
    type.contains("outro", ignoreCase = true) || type.contains("ending", ignoreCase = true) || type.contains("ed", ignoreCase = true) -> "Skip outro"
    else -> "Skip intro"
}
