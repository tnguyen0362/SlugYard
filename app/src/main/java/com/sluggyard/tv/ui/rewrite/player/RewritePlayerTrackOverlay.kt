package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.domain.model.Subtitle
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerTrackActions
import com.sluggyard.tv.ui.screens.player.TrackInfo

/** Rewrite-owned presentation for audio tracks, subtitle tracks, and a compact style editor. */
@Composable
fun RewritePlayerTrackOverlay(
    state: PlayerUiState,
    actions: RewritePlayerTrackActions,
    modifier: Modifier = Modifier,
) {
    when {
        state.showAudioOverlay -> RewriteTrackSheet("Audio", actions.onDismiss, modifier) {
            if (state.audioTracks.isEmpty()) {
                TrackStatus("No audio tracks found")
            } else {
                TrackList(
                    tracks = state.audioTracks,
                    selected = state.selectedAudioTrackIndex,
                    onSelected = actions.onSelectAudio,
                )
            }
        }
        state.showSubtitleOverlay -> RewriteTrackSheet("Subtitles", actions.onDismiss, modifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = actions.onDisableSubtitles) { Text("Off") }
                OutlinedButton(onClick = actions.onOpenSubtitleStyle) { Text("Style") }
            }
            if (state.subtitleTracks.isNotEmpty()) {
                Text("On this device", style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvasMuted)
                TrackList(
                    tracks = state.subtitleTracks,
                    selected = state.selectedSubtitleTrackIndex,
                    onSelected = actions.onSelectSubtitleTrack,
                )
            }
            if (state.isLoadingAddonSubtitles) {
                TrackStatus("Searching subtitle sources…")
            } else if (state.addonSubtitles.isNotEmpty()) {
                Text("Community subtitles", style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvasMuted)
                AddonSubtitleList(state.addonSubtitles, state.selectedAddonSubtitle, actions.onSelectAddonSubtitle)
            } else if (state.subtitleTracks.isEmpty()) {
                TrackStatus("No subtitles available")
            }
        }
        state.showSubtitleStylePanel -> RewriteTrackSheet("Subtitle style", actions.onCloseSubtitleStyle, modifier) {
            Text("Size", style = MaterialTheme.typography.titleSmall)
            StepperRow(
                value = "${state.subtitleStyle.size}%",
                onDecrease = { actions.onSetSubtitleSize((state.subtitleStyle.size - 10).coerceAtLeast(50)) },
                onIncrease = { actions.onSetSubtitleSize((state.subtitleStyle.size + 10).coerceAtMost(200)) },
            )
            Text("Vertical position", style = MaterialTheme.typography.titleSmall)
            StepperRow(
                value = "${state.subtitleStyle.verticalOffset}% from bottom",
                onDecrease = { actions.onSetSubtitleVerticalOffset((state.subtitleStyle.verticalOffset - 5).coerceAtLeast(-20)) },
                onIncrease = { actions.onSetSubtitleVerticalOffset((state.subtitleStyle.verticalOffset + 5).coerceAtMost(50)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bold", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = { actions.onSetSubtitleBold(!state.subtitleStyle.bold) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.subtitleStyle.bold) SlugYardPalette.Accent else SlugYardPalette.SurfaceElevated,
                    ),
                ) { Text(if (state.subtitleStyle.bold) "On" else "Off") }
            }
            OutlinedButton(onClick = actions.onResetSubtitleStyle) { Text("Reset style") }
        }
        state.showSpeedDialog -> RewriteTrackSheet("Playback speed", actions.onDismiss, modifier) {
            val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(speeds, key = { it }) { speed ->
                    val selected = speed == state.playbackSpeed
                    Button(
                        onClick = { actions.onSetPlaybackSpeed(speed) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) SlugYardPalette.Accent else SlugYardPalette.Surface,
                            contentColor = SlugYardPalette.OnCanvas,
                        ),
                    ) { Text("${speed}×") }
                }
            }
        }
    }
}

@Composable
private fun RewriteTrackSheet(title: String, onClose: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(740.dp).heightIn(max = 820.dp).clip(RoundedCornerShape(12.dp))
                .background(SlugYardPalette.Canvas).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            content()
        }
    }
}

@Composable
private fun TrackList(tracks: List<TrackInfo>, selected: Int, onSelected: (Int) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 440.dp)) {
        items(tracks, key = { it.index }) { track ->
            TrackRow(track.name, track.language, track.index == selected || track.isSelected) { onSelected(track.index) }
        }
    }
}

@Composable
private fun AddonSubtitleList(subtitles: List<Subtitle>, selected: Subtitle?, onSelected: (Subtitle) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 300.dp)) {
        items(subtitles, key = { "${it.id}:${it.url}" }) { subtitle ->
            TrackRow(subtitle.getDisplayLanguage(), subtitle.addonName, subtitle == selected) { onSelected(subtitle) }
        }
    }
}

@Composable
private fun TrackRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        detail?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted) }
    }
}

@Composable
private fun StepperRow(value: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onDecrease) { Text("−") }
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onIncrease) { Text("+") }
    }
}

@Composable
private fun TrackStatus(message: String) {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SlugYardPalette.Surface).padding(18.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = SlugYardPalette.OnCanvasMuted,
    )
}
