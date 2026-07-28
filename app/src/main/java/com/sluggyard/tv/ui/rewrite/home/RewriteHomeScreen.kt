package com.sluggyard.tv.ui.rewrite.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.requestFocusReliably
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class RewriteHomePoster(
    val id: String,
    val title: String,
    val imageUrl: String?,
    val progressFraction: Float? = null,
    val addonId: String? = null,
    val contentType: String? = null,
    // Present only for Continue Watching tiles resumed from a saved episode checkpoint; carried
    // through to Streams(...) so resuming an episode doesn't lose season/episode identity.
    val season: Int? = null,
    val episode: Int? = null,
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
    val heroCandidates: List<RewriteHero> = emptyList(),
    val rows: List<RewriteHomeRow> = emptyList(),
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
)

@OptIn(ExperimentalFoundationApi::class)
private val RewriteHomeBringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 140)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            offset < 40f -> offset - 40f
            trailingEdge > containerSize - 40f -> trailingEdge - containerSize + 40f
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
    onRetry: () -> Unit,
    smoothFocusMovement: Boolean = true,
    // Lets the root nav header's DPAD_DOWN focus search land directly on the hero's Play button
    // instead of going nowhere. See RewriteAppShell's homeContentFocusRequester for why this
    // exists.
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    // Persisted by the caller (outside this composable's own lifecycle) so returning from
    // Details/Streams doesn't reset which hero was showing or scroll every row back to the
    // start -- this composable is fully disposed and recomposed fresh on Home<->Details nav
    // since RewriteAppShell's `when(destination)` only calls it while destination == Home.
    rowScrollStates: MutableMap<String, ScrollState> = remember { mutableMapOf() },
    heroIndexState: MutableState<Int> = remember { mutableStateOf(0) },
    lastHeroKeyState: MutableState<String?> = remember { mutableStateOf(null) },
) {
    val rowFocusRequesters = remember(state.rows.map { it.id }) {
        List(state.rows.size) { FocusRequester() }
    }
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    val heroKey = state.heroCandidates.joinToString(separator = "|") { it.id }
    var heroIndex by heroIndexState
    LaunchedEffect(heroKey) {
        // Only reset to the first hero when the candidate set actually changed identity (a
        // fresh catalog load) -- not merely because this composable remounted after returning
        // from Details, which would otherwise always snap back to index 0.
        if (lastHeroKeyState.value != heroKey) {
            heroIndex = 0
            lastHeroKeyState.value = heroKey
        }
        if (state.heroCandidates.size > 1) {
            while (true) {
                delay(11_000)
                heroIndex = (heroIndex + 1) % state.heroCandidates.size
            }
        }
    }
    LaunchedEffect(contentFocusRequester, state.hero != null) {
        if (state.hero != null) scrollState.scrollTo(0)
        contentFocusRequester?.requestFocusReliably(retries = 8)
    }
    if (state.hero == null && contentFocusRequester != null) {
        LaunchedEffect(contentFocusRequester, state.rows.map { it.id }, state.statusMessage) {
            contentFocusRequester.requestFocusReliably(retries = 8)
        }
    }
    val visibleHero = state.heroCandidates.getOrNull(heroIndex) ?: state.hero
    CompositionLocalProvider(LocalBringIntoViewSpec provides RewriteHomeBringIntoViewSpec) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(SlugYardPalette.Canvas)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            if (visibleHero == null && state.rows.isEmpty()) {
                RewriteHomeStatusPanel(
                    isLoading = state.isLoading,
                    message = state.statusMessage,
                    onRetry = onRetry,
                    focusRequester = contentFocusRequester,
                )
            }
            visibleHero?.let { hero ->
                RewriteHeroPanel(
                    hero = hero,
                    onPlay = { onPlay(hero) },
                    onDetails = { onDetails(hero) },
                    contentFocusRequester = contentFocusRequester,
                    headerFocusRequester = headerFocusRequester,
                    downFocusRequester = rowFocusRequesters.firstOrNull(),
                    onHeroFocused = {
                        scrollScope.launch { scrollState.scrollTo(0) }
                    },
                )
            }
            state.rows.forEachIndexed { index, row ->
                RewritePosterRow(
                    row = row,
                    scrollState = rowScrollStates.getOrPut(row.id) { ScrollState(0) },
                    smoothFocusMovement = smoothFocusMovement,
                    onPosterSelected = onPosterSelected,
                    entryFocusRequester = when {
                        index == 0 && visibleHero == null -> contentFocusRequester ?: rowFocusRequesters[index]
                        else -> rowFocusRequesters[index]
                    },
                    upFocusRequester = if (index == 0) {
                        if (visibleHero == null) null else contentFocusRequester
                    } else {
                        rowFocusRequesters[index - 1]
                    },
                    downFocusRequester = rowFocusRequesters.getOrNull(index + 1),
                )
            }
            Spacer(Modifier.height(56.dp))
        }
    }
}

@Composable
private fun RewriteHomeStatusPanel(
    isLoading: Boolean,
    message: String?,
    onRetry: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val panelModifier = Modifier
        .fillMaxWidth()
        .height(220.dp)
        .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)
    if (isLoading) {
        Box(
            modifier = panelModifier
                .let { base -> if (focusRequester != null) base.focusRequester(focusRequester) else base }
                .focusable()
                .background(SlugYardPalette.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("Loading home catalogs…", style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvasMuted)
        }
    } else {
        Column(
            modifier = panelModifier.background(SlugYardPalette.Surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                message ?: "No home content is available yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = SlugYardPalette.OnCanvasMuted,
            )
            Button(
                onClick = onRetry,
                modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            ) {
                Text("Retry")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RewriteHeroPanel(
    hero: RewriteHero,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onHeroFocused: () -> Unit = {},
) {
    val playFocusRequester = remember { FocusRequester() }
    val initialFocusRequester = contentFocusRequester ?: playFocusRequester
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Tall enough that the bottom-aligned title/pills/summary/buttons column never
            // overflows above the box's own top edge (Compose doesn't clip an
            // Alignment.BottomStart child to its parent's bounds by default, so an undersized box
            // here renders the title off the top of the screen) while still leaving the Play
            // button on-screen without needing a scroll on initial load.
            .height(420.dp)
            .background(SlugYardPalette.Surface),
    ) {
        hero.backdropUrl?.takeIf(String::isNotBlank)?.let { backdropUrl ->
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.54f,
            )
        } ?: hero.posterUrl?.let { posterUrl ->
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.20f,
            )
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(420.dp),
                contentScale = ContentScale.Fit,
                alpha = 0.30f,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(SlugYardPalette.Canvas, Color.Transparent, Color.Transparent),
                    ),
                ),
        )
        // A separate bottom scrim keeps the title and actions readable across bright artwork.
        // This borrows the legibility principle from the old hero without copying its layout or
        // gradient stops into the rewrite.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.40f to Color.Transparent,
                            0.62f to SlugYardPalette.Canvas.copy(alpha = 0.18f),
                            0.84f to SlugYardPalette.Canvas.copy(alpha = 0.72f),
                            1.0f to SlugYardPalette.Canvas,
                        ),
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
                style = MaterialTheme.typography.displayMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroBadge(hero.contextTag)
                hero.descriptorTag?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = SlugYardPalette.OnCanvasMuted)
                }
            }
            if (hero.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    hero.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(22.dp))
            // Keep button-to-button movement inside the hero from moving the catalog viewport.
            CompositionLocalProvider(LocalBringIntoViewSpec provides RewriteHomeBringIntoViewSpec) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroActionButton(
                        "Play",
                        Icons.Default.PlayArrow,
                        onPlay,
                        primary = true,
                        modifier = Modifier
                        .focusRequester(initialFocusRequester)
                        .then(
                            headerFocusRequester?.let { requester ->
                                Modifier.focusProperties { up = requester }
                            } ?: Modifier,
                        )
                            .onFocusChanged { if (it.isFocused) onHeroFocused() }
                            .focusProperties { downFocusRequester?.let { down = it } },
                    )
                    HeroActionButton(
                        "View Details",
                        Icons.Default.Info,
                        onDetails,
                        primary = false,
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) onHeroFocused() }
                            .focusProperties { downFocusRequester?.let { down = it } },
                    )
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
            fontWeight = FontWeight.Medium,
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
    smoothFocusMovement: Boolean,
    onPosterSelected: (RewriteHomePoster) -> Unit,
    entryFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = row.title,
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            style = MaterialTheme.typography.titleLarge,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            horizontalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        ) {
            row.posters.forEachIndexed { index, poster ->
                RewritePosterCard(
                    poster = poster,
                    smoothFocusMovement = smoothFocusMovement,
                    onClick = { onPosterSelected(poster) },
                    // Each row's first card is the stable vertical focus anchor.
                    focusRequester = if (index == 0) entryFocusRequester else null,
                    upFocusRequester = if (index == 0) upFocusRequester else null,
                    downFocusRequester = if (index == 0) downFocusRequester else null,
                )
            }
        }
    }
}

@Composable
internal fun RewritePosterCard(
    poster: RewriteHomePoster,
    smoothFocusMovement: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    cardWidth: Dp = 176.dp,
    cardHeight: Dp = 264.dp,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var bringIntoViewJob by remember { mutableStateOf<Job?>(null) }
    val scale by animateFloatAsState(
        targetValue = if (focused) SlugYardTvMetrics.RowFocusScale else 1f,
        animationSpec = tween(220),
        label = "poster focus scale",
    )

    val focusMovementModifier = if (smoothFocusMovement) {
        Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                bringIntoViewJob?.cancel()
                if (state.isFocused) {
                    bringIntoViewJob = scope.launch {
                        delay(140)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
    } else Modifier

    Box(
        modifier = focusMovementModifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .focusProperties {
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            }
            .size(width = cardWidth, height = cardHeight)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 18f else 0f
            }
            .then(
                if (focused) {
                    Modifier.border(3.dp, SlugYardPalette.FocusRing, RoundedCornerShape(SlugYardTvMetrics.CardCornerRadius))
                } else Modifier,
            )
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
    }
}
