package com.sluggyard.tv.ui.app.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared Netflix-style episode row used by player More Episodes and View Details.
 *
 * TV remotes: [clickable] already owns focus — do not stack a second `.focusable()`.
 * Long-press (mark watched) uses the same D-pad KeyDown timer path as Home posters.
 */
@Composable
fun NetflixEpisodeRow(
    episode: DetailsEpisode,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }
    val displayTitle = remember(episode.id, episode.title, episode.description, episode.displayNumber) {
        episode.resolvedDisplayTitle()
    }
    val thumbShape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(focusRequester?.let(Modifier::focusRequester) ?: Modifier)
            .focusProperties {
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            }
            .semantics {
                contentDescription = buildString {
                    append("Episode ${episode.displayNumber}: $displayTitle")
                    if (onLongClick != null) {
                        append(
                            if (episode.watched) {
                                ". Watched. Hold OK to mark unwatched."
                            } else {
                                ". Not watched. Hold OK to mark watched."
                            },
                        )
                    }
                }
                role = Role.Button
            }
            .then(
                if (onLongClick != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        val isSelectKey = event.key == Key.DirectionCenter ||
                            event.key == Key.Enter ||
                            event.key == Key.NumPadEnter
                        when {
                            event.type == KeyEventType.KeyDown && event.key == Key.Menu -> {
                                onLongClick()
                                true
                            }
                            isSelectKey && event.type == KeyEventType.KeyDown -> {
                                if (longPressJob == null) {
                                    longPressFired = false
                                    longPressJob = scope.launch {
                                        delay(750)
                                        longPressFired = true
                                        onLongClick()
                                    }
                                }
                                true
                            }
                            isSelectKey && event.type == KeyEventType.KeyUp -> {
                                longPressJob?.cancel()
                                longPressJob = null
                                if (longPressFired) {
                                    longPressFired = false
                                    true
                                } else {
                                    onClick()
                                    true
                                }
                            }
                            else -> false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(124.dp)
                .clip(thumbShape)
                .background(SlugYardPalette.SurfaceElevated)
                .then(
                    if (focused) {
                        Modifier.border(3.dp, Color.White, thumbShape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            AsyncImage(
                model = episode.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = episode.displayNumber.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (episode.watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(SlugYardPalette.Accent.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Watched", color = Color(0xFF181818), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.releaseLabel?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
            }
            episode.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
