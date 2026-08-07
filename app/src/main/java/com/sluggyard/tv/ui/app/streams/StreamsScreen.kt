package com.sluggyard.tv.ui.app.streams

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.domain.model.StreamBadge
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.app.requestFocusReliably
import com.sluggyard.tv.ui.components.StreamBadgeChips
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics

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

    data class AddonHeader(val title: String) : StreamListItem {
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
    val context = LocalContext.current
    val backFocusRequester = remember { FocusRequester() }
    val localFirstFocusRequester = remember { FocusRequester() }
    val firstCandidateFocusRequester = contentFocusRequester ?: localFirstFocusRequester
    val listState = rememberLazyListState()
    var seededInitialFocus by remember(title) { mutableStateOf(false) }

    val listItems = remember(groups) {
        buildList {
            groups.forEach { group ->
                add(StreamListItem.AddonHeader(group.addonName))
                when (val state = group.state) {
                    StreamGroupState.Loading ->
                        add(StreamListItem.Status("Searching this source...",SlugYardPalette.OnCanvasMuted))
                    StreamGroupState.Empty ->
                        add(StreamListItem.Status("No streams returned", SlugYardPalette.OnCanvasMuted))
                    is StreamGroupState.Error ->
                        add(StreamListItem.Status(state.message, SlugYardPalette.Danger))
                    is StreamGroupState.Content ->
                        state.streams.forEach { stream -> add(StreamListItem.Candidate(stream)) }
                }
            }
        }
    }
    val firstCandidateIndex = listItems.indexOfFirst { it is StreamListItem.Candidate }
    val hasCandidates = firstCandidateIndex >= 0

    LaunchedEffect(hasCandidates, seededInitialFocus) {
        if (seededInitialFocus) return@LaunchedEffect
        if (hasCandidates) {
            if (firstCandidateFocusRequester.requestFocusReliably(retries = 8)) {
                seededInitialFocus = true
                runCatching { listState.scrollToItem(firstCandidateIndex.coerceAtLeast(0)) }
            }
        } else if (backFocusRequester.requestFocusReliably(retries = 8)) {
            seededInitialFocus = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .padding(
                horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                vertical = SlugYardTvMetrics.ScreenVerticalInset,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Choose a stream",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            )
            TvButton(
                label = "Back",
                onClick = onBack,
                style = ButtonStyle.Secondary,
                focusRequester = backFocusRequester,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = SlugYardPalette.OnCanvasMuted,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = listItems,
                key = { index, item -> "${item.key}#$index" },
            ) { index, item ->
                when (item) {
                    is StreamListItem.AddonHeader -> {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    is StreamListItem.Status -> StreamStatusCard(item.message, item.color)
                    is StreamListItem.Candidate -> {
                        // Identical pattern to CloudManager rows (known-working TV clicks).
                        StreamCandidateRow(
                            stream = item.stream,
                            focusRequester = if (index == firstCandidateIndex) {
                                firstCandidateFocusRequester
                            } else {
                                null
                            },
                            onClick = {
                                // Log.e survives release minify (Log.i is stripped by optimize rules).
                                android.util.Log.e(
                                    "SlugYardManualResolve",
                                    "CLICK id=${item.stream.id} src=${item.stream.sourceLabel} " +
                                        "cache=${item.stream.cacheState} hash=${!item.stream.infoHash.isNullOrBlank()}",
                                )
                                Toast.makeText(context, "Resolving source…", Toast.LENGTH_SHORT).show()
                                onStreamSelected(item.stream)
                            },
                        )
                    }
                }
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
            .clip(RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius))
            .background(SlugYardPalette.Surface)
            .padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

/**
 * Match CloudManager file rows exactly: clickable owns focus, no stacked focusable(),
 * no custom Key handlers that can swallow DPAD_CENTER.
 */
@Composable
private fun StreamCandidateRow(
    stream: StreamCandidate,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember(stream.id) { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.10f) else SlugYardPalette.Surface)
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = buildString {
                    append(stream.title)
                    StreamPresentation.detailLine(stream).takeIf { it.isNotBlank() }?.let {
                        append(". ").append(it)
                    }
                    append(". ").append(StreamPresentation.cacheLabel(stream.cacheState) ?: "Source")
                }
                role = Role.Button
            }
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
                    focused = focused,
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
                .background(color.copy(alpha = 0.18f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
