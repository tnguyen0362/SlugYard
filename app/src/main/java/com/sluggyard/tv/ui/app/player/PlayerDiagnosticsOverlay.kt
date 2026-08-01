package com.sluggyard.tv.ui.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PlayerDiagnosticsActions
import com.sluggyard.tv.ui.screens.player.StreamInfoData
import kotlin.math.roundToInt

/**
 * Rewrite-owned playback facts. This surface only renders state observed by the retained player;
 * it cannot alter source choice, track selection, codec handling, or playback configuration.
 */
@Composable
fun PlayerDiagnosticsOverlay(
    state: PlayerUiState,
    actions: PlayerDiagnosticsActions,
    modifier: Modifier = Modifier,
) {
    val visible = state.showStreamInfoOverlay
    BackHandler(enabled = visible, onBack = actions.onDismiss)
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .focusable(),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .background(SlugYardPalette.SurfaceElevated, RoundedCornerShape(8.dp))
                    .padding(SlugYardTvMetrics.ScreenHorizontalInset),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Playback information", style = MaterialTheme.typography.headlineSmall, color = SlugYardPalette.OnCanvas)
                val data = state.streamInfoData
                if (data == null) {
                    Text("Details will appear once the stream is ready.", color = SlugYardPalette.OnCanvasMuted)
                } else {
                    DiagnosticsSection("Source") {
                        DiagnosticsFact("Provider", data.addonName)
                        DiagnosticsFact("Stream", data.streamName?.compact())
                        DiagnosticsFact("Description", data.streamDescription?.compact())
                        DiagnosticsFact("Player", data.playerEngine)
                    }
                    DiagnosticsSection("Video") {
                        DiagnosticsFact("Codec", data.videoCodec)
                        DiagnosticsFact("Resolution", data.resolution())
                        DiagnosticsFact("Frame rate", data.videoFrameRate?.let { "${formatDecimal(it)} fps" })
                        DiagnosticsFact("Bitrate", data.videoBitrate?.let(::formatBitrate))
                    }
                    DiagnosticsSection("Audio") {
                        DiagnosticsFact("Codec", data.audioCodec)
                        DiagnosticsFact("Channels", data.audioChannels)
                        DiagnosticsFact("Language", data.audioLanguage)
                        DiagnosticsFact("Sample rate", data.audioSampleRate?.let { "${it / 1000} kHz" })
                    }
                    DiagnosticsSection("Subtitles") {
                        DiagnosticsFact("Track", data.subtitleName)
                        DiagnosticsFact("Codec", data.subtitleCodec)
                        DiagnosticsFact("Language", data.subtitleLanguage)
                        DiagnosticsFact("Source", data.subtitleSource)
                    }
                    DiagnosticsSection("File") {
                        DiagnosticsFact("Name", data.filename)
                        DiagnosticsFact("Size", data.fileSize?.let(::formatFileSize))
                    }
                }
                Button(
                    onClick = actions.onDismiss,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlugYardPalette.OnCanvas,
                        contentColor = SlugYardPalette.Canvas,
                    ),
                ) { Text("Back") }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = SlugYardPalette.OnCanvasMuted)
        content()
    }
}

@Composable
private fun DiagnosticsFact(label: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, modifier = Modifier.widthIn(min = 124.dp), color = SlugYardPalette.OnCanvasMuted)
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = SlugYardPalette.OnCanvas)
        }
    }
}

private fun String.compact(): String = replace("\n", " · ")

private fun StreamInfoData.resolution(): String? =
    if (videoWidth != null && videoHeight != null) "$videoWidth × $videoHeight" else null

private fun formatDecimal(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else "%.3f".format(value)

private fun formatBitrate(bps: Int): String = when {
    bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
    bps >= 1_000 -> "%.0f kbps".format(bps / 1_000.0)
    else -> "$bps bps"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
