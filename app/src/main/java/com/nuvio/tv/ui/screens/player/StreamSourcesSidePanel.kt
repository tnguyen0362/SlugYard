@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
internal fun StreamSourcesSidePanel(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    modifier: Modifier = Modifier
) {
    var listHasFocus by remember { mutableStateOf(false) }
    var listHadFocusBeforeLoading by remember { mutableStateOf(true) }

    // Request focus when loading finishes OR when the list content updates
    // (ensures higher-priority addons get focus if they load later)
    LaunchedEffect(uiState.isLoadingSourceStreams, uiState.sourceFilteredStreams.size) {
        if (!uiState.isLoadingSourceStreams && uiState.sourceFilteredStreams.isNotEmpty()) {
            if (listHadFocusBeforeLoading) {
                repeat(2) { withFrameNanos { } }
                try {
                    streamsFocusRequester.requestFocus()
                } catch (_: Exception) {}
                listHadFocusBeforeLoading = false
            }
        }
    }

    val orderedAddonNames = remember(uiState.sourceAvailableAddons, uiState.sourceChips) {
        buildList {
            addAll(uiState.sourceAvailableAddons)
            uiState.sourceChips.forEach { if (it.name !in this) add(it.name) }
        }
    }
    val chipFocusRequesters = remember(orderedAddonNames.size) {
        List(orderedAddonNames.size + 1) { FocusRequester() }
    }

    LaunchedEffect(uiState.isLoadingSourceStreams, uiState.sourceFilteredStreams.size) {
        if (!uiState.isLoadingSourceStreams) {
            try {
                val selectedIdx = if (uiState.sourceSelectedAddonFilter == null) 0
                else (orderedAddonNames.indexOf(uiState.sourceSelectedAddonFilter) + 1).coerceAtLeast(0)
                if (selectedIdx < chipFocusRequesters.size) {
                    chipFocusRequesters[selectedIdx].requestFocus()
                }
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
            .background(NuvioTheme.colors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sources_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    DialogButton(
                        text = stringResource(R.string.sources_reload),
                        onClick = onReload,
                        isPrimary = false
                    )
                    DialogButton(
                        text = stringResource(R.string.sources_close),
                        onClick = onClose,
                        isPrimary = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            // Current content info
            val seasonEpisodeCode = if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                stringResource(
                    R.string.season_episode_format,
                    uiState.currentSeason,
                    uiState.currentEpisode
                )
            } else {
                null
            }
            Text(
                text = buildString {
                    if (seasonEpisodeCode != null) {
                        append(seasonEpisodeCode)
                        if (!uiState.currentEpisodeTitle.isNullOrBlank()) {
                            append(" • ${uiState.currentEpisodeTitle}")
                        }
                    } else {
                        append(uiState.title)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            AnimatedVisibility(
                visible = uiState.sourceChips.isNotEmpty() ||
                    (!uiState.isLoadingSourceStreams && uiState.sourceAvailableAddons.isNotEmpty()),
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                AddonFilterChips(
                    addons = uiState.sourceAvailableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddon = uiState.sourceSelectedAddonFilter,
                    onAddonSelected = onAddonFilterSelected,
                    externalFocusRequesters = chipFocusRequesters,
                    externalOrderedNames = orderedAddonNames
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            when {
                uiState.isLoadingSourceStreams -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NuvioTheme.spacing.xl),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                uiState.sourceStreamsError != null -> {
                    Text(
                        text = uiState.sourceStreamsError ?: stringResource(R.string.panel_failed_load_streams),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                uiState.sourceFilteredStreams.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.sources_no_streams),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                else -> {
                    val currentStreamIndex = findCurrentStreamIndex(
                        streams = uiState.sourceFilteredStreams,
                        currentStreamInfoHash = uiState.currentStreamInfoHash,
                        currentStreamFileIdx = uiState.currentStreamFileIdx,
                        currentStreamAddonName = uiState.currentStreamAddonName,
                        currentStreamUrl = uiState.currentStreamUrl,
                        currentStreamName = uiState.currentStreamName
                    )
                    val initialFocusStream = uiState.sourceFilteredStreams.getOrNull(currentStreamIndex)
                        ?: uiState.sourceFilteredStreams.firstOrNull()

                    val streamKeys = remember(uiState.sourceFilteredStreams) {
                        val seen = mutableMapOf<String, Int>()
                        uiState.sourceFilteredStreams.map { stream ->
                            val base = stream.stableKey(0)
                            val count = seen.getOrDefault(base, 0)
                            seen[base] = count + 1
                            stream.stableKey(count)
                        }
                    }

                    LaunchedEffect(uiState.sourceSelectedAddonFilter) {
                        if (listHasFocus && uiState.sourceFilteredStreams.isNotEmpty()) {
                            repeat(2) { withFrameNanos { } }
                            try {
                                streamsFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    }

                    val allOptions = listOf<String?>(null) + orderedAddonNames
                    val currentIdx = allOptions.indexOf(uiState.sourceSelectedAddonFilter).coerceAtLeast(0)

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        contentPadding = PaddingValues(
                            start = NuvioTheme.spacing.sm,
                            top = 14.dp,
                            end = NuvioTheme.spacing.sm,
                            bottom = NuvioTheme.spacing.sm
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .onFocusChanged { 
                                listHasFocus = it.hasFocus
                                if (it.hasFocus) {
                                    listHadFocusBeforeLoading = true
                                } else if (!uiState.isLoadingSourceStreams) {
                                    listHadFocusBeforeLoading = false
                                }
                            }
                    ) {
                        itemsIndexed(uiState.sourceFilteredStreams, key = { index, _ ->
                            streamKeys.getOrElse(index) { index.toString() }
                        }) { index, stream ->
                            StreamItem(
                                stream = stream,
                                focusRequester = streamsFocusRequester,
                                requestInitialFocus = stream == initialFocusStream,
                                isCurrentStream = index == currentStreamIndex,
                                showFileSizeBadges = uiState.showFileSizeBadges,
                                showAddonLogo = uiState.showAddonLogo,
                                badgePlacement = uiState.streamBadgePlacement,
                                onClick = { onStreamSelected(stream) },
                                onUpKey = if (index == 0 && chipFocusRequesters.isNotEmpty()) {
                                    {
                                        if (currentIdx >= 0 && currentIdx < chipFocusRequesters.size) {
                                            try { chipFocusRequesters[currentIdx].requestFocus() } catch (_: Exception) {}
                                        }
                                    }
                                } else null,
                                onLeftKey = {
                                    val nextIdx = if (currentIdx > 0) currentIdx - 1 else allOptions.lastIndex
                                    try { chipFocusRequesters[nextIdx].requestFocus() } catch (_: Exception) {}
                                },
                                onRightKey = {
                                    val nextIdx = if (currentIdx < allOptions.lastIndex) currentIdx + 1 else 0
                                    try { chipFocusRequesters[nextIdx].requestFocus() } catch (_: Exception) {}
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun findCurrentStreamIndex(
    streams: List<Stream>,
    currentStreamInfoHash: String?,
    currentStreamFileIdx: Int?,
    currentStreamAddonName: String?,
    currentStreamUrl: String?,
    currentStreamName: String?
): Int {
    if (streams.isEmpty()) return -1

    // Strategy 1: match by infoHash + fileIdx + addonName (most precise for debrid streams)
    if (!currentStreamInfoHash.isNullOrBlank()) {
        val hashMatch = streams.indexOfFirst { stream ->
            val streamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash
            val streamFileIdx = stream.fileIdx ?: stream.clientResolve?.fileIdx
            streamInfoHash.equals(currentStreamInfoHash, ignoreCase = true) &&
                (currentStreamFileIdx == null || streamFileIdx == currentStreamFileIdx) &&
                (currentStreamAddonName == null || stream.addonName == currentStreamAddonName)
        }
        if (hashMatch >= 0) return hashMatch
    }

    // Strategy 2: match by addon + URL (works for non-debrid HTTP streams)
    if (!currentStreamUrl.isNullOrBlank() && !currentStreamAddonName.isNullOrBlank()) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.addonName == currentStreamAddonName &&
                stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    // Fallback: match by URL only (without addon filter)
    if (!currentStreamUrl.isNullOrBlank()) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    return -1
}
