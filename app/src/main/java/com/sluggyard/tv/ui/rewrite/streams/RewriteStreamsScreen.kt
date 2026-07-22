package com.sluggyard.tv.ui.rewrite.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics

data class RewriteStreamCandidate(
    val id: String,
    val title: String,
    val sourceLabel: String,
    val detailLabel: String?,
    val cacheState: StreamCacheState,
    val directUrl: String? = null,
    val infoHash: String? = null,
    val fileIndex: Int? = null,
)

sealed interface RewriteStreamGroupState {
    data object Loading : RewriteStreamGroupState
    data object Empty : RewriteStreamGroupState
    data class Error(val message: String) : RewriteStreamGroupState
    data class Content(val streams: List<RewriteStreamCandidate>) : RewriteStreamGroupState
}

data class RewriteStreamGroup(
    val addonId: String,
    val addonName: String,
    val state: RewriteStreamGroupState,
)

@Composable
fun RewriteStreamsScreen(
    title: String,
    groups: List<RewriteStreamGroup>,
    onStreamSelected: (RewriteStreamCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = SlugYardTvMetrics.ScreenVerticalInset,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item("title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choose a stream", style = MaterialTheme.typography.headlineLarge)
                Text(title, style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvasMuted)
            }
        }
        items(groups, key = RewriteStreamGroup::addonId) { group ->
            StreamGroup(group, onStreamSelected)
        }
    }
}

@Composable
private fun StreamGroup(group: RewriteStreamGroup, onStreamSelected: (RewriteStreamCandidate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(group.addonName, style = MaterialTheme.typography.titleLarge)
        when (val state = group.state) {
            RewriteStreamGroupState.Loading -> StreamStatusCard("Searching this source...", SlugYardPalette.OnCanvasMuted)
            RewriteStreamGroupState.Empty -> StreamStatusCard("No streams returned", SlugYardPalette.OnCanvasMuted)
            is RewriteStreamGroupState.Error -> StreamStatusCard(state.message, SlugYardPalette.Danger)
            is RewriteStreamGroupState.Content -> state.streams.forEach { stream ->
                StreamCandidateRow(stream, onClick = { onStreamSelected(stream) })
            }
        }
    }
}

@Composable
private fun StreamStatusCard(message: String, color: Color) {
    Text(
        message,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SlugYardPalette.Surface)
            .padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

@Composable
private fun StreamCandidateRow(stream: RewriteStreamCandidate, onClick: () -> Unit) {
    val focusState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { focusState.value = it.isFocused }
            .scale(if (focusState.value) SlugYardTvMetrics.FocusScale else 1f)
            .background(if (focusState.value) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stream.title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stream.sourceLabel, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted)
                stream.detailLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted) }
            }
        }
        Spacer(Modifier.width(18.dp))
        CacheBadge(stream.cacheState)
    }
}

@Composable
private fun CacheBadge(state: StreamCacheState) {
    val (label, color) = when (state) {
        StreamCacheState.CACHED -> "Instant" to SlugYardPalette.Accent
        StreamCacheState.NOT_CACHED -> "Download" to SlugYardPalette.OnCanvasMuted
        StreamCacheState.CHECKING -> "Checking" to SlugYardPalette.OnCanvasMuted
        StreamCacheState.UNKNOWN -> "Unknown" to SlugYardPalette.OnCanvasMuted
        StreamCacheState.NOT_APPLICABLE -> "" to Color.Transparent
    }
    if (label.isNotEmpty()) {
        Text(
            label,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
