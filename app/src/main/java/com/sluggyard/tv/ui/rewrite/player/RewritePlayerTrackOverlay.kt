package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerTrackActions
import com.sluggyard.tv.ui.screens.player.TrackInfo
import com.sluggyard.tv.ui.util.languageCodeToName

/** Rewrite-owned presentation for audio tracks, subtitle tracks, and a compact style editor. */
@Composable
fun RewritePlayerTrackOverlay(
    state: PlayerUiState,
    actions: RewritePlayerTrackActions,
    modifier: Modifier = Modifier,
) {
    val firstTrackFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(
        state.showAudioOverlay,
        state.showSubtitleOverlay,
        state.showSubtitleStylePanel,
        state.showSpeedDialog,
        state.audioTracks.firstOrNull()?.index,
        state.subtitleTracks.firstOrNull()?.index,
        state.addonSubtitles.firstOrNull()?.id,
    ) {
        val target = when {
            state.showAudioOverlay && state.audioTracks.isNotEmpty() -> firstTrackFocusRequester
            state.showSubtitleOverlay && (state.subtitleTracks.isNotEmpty() || state.addonSubtitles.isNotEmpty()) -> firstTrackFocusRequester
            else -> closeFocusRequester
        }
        target.requestRewritePlayerFocus()
    }

    when {
        state.showAudioOverlay -> RewriteTrackSheet("Audio", actions.onDismiss, modifier, closeFocusRequester) {
            if (state.audioTracks.isEmpty()) {
                TrackStatus("No audio tracks found")
            } else {
                TrackList(
                    tracks = state.audioTracks,
                    selected = state.selectedAudioTrackIndex,
                    onSelected = actions.onSelectAudio,
                    firstFocusRequester = firstTrackFocusRequester,
                )
            }
        }
        state.showSubtitleOverlay -> RewriteTrackSheet("Subtitles", actions.onDismiss, modifier, closeFocusRequester) {
            val subtitleChoices = buildList {
                state.subtitleTracks.forEach { track ->
                    val language = subtitleLanguageName(track.language, track.name)
                    add(
                        SubtitleChoice(
                            key = "embedded:${track.trackId ?: track.index}",
                            language = language,
                            title = if (track.isForced) "$language · Forced" else language,
                            detail = listOfNotNull(track.codec?.uppercase(), "Embedded").joinToString(" · "),
                            selected = track.index == state.selectedSubtitleTrackIndex || track.isSelected,
                            onClick = { actions.onSelectSubtitleTrack(track.index) },
                        ),
                    )
                }
                state.addonSubtitles.forEach { subtitle ->
                    val language = subtitle.getDisplayLanguage()
                    add(
                        SubtitleChoice(
                            key = "addon:${subtitle.id}:${subtitle.url}",
                            language = language,
                            title = language,
                            detail = subtitleFormat(subtitle.format, subtitle.url),
                            selected = subtitle == state.selectedAddonSubtitle,
                            onClick = { actions.onSelectAddonSubtitle(subtitle) },
                        ),
                    )
                }
            }.sortedWith(compareBy<SubtitleChoice> { it.language.lowercase() }.thenBy { it.detail })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = actions.onDisableSubtitles) { Text("Off") }
                OutlinedButton(onClick = actions.onOpenSubtitleStyle) { Text("Style") }
            }
            if (subtitleChoices.isNotEmpty()) SubtitleChoiceList(subtitleChoices, firstTrackFocusRequester)
            if (state.isLoadingAddonSubtitles) {
                TrackStatus("Searching for more subtitles...")
            } else {
                if (subtitleChoices.isEmpty()) TrackStatus("No subtitles found automatically")
                OutlinedButton(onClick = actions.onRetrySubtitleSearch) {
                    Text(if (subtitleChoices.isEmpty()) "Search OpenSubtitles" else "Search again")
                }
            }
        }
        state.showSubtitleStylePanel -> RewriteTrackSheet("Subtitle style", actions.onCloseSubtitleStyle, modifier, closeFocusRequester) {
            Text("Size", style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
            StepperRow(
                value = "${state.subtitleStyle.size}%",
                onDecrease = { actions.onSetSubtitleSize((state.subtitleStyle.size - 10).coerceAtLeast(50)) },
                onIncrease = { actions.onSetSubtitleSize((state.subtitleStyle.size + 10).coerceAtMost(200)) },
            )
            Text("Vertical position", style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
            StepperRow(
                value = "${state.subtitleStyle.verticalOffset}% from bottom",
                onDecrease = { actions.onSetSubtitleVerticalOffset((state.subtitleStyle.verticalOffset - 5).coerceAtLeast(-20)) },
                onIncrease = { actions.onSetSubtitleVerticalOffset((state.subtitleStyle.verticalOffset + 5).coerceAtMost(50)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bold", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
                Button(
                    onClick = { actions.onSetSubtitleBold(!state.subtitleStyle.bold) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.subtitleStyle.bold) SlugYardPalette.Accent else SlugYardPalette.SurfaceElevated,
                    ),
                ) { Text(if (state.subtitleStyle.bold) "On" else "Off") }
            }
            Text("Text color", style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
            ColorSwatchRow(
                colors = subtitleTextColorSwatches,
                selectedArgb = state.subtitleStyle.textColor,
                onSelect = actions.onSetSubtitleTextColor,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Outline", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
                Button(
                    onClick = { actions.onSetSubtitleOutlineEnabled(!state.subtitleStyle.outlineEnabled) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.subtitleStyle.outlineEnabled) SlugYardPalette.Accent else SlugYardPalette.SurfaceElevated,
                    ),
                ) { Text(if (state.subtitleStyle.outlineEnabled) "On" else "Off") }
            }
            if (state.subtitleStyle.outlineEnabled) {
                ColorSwatchRow(
                    colors = subtitleOutlineColorSwatches,
                    selectedArgb = state.subtitleStyle.outlineColor,
                    onSelect = actions.onSetSubtitleOutlineColor,
                )
            }
            OutlinedButton(onClick = actions.onResetSubtitleStyle) { Text("Reset style") }
        }
        state.showSpeedDialog -> RewriteTrackSheet("Playback speed", actions.onDismiss, modifier, closeFocusRequester) {
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
private fun RewriteTrackSheet(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    closeFocusRequester: FocusRequester,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(740.dp).heightIn(max = 820.dp).clip(RoundedCornerShape(12.dp))
                .background(SlugYardPalette.Canvas).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = SlugYardPalette.OnCanvas,
                )
                OutlinedButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocusRequester)) { Text("Close") }
            }
            content()
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<TrackInfo>,
    selected: Int,
    onSelected: (Int) -> Unit,
    firstFocusRequester: FocusRequester? = null,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 440.dp)) {
        items(tracks, key = { it.index }) { track ->
            TrackRow(
                title = track.name,
                detail = track.language,
                selected = track.index == selected || track.isSelected,
                onClick = { onSelected(track.index) },
                focusRequester = firstFocusRequester.takeIf { track.index == tracks.firstOrNull()?.index },
            )
        }
    }
}

private data class SubtitleChoice(
    val key: String,
    val language: String,
    val title: String,
    val detail: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SubtitleChoiceList(
    choices: List<SubtitleChoice>,
    firstFocusRequester: FocusRequester? = null,
) {
    val initialChoice = choices.firstOrNull(SubtitleChoice::selected) ?: choices.firstOrNull()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 440.dp)) {
        items(choices, key = SubtitleChoice::key) { choice ->
            TrackRow(
                title = choice.title,
                detail = choice.detail,
                selected = choice.selected,
                onClick = choice.onClick,
                focusRequester = firstFocusRequester.takeIf { choice == initialChoice },
            )
        }
    }
}

private fun subtitleLanguageName(language: String?, fallback: String): String {
    language?.takeIf(String::isNotBlank)?.let { return languageCodeToName(it) }
    return if (fallback.matches(Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,4})?"))) {
        languageCodeToName(fallback)
    } else {
        fallback
    }
}

private fun subtitleFormat(format: String?, url: String): String {
    val extension = format
        ?.trim()
        ?.substringAfterLast('.')
        ?.takeIf(String::isNotBlank)
        ?: url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .ifBlank { url.substringAfter("format=", "").substringBefore('&') }
    return extension
        .lowercase()
        .takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "pgs", "sup", "idx") }
        ?.uppercase()
        ?: "Unknown format"
}

@Composable
private fun TrackRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clickable(onClick = onClick).heightIn(min = 56.dp).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SlugYardPalette.OnCanvas)
        detail?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted) }
    }
}

private val subtitleTextColorSwatches = listOf(
    Color.White,
    Color(0xFFB6B5B1),
    Color(0xFFF2C94C),
    Color(0xFF56CCF2),
    Color(0xFFEF6A6A),
    Color(0xFF6FCF97),
)

private val subtitleOutlineColorSwatches = listOf(
    Color.Black,
    Color.White,
    Color(0xFF56CCF2),
    Color(0xFFEF6A6A),
)

@Composable
private fun ColorSwatchRow(colors: List<Color>, selectedArgb: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEach { color ->
            val selected = color.toArgb() == selectedArgb
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (selected) {
                            Modifier.border(3.dp, SlugYardPalette.Accent, CircleShape)
                        } else {
                            Modifier.border(1.dp, SlugYardPalette.OnCanvasMuted.copy(alpha = .4f), CircleShape)
                        },
                    )
                    .clickable { onSelect(color.toArgb()) },
            )
        }
    }
}

@Composable
private fun StepperRow(value: String, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onDecrease) { Text("−") }
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvas)
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
