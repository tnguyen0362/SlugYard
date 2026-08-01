package com.sluggyard.tv.ui.app.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.app.preferLargePosterUrl
import com.sluggyard.tv.ui.app.requestFocusReliably
import com.sluggyard.tv.ui.app.seasonDisplayLabel

enum class DetailsOverlay {
    None,
    Episodes,
    Related,
}

/** Prefer a real episode name over generic "Episode N" addon placeholders. */
internal fun DetailsEpisode.resolvedDisplayTitle(): String {
    val raw = title.trim()
    val generic = raw.isBlank() || raw.matches(Regex("""(?i)^episode\s*\d+$"""))
    if (!generic) return raw
    val fromDescription = description
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?.take(96)
    return fromDescription ?: raw.ifBlank { "Episode $displayNumber" }
}

@Composable
fun DetailsSecondaryOverlay(
    overlay: DetailsOverlay,
    state: DetailsState,
    related: List<DetailsRelatedPoster>,
    relatedLoading: Boolean,
    onDismiss: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onEpisodeSelected: (Int, DetailsEpisode) -> Unit,
    onEpisodeWatchedChanged: (DetailsEpisode, Boolean) -> Unit,
    onRelatedSelected: (DetailsRelatedPoster) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (overlay == DetailsOverlay.None) return

    // Close overlay only — does not pop the Details route.
    BackHandler(onBack = onDismiss)

    val selectedSeason = state.seasons.firstOrNull { it.number == state.selectedSeason }
        ?: state.seasons.firstOrNull()
    val firstFocusRequester = remember(overlay, selectedSeason?.number, related.map { it.id }) {
        FocusRequester()
    }
    val closeFocusRequester = remember(overlay) { FocusRequester() }
    val episodesListState = rememberLazyListState()
    val relatedFocusRequesters = remember(related.map { it.id }) {
        List(related.size) { FocusRequester() }
    }

    LaunchedEffect(overlay, selectedSeason?.number, related.size, relatedLoading) {
        when (overlay) {
            DetailsOverlay.Episodes -> {
                if (selectedSeason == null) {
                    closeFocusRequester.requestFocusReliably(retries = 8)
                } else {
                    episodesListState.scrollToItem(0)
                    kotlinx.coroutines.delay(32)
                    firstFocusRequester.requestFocusReliably(retries = 10)
                }
            }
            DetailsOverlay.Related -> {
                if (relatedLoading && related.isEmpty()) {
                    closeFocusRequester.requestFocusReliably(retries = 8)
                } else if (related.isEmpty()) {
                    closeFocusRequester.requestFocusReliably(retries = 8)
                } else {
                    kotlinx.coroutines.delay(32)
                    firstFocusRequester.requestFocusReliably(retries = 10)
                }
            }
            DetailsOverlay.None -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.78f)
                .padding(start = 40.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (overlay) {
                            DetailsOverlay.Episodes ->
                                seasonDisplayLabel(selectedSeason?.number ?: state.selectedSeason ?: 1)
                            DetailsOverlay.Related -> "More Like This"
                            DetailsOverlay.None -> ""
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = SlugYardPalette.OnCanvas,
                    )
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SlugYardPalette.OnCanvasMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocusRequester),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Close")
                }
            }

            when (overlay) {
                DetailsOverlay.Episodes -> {
                    if (selectedSeason == null) {
                        Text(
                            "No episodes are available for this title.",
                            color = SlugYardPalette.OnCanvasMuted,
                        )
                    } else {
                        if (state.seasons.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(state.seasons, key = DetailsSeason::number) { season ->
                                    FilterChip(
                                        selected = season.number == selectedSeason.number,
                                        onClick = { onSeasonSelected(season.number) },
                                        label = { Text(seasonDisplayLabel(season.number)) },
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${selectedSeason.episodes.size} Episodes",
                                style = MaterialTheme.typography.titleMedium,
                                color = SlugYardPalette.OnCanvasMuted,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { onMarkSeasonWatched(selectedSeason.number) },
                                enabled = !selectedSeason.fullyWatched,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(if (selectedSeason.fullyWatched) "Watched" else "Mark season watched")
                            }
                        }
                        Text(
                            "Hold OK on an episode to mark it watched or unwatched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SlugYardPalette.OnCanvasMuted,
                        )
                        LazyColumn(
                            state = episodesListState,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier
                                .weight(1f)
                                .focusGroup(),
                        ) {
                            itemsIndexed(
                                items = selectedSeason.episodes,
                                key = { index, episode -> "$index:${episode.id}" },
                            ) { index, episode ->
                                // Match player More Episodes: no manual up/down FocusRequesters.
                                // Explicit chains orphaned index-0 and trapped Up away from ep 1.
                                NetflixEpisodeRow(
                                    episode = episode,
                                    onClick = { onEpisodeSelected(selectedSeason.number, episode) },
                                    onLongClick = { onEpisodeWatchedChanged(episode, !episode.watched) },
                                    focusRequester = if (index == 0) firstFocusRequester else null,
                                )
                            }
                        }
                    }
                }
                DetailsOverlay.Related -> {
                    when {
                        relatedLoading && related.isEmpty() -> {
                            Text(
                                "Finding recommendations…",
                                color = SlugYardPalette.OnCanvasMuted,
                            )
                        }
                        related.isEmpty() -> {
                            Text(
                                "No recommendations are available for this title.",
                                color = SlugYardPalette.OnCanvasMuted,
                            )
                        }
                        else -> {
                            val imageContext = LocalContext.current
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(end = 12.dp),
                            ) {
                                itemsIndexed(related, key = { _, poster -> poster.id }) { index, poster ->
                                    var focused by remember(poster.id) { mutableStateOf(false) }
                                    val posterModel = remember(poster.imageUrl) {
                                        preferLargePosterUrl(poster.imageUrl)?.let { url ->
                                            ImageRequest.Builder(imageContext)
                                                .data(url)
                                                .size(Size(280, 420))
                                                .build()
                                        }
                                    }
                                    Column(
                                        modifier = Modifier
                                            .width(140.dp)
                                            .then(
                                                if (index == 0) {
                                                    Modifier.focusRequester(firstFocusRequester)
                                                } else {
                                                    Modifier.focusRequester(relatedFocusRequesters[index])
                                                },
                                            )
                                            .focusProperties {
                                                when {
                                                    index == 0 -> Unit
                                                    index == 1 -> left = firstFocusRequester
                                                    else ->
                                                        relatedFocusRequesters.getOrNull(index - 1)?.let { left = it }
                                                }
                                                relatedFocusRequesters.getOrNull(index + 1)?.let { right = it }
                                            }
                                            .onFocusChanged { focused = it.isFocused }
                                            .focusable()
                                            // TV remotes often do not fire clickable() for Center/Enter
                                            // when focusable() is applied separately — handle Select here.
                                            .onPreviewKeyEvent { event ->
                                                val isSelect = event.key == Key.DirectionCenter ||
                                                    event.key == Key.Enter ||
                                                    event.key == Key.NumPadEnter
                                                if (isSelect && event.type == KeyEventType.KeyUp) {
                                                    onRelatedSelected(poster)
                                                    true
                                                } else {
                                                    isSelect && event.type == KeyEventType.KeyDown
                                                }
                                            }
                                            .clickable { onRelatedSelected(poster) },
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(210.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(SlugYardPalette.SurfaceElevated)
                                                .then(
                                                    if (focused) {
                                                        Modifier.border(3.dp, SlugYardPalette.FocusRing, RoundedCornerShape(6.dp))
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                        ) {
                                            if (posterModel != null) {
                                                AsyncImage(
                                                    model = posterModel,
                                                    contentDescription = poster.title,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop,
                                                )
                                            }
                                        }
                                        Text(
                                            poster.title,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = SlugYardPalette.OnCanvas,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                DetailsOverlay.None -> Unit
            }
        }
    }
}
