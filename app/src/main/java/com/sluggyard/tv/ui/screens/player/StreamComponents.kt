@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.sluggyard.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.sluggyard.tv.core.streams.StreamBadgePlacement
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.ui.components.SourceChipItem
import com.sluggyard.tv.ui.components.SourceChipStatus
import com.sluggyard.tv.ui.components.SourceStatusFilterChip
import com.sluggyard.tv.ui.components.StreamBadgeChips
import com.sluggyard.tv.ui.theme.SlugYardTheme
import androidx.compose.ui.res.stringResource
import com.sluggyard.tv.R

@Composable
internal fun StreamItem(
    stream: Stream,
    focusRequester: FocusRequester,
    requestInitialFocus: Boolean,
    isCurrentStream: Boolean = false,
    showFileSizeBadges: Boolean = true,
    showAddonLogo: Boolean = true,
    badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM,
    onClick: () -> Unit,
    onUpKey: (() -> Unit)? = null,
    onLeftKey: (() -> Unit)? = null,
    onRightKey: (() -> Unit)? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val unknownStreamLabel = stringResource(R.string.stream_unknown)
    val streamName = remember(stream, unknownStreamLabel) { stream.getDisplayNameOrNull() ?: unknownStreamLabel }
    val streamDescription = remember(stream) { stream.getDisplayDescription() }
    val hasBadges = stream.badges.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints?.videoSize != null)
    // Pre-upscale: decode at 2× target pixels so the hardware compositor
    // has enough pixel data for smooth edges inside Card RenderNodes.
    val logoDecodeSize = remember(density) {
        with(density) { SlugYardTheme.spacing.xxl.roundToPx() } * 2
    }
    val addonLogoModel = remember(context, stream.addonLogo, logoDecodeSize) {
        stream.addonLogo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .size(width = logoDecodeSize, height = logoDecodeSize)
                .memoryCacheKey("${logo}_${logoDecodeSize}x${logoDecodeSize}")
                .crossfade(true)
                .build()
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestInitialFocus) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                leftFocusRequester?.let { left = it }
                rightFocusRequester?.let { right = it }
                upFocusRequester?.let { up = it }
            }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false

                when (event.key) {
                    Key.DirectionUp -> {
                        if (onUpKey != null && upFocusRequester == null) {
                            onUpKey()
                            true
                        } else false
                    }
                    Key.DirectionLeft -> {
                        if (onLeftKey != null && leftFocusRequester == null) {
                            onLeftKey()
                            true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (onRightKey != null && rightFocusRequester == null) {
                            onRightKey()
                            true
                        } else false
                    }
                    else -> false
                }
            },
        colors = CardDefaults.colors(
            containerColor = SlugYardTheme.colors.BackgroundElevated,
            focusedContainerColor = SlugYardTheme.colors.BackgroundElevated
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(SlugYardTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(
                    SlugYardTheme.spacing.hairline,
                    if (isCurrentStream) SlugYardTheme.colors.Primary.copy(alpha = 0.65f) else Color.Transparent
                ),
                shape = RoundedCornerShape(SlugYardTheme.radii.md)
            ),
            focusedBorder = Border(
                border = BorderStroke(SlugYardTheme.spacing.xxs, SlugYardTheme.colors.FocusRing),
                shape = RoundedCornerShape(SlugYardTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SlugYardTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlugYardTheme.spacing.lg)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SlugYardTheme.spacing.xs)
            ) {
                if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                    StreamBadgeChips(
                        badges = stream.badges,
                        fileSizeBytes = stream.behaviorHints?.videoSize,
                        showFileSizeBadge = showFileSizeBadges
                    )
                    Spacer(modifier = Modifier.height(SlugYardTheme.spacing.xxs))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlugYardTheme.spacing.sm)
                ) {
                    Text(
                        text = streamName,
                        style = MaterialTheme.typography.titleMedium,
                        color = SlugYardTheme.colors.TextPrimary
                    )

                    if (isCurrentStream) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(SlugYardTheme.colors.Primary.copy(alpha = 0.2f))
                                .padding(horizontal = SlugYardTheme.spacing.sm, vertical = SlugYardTheme.spacing.xs)
                        ) {
                            Text(
                                text = stringResource(R.string.sources_playing),
                                style = MaterialTheme.typography.labelSmall,
                                color = SlugYardTheme.colors.Primary
                            )
                        }
                    }
                }

                streamDescription?.let { description ->
                    if (description != streamName) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = SlugYardTheme.extendedColors.textSecondary
                        )
                    }
                }

                if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                    StreamBadgeChips(
                        badges = stream.badges,
                        fileSizeBytes = stream.behaviorHints?.videoSize,
                        showFileSizeBadge = showFileSizeBadges,
                        modifier = Modifier.padding(top = SlugYardTheme.spacing.xxs)
                    )
                }
            }

            if (showAddonLogo) {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    if (addonLogoModel != null) {
                        AsyncImage(
                            model = addonLogoModel,
                            contentDescription = stream.addonName,
                            modifier = Modifier
                                .size(SlugYardTheme.spacing.xxl)
                                .clip(RoundedCornerShape(SlugYardTheme.radii.xs)),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Spacer(modifier = Modifier.height(SlugYardTheme.spacing.xs))

                    Text(
                        text = stream.addonName,
                        style = MaterialTheme.typography.labelSmall,
                        color = SlugYardTheme.extendedColors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AddonFilterChips(
    addons: List<String>,
    sourceChips: List<SourceChipItem> = emptyList(),
    selectedAddon: String?,
    onAddonSelected: (String?) -> Unit,
    externalFocusRequesters: List<FocusRequester>? = null,
    externalOrderedNames: List<String>? = null
) {
    val chipMap = sourceChips.associateBy { it.name }
    val orderedNames = externalOrderedNames ?: buildList {
        addAll(addons)
        sourceChips.forEach { chip -> if (chip.name !in this) add(chip.name) }
    }
    val focusRequesters = externalFocusRequesters ?: remember(orderedNames.size) {
        List(orderedNames.size + 1) { FocusRequester() }
    }


    val selectedIndex = if (selectedAddon == null) 0 else (orderedNames.indexOf(selectedAddon) + 1).coerceAtLeast(0)
    var focusedChipIndex by remember { mutableStateOf(selectedIndex) }

    LaunchedEffect(selectedAddon, orderedNames) {
        val idx = if (selectedAddon == null) 0 else (orderedNames.indexOf(selectedAddon) + 1).coerceAtLeast(0)
        focusedChipIndex = idx
    }

    val lastKeyRepeatDispatchRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val allOptions = remember(orderedNames) { listOf<String?>(null) + orderedNames }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(SlugYardTheme.spacing.lg),
        contentPadding = PaddingValues(horizontal = SlugYardTheme.spacing.sm, vertical = SlugYardTheme.spacing.xs),
        modifier = Modifier
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false

                if (event.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatDispatchRef.get() < 112L) return@onKeyEvent true
                    lastKeyRepeatDispatchRef.set(now)
                }

                val currentIdx = focusedChipIndex.coerceIn(0, allOptions.lastIndex)
                when (event.key) {
                    Key.DirectionLeft -> {
                        val nextIdx = if (currentIdx > 0) currentIdx - 1 else allOptions.lastIndex
                        focusedChipIndex = nextIdx
                        try { focusRequesters[nextIdx].requestFocus() } catch (_: Exception) {}
                        true
                    }
                    Key.DirectionRight -> {
                        val nextIdx = if (currentIdx < allOptions.lastIndex) currentIdx + 1 else 0
                        focusedChipIndex = nextIdx
                        try { focusRequesters[nextIdx].requestFocus() } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }
    ) {
        item {
            SourceStatusFilterChip(
                name = stringResource(R.string.stream_filter_all),
                isSelected = selectedAddon == null,
                status = SourceChipStatus.SUCCESS,
                isSelectable = true,
                onClick = { onAddonSelected(null) },
                modifier = Modifier
                    .focusRequester(focusRequesters[0])
                    .onFocusChanged { 
                        if (it.isFocused) {
                            focusedChipIndex = 0
                            onAddonSelected(null)
                        }
                    }
            )
        }

        items(orderedNames.size) { i ->
            val addon = orderedNames[i]
            val chipStatus = chipMap[addon]?.status ?: SourceChipStatus.SUCCESS
            val isSelectable = addon in addons && chipStatus == SourceChipStatus.SUCCESS
            SourceStatusFilterChip(
                name = addon,
                isSelected = selectedAddon == addon,
                status = chipStatus,
                isSelectable = isSelectable,
                onClick = { onAddonSelected(addon) },
                modifier = Modifier
                    .focusRequester(focusRequesters[i + 1])
                    .onFocusChanged { 
                        if (it.isFocused) {
                            focusedChipIndex = i + 1
                            onAddonSelected(addon)
                        }
                    }
            )
        }
    }
}
