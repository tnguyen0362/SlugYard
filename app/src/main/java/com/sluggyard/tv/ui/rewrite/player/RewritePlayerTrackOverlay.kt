package com.sluggyard.tv.ui.rewrite.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.RewritePlayerTrackActions
import com.sluggyard.tv.ui.screens.player.TrackInfo
import com.sluggyard.tv.ui.util.languageCodeToName

private const val SubtitleOffKey = "off"
private const val SubtitleFooterKey = "footer"

/** Rewrite-owned presentation for audio tracks, subtitle tracks, and a compact style editor. */
@Composable
fun RewritePlayerTrackOverlay(
    state: PlayerUiState,
    actions: RewritePlayerTrackActions,
    modifier: Modifier = Modifier,
) {
    val listFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val subtitlesOff = state.selectedSubtitleTrackIndex < 0 && state.selectedAddonSubtitle == null

    // List composables own scroll-then-focus. Only fall back to Close when there is no list target.
    LaunchedEffect(
        state.showAudioOverlay,
        state.showSubtitleOverlay,
        state.showSubtitleStylePanel,
        state.showSpeedDialog,
        state.audioTracks.isEmpty(),
    ) {
        val needsCloseFallback = when {
            state.showAudioOverlay && state.audioTracks.isEmpty() -> true
            state.showSubtitleOverlay -> false
            state.showSubtitleStylePanel || state.showSpeedDialog -> true
            else -> false
        }
        if (needsCloseFallback) closeFocusRequester.requestRewritePlayerFocus()
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
                    listFocusRequester = listFocusRequester,
                )
            }
        }
        state.showSubtitleOverlay -> RewriteTrackSheet(
            title = "Subtitles",
            onClose = actions.onDismiss,
            modifier = modifier,
            closeFocusRequester = closeFocusRequester,
            headerTrailing = {
                OutlinedButton(onClick = actions.onOpenSubtitleStyle) { Text("Style") }
            },
        ) {
            // Build unique keys from list ordinals. Media3 format.id is often null/blank/duplicated
            // across text tracks; colliding LazyColumn keys break TV focus traversal on those streams.
            val subtitleChoices = buildList {
                state.subtitleTracks.forEachIndexed { ordinal, track ->
                    val language = subtitleLanguageName(track.language, track.name)
                    val format = subtitleCodecTag(track.codec, track.name)
                    add(
                        SubtitleChoice(
                            key = "embedded:$ordinal:${track.trackId.orEmpty()}:${track.index}",
                            language = language,
                            title = listOfNotNull(language, format).joinToString(" "),
                            detail = if (track.isForced) "Forced" else null,
                            // Prefer the UI index so MPV/Media3 isSelected desync cannot mark
                            // multiple rows selected after language sort (breaks initial focus).
                            selected = state.selectedAddonSubtitle == null && (
                                if (state.selectedSubtitleTrackIndex >= 0) {
                                    track.index == state.selectedSubtitleTrackIndex
                                } else {
                                    track.isSelected
                                }
                            ),
                            onClick = { actions.onSelectSubtitleTrack(track.index) },
                        ),
                    )
                }
                state.addonSubtitles.forEachIndexed { ordinal, subtitle ->
                    val language = subtitle.getDisplayLanguage()
                    val format = subtitleFormatTag(subtitle.format, subtitle.url)
                    add(
                        SubtitleChoice(
                            key = "addon:$ordinal:${subtitle.id}:${subtitle.url}",
                            language = language,
                            title = listOfNotNull(language, format).joinToString(" "),
                            detail = null,
                            selected = subtitle == state.selectedAddonSubtitle,
                            onClick = { actions.onSelectAddonSubtitle(subtitle) },
                        ),
                    )
                }
            }.sortedWith(compareBy<SubtitleChoice> { it.language.lowercase() }.thenBy { it.title })

            // Off + tracks + search footer share one LazyColumn so D-pad can scroll the full list.
            // A focusable Search button *below* the list stole Down before focusGroup could scroll.
            SubtitleChoiceList(
                choices = subtitleChoices,
                subtitlesOff = subtitlesOff,
                onDisableSubtitles = actions.onDisableSubtitles,
                listFocusRequester = listFocusRequester,
                isLoadingAddonSubtitles = state.isLoadingAddonSubtitles,
                onRetrySubtitleSearch = actions.onRetrySubtitleSearch,
            )
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusGroup(),
            ) {
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
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.CenterStart) {
        // Fixed fraction height so weighted LazyColumns always get a real viewport. Shrink-wrapped
        // sheets made short lists unnavigable on TV (focus could not move/scroll inside the list).
        Column(
            modifier = Modifier
                .padding(start = 48.dp)
                .width(740.dp)
                .fillMaxHeight(0.9f)
                .heightIn(max = 820.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SlugYardPalette.Canvas)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = SlugYardPalette.OnCanvas,
                )
                if (headerTrailing != null) {
                    headerTrailing()
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocusRequester)) { Text("Close") }
            }
            content()
        }
    }
}

@Composable
private fun ColumnScope.TrackList(
    tracks: List<TrackInfo>,
    selected: Int,
    onSelected: (Int) -> Unit,
    listFocusRequester: FocusRequester,
) {
    val listState = rememberLazyListState()
    val selectedIndex = tracks.indexOfFirst { it.index == selected || it.isSelected }
        .takeIf { it >= 0 }
        ?: 0

    LaunchedEffect(tracks.map { it.index }, selectedIndex) {
        listState.scrollToItem(selectedIndex)
        listFocusRequester.requestRewritePlayerFocus()
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .focusGroup(),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.index }) { index, track ->
            TrackRow(
                title = track.name,
                detail = track.language,
                selected = track.index == selected || track.isSelected,
                onClick = { onSelected(track.index) },
                focusRequester = listFocusRequester.takeIf { index == selectedIndex },
            )
        }
    }
}

private data class SubtitleChoice(
    val key: String,
    val language: String,
    val title: String,
    val detail: String?,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ColumnScope.SubtitleChoiceList(
    choices: List<SubtitleChoice>,
    subtitlesOff: Boolean,
    onDisableSubtitles: () -> Unit,
    listFocusRequester: FocusRequester,
    isLoadingAddonSubtitles: Boolean,
    onRetrySubtitleSearch: () -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedChoiceIndex = choices.indexOfFirst(SubtitleChoice::selected)
    // Index 0 is always Off; track rows follow; search/status footer is last.
    val focusIndex = when {
        subtitlesOff || selectedChoiceIndex < 0 -> 0
        else -> selectedChoiceIndex + 1
    }
    val focusKey = if (focusIndex == 0) SubtitleOffKey else choices[selectedChoiceIndex].key

    // Scroll + focus once when the sheet opens. Re-running on addon/MPV list churn jumps the
    // caret mid-browse and feels like the list "won't scroll".
    LaunchedEffect(Unit) {
        listState.scrollToItem(focusIndex.coerceIn(0, choices.size))
        listFocusRequester.requestRewritePlayerFocus()
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .focusGroup(),
    ) {
        item(key = SubtitleOffKey) {
            SubtitlePillRow(
                title = "Off",
                detail = null,
                selected = subtitlesOff,
                onClick = onDisableSubtitles,
                focusRequester = listFocusRequester.takeIf { focusKey == SubtitleOffKey },
            )
        }
        items(choices, key = SubtitleChoice::key) { choice ->
            SubtitlePillRow(
                title = choice.title,
                detail = choice.detail,
                selected = choice.selected && !subtitlesOff,
                onClick = choice.onClick,
                focusRequester = listFocusRequester.takeIf { choice.key == focusKey },
            )
        }
        item(key = SubtitleFooterKey) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isLoadingAddonSubtitles) {
                    TrackStatus("Searching for more subtitles...")
                } else {
                    if (choices.isEmpty()) TrackStatus("No subtitles found automatically")
                    OutlinedButton(
                        onClick = onRetrySubtitleSearch,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (choices.isEmpty()) "Search OpenSubtitles" else "Search again")
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitlePillRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> SlugYardPalette.SurfaceElevated
                    else -> Color.Transparent
                },
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            // clickable already installs a focus target — do not add a second .focusable().
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    focused -> Color(0xFF181818)
                    selected -> SlugYardPalette.OnCanvas
                    else -> SlugYardPalette.OnCanvasMuted
                },
            )
            detail?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Color(0xFF444444) else SlugYardPalette.OnCanvasMuted,
                )
            }
        }
        if (selected || focused) {
            Text(
                "✓",
                color = if (focused) Color(0xFF181818) else SlugYardPalette.Accent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
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

private fun subtitleCodecTag(codec: String?, name: String?): String? {
    val haystack = listOfNotNull(codec, name).joinToString(" ").lowercase()
    return when {
        "pgs" in haystack || "sup" in haystack || "hdmv" in haystack -> "PGS"
        "ass" in haystack || "ssa" in haystack -> "ASS"
        "srt" in haystack || "subrip" in haystack -> "SRT"
        "vtt" in haystack || "webvtt" in haystack -> "VTT"
        "ttml" in haystack -> "TTML"
        "dvb" in haystack -> "DVB"
        codec.isNullOrBlank() -> null
        else -> codec.substringAfterLast('/').uppercase().take(8)
    }
}

private fun subtitleFormatTag(format: String?, url: String): String? {
    val extension = format
        ?.trim()
        ?.substringAfterLast('.')
        ?.takeIf(String::isNotBlank)
        ?: url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .ifBlank { url.substringAfter("format=", "").substringBefore('&') }
    return when (extension.lowercase()) {
        "srt" -> "SRT"
        "vtt", "webvtt" -> "VTT"
        "ass", "ssa" -> "ASS"
        "sub" -> "SUB"
        "ttml" -> "TTML"
        "pgs", "sup" -> "PGS"
        "idx" -> "IDX"
        else -> null
    }
}

@Composable
private fun TrackRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> SlugYardPalette.SurfaceElevated
                    selected -> SlugYardPalette.SurfaceElevated
                    else -> SlugYardPalette.Surface
                },
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused || selected) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
        )
        detail?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted)
        }
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
