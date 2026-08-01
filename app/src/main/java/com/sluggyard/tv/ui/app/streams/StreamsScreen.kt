package com.sluggyard.tv.ui.app.streams

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.domain.model.StreamBadge
import com.sluggyard.tv.ui.components.StreamBadgeChips
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.app.requestFocusReliably

data class StreamCandidate(
    val id: String,
    val title: String,
    val sourceLabel: String,
    val detailLabel: String?,
    val cacheState: StreamCacheState,
    val directUrl: String? = null,
    val infoHash: String? = null,
    val fileIndex: Int? = null,
    val metadataText: String? = null,
    val videoSizeBytes: Long? = null,
    val seeders: Int? = null,
    val filename: String? = null,
    val streamDescription: String? = null,
    val bingeGroup: String? = null,
    val videoHash: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val trackers: List<String> = emptyList(),
    val badges: List<StreamBadge> = emptyList(),
)

sealed interface StreamGroupState {
    data object Loading : StreamGroupState
    data object Empty : StreamGroupState
    data class Error(val message: String) : StreamGroupState
    data class Content(val streams: List<StreamCandidate>) : StreamGroupState
}

data class StreamGroup(
    val addonId: String,
    val addonName: String,
    val state: StreamGroupState,
)

private sealed interface StreamListItem {
    val key: String

    data class Header(val title: String) : StreamListItem {
        override val key: String = "header:$title"
    }

    data class Status(val message: String, val color: Color) : StreamListItem {
        override val key: String = "status:$message"
    }

    data class Candidate(val stream: StreamCandidate) : StreamListItem {
        override val key: String = "candidate:${stream.id}"
    }
}

@Composable
fun StreamsScreen(
    title: String,
    groups: List<StreamGroup>,
    onStreamSelected: (StreamCandidate) -> Unit,
    onBack: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true, onBack = onBack)
    val backFocusRequester = remember { FocusRequester() }
    val listItems = buildList<StreamListItem> {
        add(StreamListItem.Header(title))
        groups.forEach { group ->
            add(StreamListItem.Header(group.addonName))
            when (val state = group.state) {
                StreamGroupState.Loading -> add(StreamListItem.Status("Searching this source...", SlugYardPalette.OnCanvasMuted))
                StreamGroupState.Empty -> add(StreamListItem.Status("No streams returned", SlugYardPalette.OnCanvasMuted))
                is StreamGroupState.Error -> add(StreamListItem.Status(state.message, SlugYardPalette.Danger))
                is StreamGroupState.Content -> state.streams.forEach { stream ->
                    add(StreamListItem.Candidate(stream))
                }
            }
        }
    }
    val firstStreamId = firstStreamId(groups)
    val candidateIds = listItems.filterIsInstance<StreamListItem.Candidate>().map { it.stream.id }
    val candidateFocusRequesters = remember(candidateIds, contentFocusRequester) {
        candidateIds.mapIndexed { index, _ ->
            if (index == 0) contentFocusRequester ?: FocusRequester() else FocusRequester()
        }
    }
    LaunchedEffect(contentFocusRequester, firstStreamId, candidateIds) {
        when {
            firstStreamId != null && contentFocusRequester != null ->
                contentFocusRequester.requestFocusReliably(retries = 8)
            firstStreamId != null ->
                candidateFocusRequesters.firstOrNull()?.requestFocusReliably(retries = 8)
            else ->
                backFocusRequester.requestFocusReliably(retries = 8)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                vertical = SlugYardTvMetrics.ScreenVerticalInset,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var candidateIndex = 0
        listItems.forEach { item ->
            when (item) {
                is StreamListItem.Header -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (item.key == "header:$title") "Choose a stream" else item.title,
                            style = if (item.key == "header:$title") MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(top = if (item.key == "header:$title") 8.dp else 22.dp),
                        )
                        if (item.key == "header:$title") {
                            TvButton(
                                label = "Back",
                                onClick = onBack,
                                style = ButtonStyle.Secondary,
                                focusRequester = backFocusRequester,
                            )
                        }
                    }
                    if (item.key == "header:$title") {
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvasMuted)
                    }
                }
                is StreamListItem.Status -> StreamStatusCard(item.message, item.color)
                is StreamListItem.Candidate -> StreamCandidateRow(
                    stream = item.stream,
                    onClick = { onStreamSelected(item.stream) },
                    focusRequester = candidateFocusRequesters[candidateIndex],
                    upFocusRequester = if (candidateIndex == 0) backFocusRequester else candidateFocusRequesters.getOrNull(candidateIndex - 1),
                    downFocusRequester = candidateFocusRequesters.getOrNull(candidateIndex + 1),
                )
            }
            if (item is StreamListItem.Candidate) candidateIndex++
        }
    }
}

private fun firstStreamId(groups: List<StreamGroup>): String? = groups
    .asSequence()
    .mapNotNull { it.state as? StreamGroupState.Content }
    .flatMap { it.streams.asSequence() }
    .firstOrNull()
    ?.id

@Composable
private fun StreamStatusCard(message: String, color: Color) {
    Text(
        message,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius))
            .background(SlugYardPalette.Surface)
            .padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

@Composable
private fun StreamCandidateRow(
    stream: StreamCandidate,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
) {
    val focusState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties {
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            }
            .onFocusChanged { focusState.value = it.isFocused }
            .scale(if (focusState.value) SlugYardTvMetrics.FocusScale else 1f)
            .background(if (focusState.value) Color.White.copy(alpha = 0.10f) else SlugYardPalette.Surface)
            .then(
                if (focusState.value) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stream.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            val detail = StreamPresentation.detailLine(stream)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted)
            }
            if (stream.badges.isNotEmpty() || stream.videoSizeBytes != null) {
                StreamBadgeChips(
                    badges = stream.badges,
                    fileSizeBytes = stream.videoSizeBytes,
                    showFileSizeBadge = stream.videoSizeBytes != null,
                    focused = focusState.value,
                )
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
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}
