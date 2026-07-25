package com.sluggyard.tv.ui.rewrite.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.requestFocusReliably
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
    // Threaded through to playback (Streams -> Player -> saved checkpoint) so a hero Play tap
    // that skips Details entirely still saves Continue Watching art instead of leaving the
    // checkpoint's posterUrl permanently null. See RewriteAppShell's Streams(...) construction.
    val posterUrl: String? = null,
    val summary: String,
    val contextTag: String,
    val descriptorTag: String? = null,
    val addonId: String,
    val contentType: String,
)

data class RewriteHomeState(
    val hero: RewriteHero? = null,
    val rows: List<RewriteHomeRow> = emptyList(),
)

@OptIn(ExperimentalFoundationApi::class)
private val RewriteHomeBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            trailingEdge <= 0f -> offset
            offset >= containerSize -> trailingEdge - containerSize
            else -> 0f
        }
    }
}

/**
 * TV-first Home composition for the rewritten shell.
 *
 * Data ownership and network fetching intentionally live outside this composable. That keeps
 * partial catalog results safe to render: a row can appear, update, or report an error without
 * resetting focus or rebuilding unrelated shelves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RewriteHomeScreen(
    state: RewriteHomeState,
    onPlay: (RewriteHero) -> Unit,
    onDetails: (RewriteHero) -> Unit,
    onPosterSelected: (RewriteHomePoster) -> Unit,
    showPosterLabels: Boolean = true,
    smoothFocusMovement: Boolean = true,
    fastHorizontalNavigation: Boolean = false,
    // Lets the root nav header's DPAD_DOWN focus search land directly on the hero's Play button
    // instead of going nowhere. See RewriteAppShell's homeContentFocusRequester for why this
    // exists.
    contentFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // One FocusRequester per row, attached to that row's first (leftmost) poster card, so DPAD
    // up/down between rows is an explicit hop instead of relying on Compose's geometric focus
    // search. That search only considers currently-composed nodes, and LazyColumn decomposes
    // rows once they scroll far enough out of the viewport -- so after scrolling down to row 2+,
    // pressing UP had nothing to land on in row 1 (it was no longer composed) and Compose's
    // search kept walking up the tree until it found the hero's Play button, skipping every row
    // in between. Explicit neighbors fix this the same way the header-to-hero DOWN bug was fixed.
    val rowEntryFocusRequesters = remember(state.rows.map { it.id }) {
        List(state.rows.size) { FocusRequester() }
    }
    // Shared with dpadRepeatThrottle() below: whichever hero/row item currently has focus
    // registers its up/down FocusRequester here (see DpadVerticalNeighbors' class doc for why
    // Compose's default focus search can't be trusted to route between rows on its own).
    val dpadVerticalNeighbors = remember { com.sluggyard.tv.ui.util.DpadVerticalNeighbors() }
    CompositionLocalProvider(
        LocalFastHorizontalNavigationEnabled provides fastHorizontalNavigation,
        com.sluggyard.tv.ui.util.LocalDpadVerticalNeighbors provides dpadVerticalNeighbors,
        // NOTE: the custom RewriteHomeBringIntoViewSpec is intentionally NOT provided here at the
        // top level anymore. It only suppresses re-scrolling for the hero's internal Play/More
        // info focus changes (scoped locally inside RewriteHeroPanel below) -- applying it to the
        // whole LazyColumn also broke vertical scrolling for the poster rows, since its
        // calculateScrollDistance only scrolls when a focused item is *fully* outside the
        // viewport, ignoring rows that are only partially clipped at the screen edge. That left
        // the last/second-to-last row stuck: pressing DOWN further did nothing because Compose
        // considered it "already in view." The rest of this LazyColumn now uses Compose's default
        // BringIntoViewSpec, which always fully reveals the focused item on a single press.
    ) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .background(SlugYardPalette.Canvas)
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            state.hero?.let { hero ->
                item(key = "hero:${hero.id}") {
                    RewriteHeroPanel(
                        hero = hero,
                        listState = listState,
                        onPlay = { onPlay(hero) },
                        onDetails = { onDetails(hero) },
                        contentFocusRequester = contentFocusRequester,
                        downNeighbor = rowEntryFocusRequesters.getOrNull(0),
                    )
                }
            }
            itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                RewritePosterRow(
                    row = row,
                    showPosterLabels = showPosterLabels,
                    smoothFocusMovement = smoothFocusMovement,
                    onPosterSelected = onPosterSelected,
                    entryFocusRequester = rowEntryFocusRequesters[index],
                    upNeighbor = if (index == 0) contentFocusRequester else rowEntryFocusRequesters[index - 1],
                    downNeighbor = rowEntryFocusRequesters.getOrNull(index + 1),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RewriteHeroPanel(
    hero: RewriteHero,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    downNeighbor: FocusRequester? = null,
) {
    val dpadVerticalNeighbors = com.sluggyard.tv.ui.util.LocalDpadVerticalNeighbors.current
    val playFocusRequester = remember(hero.id) { FocusRequester() }
    var isPlayFocused by remember { mutableStateOf(false) }
    // Reactive on (isPlayFocused, downNeighbor) rather than a one-shot onFocusChanged write:
    // downNeighbor starts null (catalog rows haven't loaded yet when the hero first gains
    // focus) and only becomes non-null once RewriteHomeState.rows arrives asynchronously. A
    // plain onFocusChanged edge-trigger would capture that initial null in its closure and
    // never update once rows populate, since the Play button doesn't lose/regain focus in
    // between -- leaving DOWN from the hero with a stale null neighbor and falling back to
    // Compose's unreliable default focus search.
    LaunchedEffect(isPlayFocused, downNeighbor) {
        if (isPlayFocused) {
            dpadVerticalNeighbors.up = null
            dpadVerticalNeighbors.down = downNeighbor
        }
    }
    LaunchedEffect(hero.id) {
        listState.scrollToItem(0)
        playFocusRequester.requestFocusReliably()
        // requestFocus() triggers Compose's automatic scroll-focused-element-into-view behavior,
        // which animates over several frames and otherwise wins the race against the line above,
        // leaving the list scrolled down enough to push the hero title under the floating header.
        // Re-assert top-of-list once that animation has had time to run its course.
        delay(300)
        listState.scrollToItem(0)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Tall enough that the bottom-aligned title/pills/summary/buttons column never
            // overflows above the box's own top edge (Compose doesn't clip an
            // Alignment.BottomStart child to its parent's bounds by default, so an undersized box
            // here renders the title off the top of the screen) while still leaving the Play
            // button on-screen without needing a scroll on initial load.
            .height(520.dp)
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
            Text(
                hero.title,
                style = MaterialTheme.typography.displayLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroBadge(hero.contextTag)
                hero.descriptorTag?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = SlugYardPalette.OnCanvasMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                hero.summary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(22.dp))
            // Scoped locally: only the hero's Play/More info buttons should suppress the
            // list's bring-into-view scroll when focus moves between them (they're already
            // fully visible). See the comment on RewriteHomeScreen's CompositionLocalProvider
            // for why this can no longer be provided for the whole LazyColumn.
            CompositionLocalProvider(LocalBringIntoViewSpec provides RewriteHomeBringIntoViewSpec) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroActionButton(
                        "Play",
                        Icons.Default.PlayArrow,
                        onPlay,
                        primary = true,
                        modifier = Modifier
                            .focusRequester(playFocusRequester)
                            .let { if (contentFocusRequester != null) it.focusRequester(contentFocusRequester) else it }
                            .onFocusChanged { state -> isPlayFocused = state.isFocused },
                    )
                    HeroActionButton("More info", Icons.Default.Info, onDetails, primary = false)
                }
            }
        }
    }
}

@Composable
private fun HeroBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(SlugYardPalette.Accent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF181818),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
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
    entryFocusRequester: FocusRequester? = null,
    upNeighbor: FocusRequester? = null,
    downNeighbor: FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = row.title,
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            horizontalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        ) {
            itemsIndexed(row.posters, key = { _, poster -> poster.id }) { index, poster ->
                RewritePosterCard(
                    poster = poster,
                    showLabel = showPosterLabels,
                    smoothFocusMovement = smoothFocusMovement,
                    onClick = { onPosterSelected(poster) },
                    // Only the row's leftmost card is a cross-row DPAD up/down anchor -- that's
                    // the card that ends up focused after horizontal scroll resets on row entry.
                    focusRequester = if (index == 0) entryFocusRequester else null,
                    upNeighbor = if (index == 0) upNeighbor else null,
                    downNeighbor = if (index == 0) downNeighbor else null,
                )
            }
        }
    }
}

@Composable
internal fun RewritePosterCard(
    poster: RewriteHomePoster,
    showLabel: Boolean,
    smoothFocusMovement: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upNeighbor: FocusRequester? = null,
    downNeighbor: FocusRequester? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val dpadVerticalNeighbors = com.sluggyard.tv.ui.util.LocalDpadVerticalNeighbors.current
    val scale by animateFloatAsState(
        targetValue = if (focused) SlugYardTvMetrics.RowFocusScale else 1f,
        animationSpec = tween(220),
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

    // Row-to-row DPAD up/down is routed centrally by dpadRepeatThrottle() (see
    // DpadThrottleModifier.kt) via LocalDpadVerticalNeighbors, not by intercepting key events
    // on this card directly. A per-card onPreviewKeyEvent was tried first and proved
    // unreliable: dpadRepeatThrottle sits on an ancestor LazyColumn and its own
    // onPreviewKeyEvent fires first (preview events travel root -> leaf), so it was winning
    // the race and jumping straight to the hero via Compose's default 2D focus search before
    // this card's handler ever ran. Registering this card's neighbors here instead lets the
    // ancestor prefer them. Keyed on (focused, upNeighbor, downNeighbor) rather than a
    // one-shot onFocusChanged write, since neighbor identity can change (e.g. rows list
    // mutating) while this card stays focused across a recomposition.
    LaunchedEffect(focused, upNeighbor, downNeighbor) {
        if (focused) {
            dpadVerticalNeighbors.up = upNeighbor
            dpadVerticalNeighbors.down = downNeighbor
        }
    }

    Box(
        modifier = focusMovementModifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .size(width = 176.dp, height = 264.dp)
            .zIndex(if (focused) 1f else 0f)
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
        // Drawn underneath the image so a failed/missing load (Coil renders nothing on
        // error) still leaves a readable card instead of a bare placeholder box.
        Text(
            poster.title,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = SlugYardPalette.OnCanvasMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (!poster.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = poster.imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
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
