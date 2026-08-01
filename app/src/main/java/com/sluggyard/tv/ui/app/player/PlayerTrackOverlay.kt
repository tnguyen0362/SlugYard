package com.sluggyard.tv.ui.app.player

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PlayerSubtitleUtils
import com.sluggyard.tv.ui.screens.player.PlayerTrackActions
import com.sluggyard.tv.ui.screens.player.SUBTITLE_DELAY_STEP_MS
import com.sluggyard.tv.ui.screens.player.TrackInfo
import com.sluggyard.tv.ui.util.languageCodeToName
import kotlin.math.abs

private const val SubtitleOffKey = "off"
private const val SubtitleFooterKey = "footer"

internal fun audioTrackUiLabel(track: TrackInfo): String {
    val language = track.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let(::languageCodeToName)
        ?: "Unknown language"
    val format = audioFormatLabel(track.codec)
    return listOfNotNull(language, format).joinToString(" · ")
}

private fun audioFormatLabel(codec: String?): String? {
    val normalized = codec?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        "eac3" in normalized || "ec-3" in normalized -> "E-AC-3"
        "truehd" in normalized -> "TrueHD"
        "ac3" in normalized || "ac-3" in normalized -> "AC-3"
        "dts" in normalized -> "DTS"
        "opus" in normalized -> "Opus"
        "vorbis" in normalized -> "Vorbis"
        "flac" in normalized -> "FLAC"
        "aac" in normalized -> "AAC"
        "pcm" in normalized -> "PCM"
        else -> normalized.substringAfterLast('/').replace('-', ' ').uppercase()
    }
}

/** Rewrite-owned presentation for audio tracks, subtitle tracks, and a compact style editor. */
@Composable
fun PlayerTrackOverlay(
    state: PlayerUiState,
    actions: PlayerTrackActions,
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
        if (needsCloseFallback) closeFocusRequester.requestPlayerFocus()
    }

    when {
        state.showAudioOverlay -> TrackSheet("Audio", actions.onDismiss, modifier, closeFocusRequester) {
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
        state.showSubtitleOverlay -> TrackSheet(
            title = "Subtitles",
            onClose = actions.onDismiss,
            modifier = modifier,
            closeFocusRequester = closeFocusRequester,
            headerTrailing = {
                TvButton(
                    label = "Style",
                    onClick = actions.onOpenSubtitleStyle,
                    style = ButtonStyle.Secondary,
                )
            },
        ) {
            // Build unique keys from list ordinals. Media3 format.id is often null/blank/duplicated
            // across text tracks; colliding LazyColumn keys break TV focus traversal on those streams.
            val subtitleChoices = buildList {
                state.subtitleTracks.forEachIndexed { ordinal, track ->
                    val language = subtitleLanguageName(track.language, track.name)
                    val format = subtitleCodecTag(track.codec, track.sampleMimeType, track.name)
                    val (title, detail) = PlayerSubtitleUtils.formatSubtitleTrackLabel(
                        languageDisplay = language,
                        formatTag = format,
                        name = track.name,
                        isForced = track.isForced,
                        isSignsAndSongs = track.isSignsAndSongs,
                    )
                    add(
                        SubtitleChoice(
                            key = "embedded:$ordinal:${track.trackId.orEmpty()}:${track.index}",
                            language = language,
                            title = title,
                            detail = detail,
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
                    val (title, detail) = PlayerSubtitleUtils.formatSubtitleTrackLabel(
                        languageDisplay = language,
                        formatTag = format,
                        name = listOfNotNull(subtitle.addonName, subtitle.format).joinToString(" "),
                    )
                    add(
                        SubtitleChoice(
                            key = "addon:$ordinal:${subtitle.id}:${subtitle.url}",
                            language = language,
                            title = title,
                            detail = detail,
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
        state.showSubtitleStylePanel -> TrackSheet("Subtitle style", actions.onCloseSubtitleStyle, modifier, closeFocusRequester) {
            // Style controls can extend below the TV viewport; scroll so D-pad can reach
            // Outline color and Reset style. Mirrors the LazyColumn.weight(1f) pattern used by
            // the speed dialog above.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Timing delay", style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
                StepperRow(
                    value = formatStyleDelay(state.subtitleDelayMs),
                    onDecrease = { actions.onAdjustSubtitleDelay(-SUBTITLE_DELAY_STEP_MS) },
                    onIncrease = { actions.onAdjustSubtitleDelay(SUBTITLE_DELAY_STEP_MS) },
                )
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
                    TvButton(
                        label = if (state.subtitleStyle.bold) "On" else "Off",
                        onClick = { actions.onSetSubtitleBold(!state.subtitleStyle.bold) },
                        style = if (state.subtitleStyle.bold) ButtonStyle.Primary else ButtonStyle.Secondary,
                    )
                }
                Text("Text color", style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
                ColorSwatchRow(
                    colors = subtitleTextColorSwatches,
                    selectedArgb = state.subtitleStyle.textColor,
                    onSelect = actions.onSetSubtitleTextColor,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Outline", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = SlugYardPalette.OnCanvas)
                    TvButton(
                        label = if (state.subtitleStyle.outlineEnabled) "On" else "Off",
                        onClick = { actions.onSetSubtitleOutlineEnabled(!state.subtitleStyle.outlineEnabled) },
                        style = if (state.subtitleStyle.outlineEnabled) ButtonStyle.Primary else ButtonStyle.Secondary,
                    )
                }
                if (state.subtitleStyle.outlineEnabled) {
                    ColorSwatchRow(
                        colors = subtitleOutlineColorSwatches,
                        selectedArgb = state.subtitleStyle.outlineColor,
                        onSelect = actions.onSetSubtitleOutlineColor,
                    )
                }
                TvButton(
                    label = "Reset style",
                    onClick = actions.onResetSubtitleStyle,
                    style = ButtonStyle.Secondary,
                )
            }
        }
        state.showSpeedDialog -> TrackSheet("Playback speed", actions.onDismiss, modifier, closeFocusRequester) {
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
                    TvButton(
                        label = "${speed}×",
                        onClick = { actions.onSetPlaybackSpeed(speed) },
                        style = if (selected) ButtonStyle.Primary else ButtonStyle.Secondary,
                        fillMaxWidth = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackSheet(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    closeFocusRequester: FocusRequester,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f)), contentAlignment = Alignment.CenterStart) {
        // Fixed fraction height so weighted LazyColumns always get a real viewport. Shrink-wrapped
        // sheets made short lists unnavigable on TV (focus could not move/scroll inside the list).
        val sheetShape = RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius)
        Column(
            modifier = Modifier
                .padding(start = 48.dp)
                .width(740.dp)
                .fillMaxHeight(0.9f)
                .heightIn(max = 820.dp)
                .clip(sheetShape)
                .background(SlugYardPalette.Surface)
                .border(1.dp, SlugYardPalette.Divider, sheetShape)
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
                TvButton(
                    label = "Close",
                    onClick = onClose,
                    style = ButtonStyle.Secondary,
                    focusRequester = closeFocusRequester,
                )
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
        listFocusRequester.requestPlayerFocus()
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
                title = audioTrackUiLabel(track),
                detail = track.channelCount?.takeIf { it > 0 }?.let { "$it channels" },
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
        listFocusRequester.requestPlayerFocus()
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
                    TvButton(
                        label = if (choices.isEmpty()) "Search OpenSubtitles" else "Search again",
                        onClick = onRetrySubtitleSearch,
                        style = ButtonStyle.Secondary,
                        fillMaxWidth = true,
                    )
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
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
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
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
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
                    focused -> Color(0xFF141414)
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
                color = if (focused) Color(0xFF141414) else SlugYardPalette.Accent,
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

private fun subtitleCodecTag(codec: String?, sampleMimeType: String?, name: String): String =
    PlayerSubtitleUtils.classifySubtitleFormat(
        codecLabel = codec,
        sampleMimeType = sampleMimeType,
        trackTitle = name,
    ) ?: "Text"

private fun subtitleFormatTag(format: String?, url: String): String =
    PlayerSubtitleUtils.classifySubtitleFormat(
        url = url,
        declaredFormat = format,
    ) ?: "Text"

@Composable
private fun TrackRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> SlugYardPalette.SurfaceElevated
                    else -> SlugYardPalette.Canvas
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
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                focused -> Color(0xFF141414)
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
        TvButton(label = "−", onClick = onDecrease, style = ButtonStyle.Secondary)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvas)
        TvButton(label = "+", onClick = onIncrease, style = ButtonStyle.Secondary)
    }
}

@Composable
private fun TrackStatus(message: String) {
    Text(
        message,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius))
            .background(SlugYardPalette.Canvas)
            .padding(18.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = SlugYardPalette.OnCanvasMuted,
    )
}

private fun formatStyleDelay(delayMs: Int): String {
    val seconds = abs(delayMs) / 1000f
    val sign = when {
        delayMs > 0 -> "+"
        delayMs < 0 -> "-"
        else -> ""
    }
    return "${sign}${"%.1f".format(seconds)} s"
}
