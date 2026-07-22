package com.sluggyard.tv.ui.rewrite.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.settings.shouldShowPosterLabel
import com.sluggyard.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.sluggyard.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class RewriteHomePoster(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val progressFraction: Float? = null,
    val addonId: String? = null,
    val contentType: String? = null,
)

data class RewriteHomeRow(
    val id: String,
    val title: String,
    val posters: List<RewriteHomePoster>,
)

data class RewriteHero(
    val id: String,
    val title: String,
    val backdropUrl: String?,
    val summary: String,
    val metadata: String,
    val addonId: String,
    val contentType: String,
)

data class RewriteHomeState(
    val hero: RewriteHero? = null,
    val rows: List<RewriteHomeRow> = emptyList(),
)

/**
 * TV-first Home composition for the rewritten shell.
 *
 * Data ownership and network fetching intentionally live outside this composable. That keeps
 * partial catalog results safe to render: a row can appear, update, or report an error without
 * resetting focus or rebuilding unrelated shelves.
 */
@Composable
fun RewriteHomeScreen(
    state: RewriteHomeState,
    onPlay: (RewriteHero) -> Unit,
    onDetails: (RewriteHero) -> Unit,
    onPosterSelected: (RewriteHomePoster) -> Unit,
    showPosterLabels: Boolean = true,
    smoothFocusMovement: Boolean = true,
    fastHorizontalNavigation: Boolean = false,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalFastHorizontalNavigationEnabled provides fastHorizontalNavigation) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(SlugYardPalette.Canvas)
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            state.hero?.let { hero ->
                item(key = "hero:${hero.id}") {
                    RewriteHeroPanel(hero = hero, onPlay = { onPlay(hero) }, onDetails = { onDetails(hero) })
                }
            }
            items(state.rows, key = RewriteHomeRow::id) { row ->
                RewritePosterRow(
                    row = row,
                    showPosterLabels = showPosterLabels,
                    smoothFocusMovement = smoothFocusMovement,
                    onPosterSelected = onPosterSelected,
                )
            }
        }
    }
}

@Composable
private fun RewriteHeroPanel(
    hero: RewriteHero,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
) {
    val playFocusRequester = remember(hero.id) { FocusRequester() }
    LaunchedEffect(hero.id) {
        // The root navigation receives Android's default focus after the first layout pass.
        // Hand off once that default-focus pass is complete so entering Home lands on Play.
        delay(180)
        playFocusRequester.requestFocus()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
            .background(SlugYardPalette.Surface),
    ) {
        AsyncImage(
            model = hero.backdropUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.64f,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(SlugYardPalette.Canvas, Color.Transparent, Color.Transparent),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = SlugYardTvMetrics.ScreenHorizontalInset,
                    end = SlugYardTvMetrics.ScreenHorizontalInset,
                    bottom = 44.dp,
                )
                .fillMaxWidth(0.52f),
        ) {
            Text(hero.title, style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                hero.metadata,
                style = MaterialTheme.typography.labelLarge,
                color = SlugYardPalette.OnCanvasMuted,
            )
            Spacer(Modifier.height(12.dp))
            Text(hero.summary, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroActionButton("Play", Icons.Default.PlayArrow, onPlay, primary = true, modifier = Modifier.focusRequester(playFocusRequester))
                HeroActionButton("More info", Icons.Default.Info, onDetails, primary = false)
            }
        }
    }
}

@Composable
private fun HeroActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SlugYardPalette.OnCanvas,
                contentColor = SlugYardPalette.Canvas,
            ),
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RewritePosterRow(
    row: RewriteHomeRow,
    showPosterLabels: Boolean,
    smoothFocusMovement: Boolean,
    onPosterSelected: (RewriteHomePoster) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = row.title,
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            horizontalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        ) {
            items(row.posters, key = RewriteHomePoster::id) { poster ->
                RewritePosterCard(
                    poster = poster,
                    showLabel = showPosterLabels,
                    smoothFocusMovement = smoothFocusMovement,
                    onClick = { onPosterSelected(poster) },
                )
            }
        }
    }
}

@Composable
private fun RewritePosterCard(
    poster: RewriteHomePoster,
    showLabel: Boolean,
    smoothFocusMovement: Boolean,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(
        targetValue = if (focused) SlugYardTvMetrics.FocusScale else 1f,
        label = "poster focus scale",
    )

    val focusMovementModifier = if (smoothFocusMovement) {
        Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                if (state.isFocused) scope.launch { bringIntoViewRequester.bringIntoView() }
            }
    } else {
        Modifier
    }

    Box(
        modifier = focusMovementModifier
            .size(width = 176.dp, height = 264.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 18f else 0f
            }
            .clip(RoundedCornerShape(SlugYardTvMetrics.CardCornerRadius))
            .background(SlugYardPalette.SurfaceElevated)
            .semantics {
                contentDescription = poster.title
                role = Role.Button
            }
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = poster.imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        poster.progressFraction?.let { progress ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(SlugYardPalette.Accent),
            )
        }
        if (shouldShowPosterLabel(showLabel, focused)) {
            Text(
                poster.title,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(10.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}
