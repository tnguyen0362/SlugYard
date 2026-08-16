package com.sluggyard.tv.ui.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.app.streams.StreamLinkCodecFilter
import com.sluggyard.tv.ui.app.streams.StreamLinkFilterSettings
import com.sluggyard.tv.ui.app.streams.StreamLinkMaxResolution
import com.sluggyard.tv.ui.app.streams.StreamLinkSizeGbOptions
import com.sluggyard.tv.ui.app.streams.sizeGbLabel
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import kotlinx.coroutines.launch

@Composable
internal fun SourcesSettings(facade: SettingsFacade) {
    val scope = rememberCoroutineScope()
    val filters by facade.streamLinkFilterSettings.collectAsState(initial = StreamLinkFilterSettings())
    SettingsGroup("Link filters") {
        Text(
            "Applies to Sources and auto-play / Finding. CAM, 3D, and VR are always hidden.",
            style = MaterialTheme.typography.bodyMedium,
            color = SlugYardPalette.OnCanvasMuted,
        )
        CycleRow(
            "Max resolution",
            filters.maxResolution.label,
            StreamLinkMaxResolution.entries.map { it.label },
        ) { value ->
            scope.launch {
                facade.setStreamLinkMaxResolution(
                    StreamLinkMaxResolution.entries.first { it.label == value },
                )
            }
        }
        CycleRow(
            "Max file size",
            filters.maxSizeGb.sizeGbLabel(),
            StreamLinkSizeGbOptions.map { it.sizeGbLabel() },
        ) { value ->
            scope.launch {
                val gb = StreamLinkSizeGbOptions.first { it.sizeGbLabel() == value }
                facade.setStreamLinkMaxSizeGb(gb)
            }
        }
        ToggleRow(
            "Allow remux",
            "Include BluRay REMUX / BDRemux packs in Sources and Finding.",
            filters.allowRemux,
        ) { scope.launch { facade.setStreamLinkAllowRemux(it) } }
        ToggleRow(
            "Allow Dolby Vision",
            "Include DV / DoVi packs. Profile 8.6 stays blocked for playback.",
            filters.allowDolbyVision,
        ) { scope.launch { facade.setStreamLinkAllowDolbyVision(it) } }
    }
    SettingsGroup("Codec") {
        Text(
            "Require a video codec when the release tags one. Untagged rows stay visible.",
            style = MaterialTheme.typography.bodyMedium,
            color = SlugYardPalette.OnCanvasMuted,
        )
        CodecBubbleRow(
            selected = filters.codec,
            onSelect = { codec -> scope.launch { facade.setStreamLinkCodec(codec) } },
        )
    }
}

@Composable
private fun CodecBubbleRow(
    selected: StreamLinkCodecFilter,
    onSelect: (StreamLinkCodecFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamLinkCodecFilter.entries.forEach { codec ->
            CodecChip(
                label = codec.label,
                selected = selected == codec,
                onClick = { onSelect(codec) },
            )
        }
    }
}

@Composable
private fun CodecChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius)
    Box(
        modifier = Modifier
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.12f)
                    selected -> Color.White.copy(alpha = 0.08f)
                    else -> SlugYardPalette.SurfaceElevated
                },
                shape,
            )
            .border(
                width = SlugYardTvMetrics.FocusRingWidth,
                color = when {
                    focused -> SlugYardPalette.FocusRing
                    selected -> SlugYardPalette.Accent.copy(alpha = 0.65f)
                    else -> SlugYardPalette.Divider
                },
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = SlugYardPalette.OnCanvas,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
