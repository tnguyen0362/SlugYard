package com.sluggyard.tv.ui.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PlayerSubtitleTimingActions
import com.sluggyard.tv.ui.screens.player.SUBTITLE_DELAY_MAX_MS
import com.sluggyard.tv.ui.screens.player.SUBTITLE_DELAY_MIN_MS
import com.sluggyard.tv.ui.screens.player.SUBTITLE_DELAY_STEP_MS
import com.sluggyard.tv.ui.screens.player.SubtitleSyncCue
import kotlin.math.abs

/** TV-first subtitle delay and line-sync surface backed only by retained player intents. */
@Composable
fun PlayerSubtitleTimingOverlay(
    state: PlayerUiState,
    currentPositionMs: Long,
    actions: PlayerSubtitleTimingActions,
    modifier: Modifier = Modifier,
) {
    val syncVisible = state.showSubtitleTimingDialog && state.canShowTiming()
    val delayVisible = state.showSubtitleDelayOverlay && !syncVisible && state.canShowTiming()
    BackHandler(enabled = syncVisible, onBack = actions.onDismissSync)
    BackHandler(enabled = delayVisible, onBack = actions.onDismissDelay)

    AnimatedVisibility(visible = delayVisible, enter = fadeIn(), exit = fadeOut()) {
        DelayCard(
            delayMs = state.subtitleDelayMs,
            onAdjust = actions.onAdjustDelay,
            onSyncByLine = actions.onOpenSyncByLine,
            onDismiss = actions.onDismissDelay,
            modifier = modifier,
        )
    }
    AnimatedVisibility(visible = syncVisible, enter = fadeIn(), exit = fadeOut()) {
        SyncCard(
            state = state,
            currentPositionMs = currentPositionMs,
            actions = actions,
            modifier = modifier,
        )
    }
}

@Composable
private fun DelayCard(
    delayMs: Int,
    onAdjust: (Int) -> Unit,
    onSyncByLine: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val cardShape = RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius)
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp)
                .fillMaxWidth(0.66f)
                .background(SlugYardPalette.Surface, cardShape)
                .border(1.dp, SlugYardPalette.Divider, cardShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Subtitle timing",
                fontWeight = FontWeight.SemiBold,
                color = SlugYardPalette.OnCanvas,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    label = "−0.1 s",
                    onClick = { onAdjust(-SUBTITLE_DELAY_STEP_MS) },
                    style = ButtonStyle.Secondary,
                )
                Text(
                    formatDelay(delayMs),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = SlugYardPalette.OnCanvas,
                    fontWeight = FontWeight.Medium,
                )
                TvButton(
                    label = "+0.1 s",
                    onClick = { onAdjust(SUBTITLE_DELAY_STEP_MS) },
                    style = ButtonStyle.Secondary,
                )
            }
            TimingMeter(delayMs)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    label = "Sync by line",
                    onClick = onSyncByLine,
                    style = ButtonStyle.Primary,
                )
                TvButton(
                    label = "Done",
                    onClick = onDismiss,
                    style = ButtonStyle.Secondary,
                )
            }
        }
    }
}

@Composable
private fun TimingMeter(delayMs: Int) {
    val fraction = ((delayMs - SUBTITLE_DELAY_MIN_MS).toFloat() /
        (SUBTITLE_DELAY_MAX_MS - SUBTITLE_DELAY_MIN_MS).toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(SlugYardPalette.OnCanvasMuted.copy(alpha = .35f))) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(SlugYardPalette.Accent),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−60 s", color = SlugYardPalette.OnCanvasMuted)
            Text("0", color = SlugYardPalette.OnCanvasMuted)
            Text("+60 s", color = SlugYardPalette.OnCanvasMuted)
        }
    }
}

@Composable
private fun SyncCard(
    state: PlayerUiState,
    currentPositionMs: Long,
    actions: PlayerSubtitleTimingActions,
    modifier: Modifier,
) {
    val capturedAt = state.subtitleAutoSyncCapturedVideoMs
    val anchor = capturedAt ?: currentPositionMs
    val cues = state.subtitleAutoSyncCues.sortedBy { abs(it.startTimeMs - anchor) }.take(12)
    val cardShape = RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .68f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(.82f)
                .background(SlugYardPalette.Surface, cardShape)
                .border(1.dp, SlugYardPalette.Divider, cardShape)
                .padding(SlugYardTvMetrics.ScreenHorizontalInset),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Sync subtitle timing",
                fontWeight = FontWeight.SemiBold,
                color = SlugYardPalette.OnCanvas,
            )
            Text(
                if (capturedAt == null) "Pause at the spoken line, then capture the video time."
                else "Choose the subtitle line that is being spoken.",
                color = SlugYardPalette.OnCanvasMuted,
            )
            if (capturedAt == null) {
                TvButton(
                    label = "Capture video time",
                    onClick = actions.onCaptureNow,
                    style = ButtonStyle.Primary,
                )
            } else {
                state.subtitleAutoSyncStatus?.let { Text(it, color = SlugYardPalette.OnCanvasMuted) }
                state.subtitleAutoSyncError?.let { Text(it, color = SlugYardPalette.Danger) }
                if (state.subtitleAutoSyncLoading) {
                    Text("Loading subtitle lines…", color = SlugYardPalette.OnCanvasMuted)
                } else if (cues.isEmpty()) {
                    Text("No subtitle lines are available for this track.", color = SlugYardPalette.OnCanvasMuted)
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(cues, key = SubtitleSyncCue::startTimeMs) { cue ->
                            TvButton(
                                label = "${formatClock(cue.startTimeMs)}  ${cue.text}",
                                onClick = { actions.onCueSelected(cue) },
                                style = ButtonStyle.Secondary,
                                fillMaxWidth = true,
                            )
                        }
                    }
                }
            }
            TvButton(
                label = "Back",
                onClick = actions.onDismissSync,
                style = ButtonStyle.Secondary,
            )
        }
    }
}

private fun PlayerUiState.canShowTiming(): Boolean =
    error == null && !showLoadingOverlay && !showPauseOverlay && !showEpisodesPanel && !showSourcesPanel &&
        !showAudioOverlay && !showSubtitleOverlay && !showSubtitleStylePanel && !showSpeedDialog && !showMoreDialog

private fun formatDelay(valueMs: Int): String = "${if (valueMs >= 0) "+" else ""}${"%.1f".format(valueMs / 1000f)} s"

private fun formatClock(valueMs: Long): String {
    val totalSeconds = valueMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
