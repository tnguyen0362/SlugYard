package com.sluggyard.tv.ui.app.home

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key as composeKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Size
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.theme.QuicksandFamily
import com.sluggyard.tv.ui.app.preferCardBackdropUrl
import com.sluggyard.tv.ui.app.preferLargePosterUrl
import com.sluggyard.tv.ui.app.preferTvBackdropUrl
import com.sluggyard.tv.ui.app.requestFocusReliably
import com.sluggyard.tv.ui.app.contentTypeLabel
import com.sluggyard.tv.ui.app.episodeLabel
import com.sluggyard.tv.ui.app.horizontalFocusBounds
import com.sluggyard.tv.ui.app.verticalFocusExits
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.sluggyard.tv.ui.util.DpadVerticalNeighbors
import com.sluggyard.tv.ui.util.LocalDpadVerticalNeighbors
import com.sluggyard.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class HomePoster(
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
    val parentId: String? = null,
    val parentType: String? = null,
    val resumePositionMs: Long = 0L,
    /** Short synopsis shown on the focused poster — from catalog meta when available. */
    val summary: String? = null,
    /** Landscape / backdrop art when the catalog provides it (preferred for wide focus). */
    val backdropUrl: String? = null,
    val contentGenres: String? = null,
    val contentLanguage: String? = null,
    /** Numeric score for landscape focus chrome, e.g. "7.8". */
    val ratingLabel: String? = null,
    /** "IMDb" or "TMDB" when [ratingLabel] is set. */
    val ratingSource: String? = null,
    /** True for a next-up tile: play opens the next unwatched episode, not a resume. */
    val nextUp: Boolean = false,
)

data class HomeRow(
    val id: String,
    val title: String,
    val posters: List<HomePoster>,
)

data class Hero(
    val id: String,
    val title: String,
    val backdropUrl: String?,
    // Threaded through to playback (Streams -> Player -> saved checkpoint) so a hero Play tap
    // that skips Details entirely still saves Continue Watching art instead of leaving the
    // checkpoint's posterUrl permanently null. See AppShell's Streams(...) construction.
    val posterUrl: String? = null,
    val summary: String,
    val contextTag: String,
    val descriptorTag: String? = null,
    val addonId: String,
    val contentType: String,
)

data class HomeState(
    val hero: Hero? = null,
    val heroCandidates: List<Hero> = emptyList(),
    val rows: List<HomeRow> = emptyList(),
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
)

/**
 * Keep focused catalog **rows** (title + posters) clear of the floating root nav.
 *
 * Each shelf ends with [HomeCatalogFocusTopInset] of empty space. BringIntoView pins the
 * next shelf's title at that same inset from the top — so the previous shelf's posters sit fully
 * above the viewport and only empty canvas remains under the header (breathing room), not a
 * chopped leftover row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberHomeBringIntoViewSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    val topInsetPx = with(density) { HomeCatalogFocusTopInset.toPx() }
    val bottomInsetPx = with(density) { 48.dp.toPx() }
    return remember(topInsetPx, bottomInsetPx) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 0)

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return homeCatalogVerticalScrollDistance(
                    offset = offset,
                    size = size,
                    containerSize = containerSize,
                    topInsetPx = topInsetPx,
                    bottomInsetPx = bottomInsetPx,
                )
            }
        }
    }
}

/**
 * Netflix/TV horizontal rails: pin the focused card near the leading edge so trailing posters
 * (and their titles) stay visible. LazyList clamps at the ends of the row.
 *
 * Applied only inside [PosterRow]'s LazyRow — must not replace the vertical catalog spec.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberHomeHorizontalBringIntoViewSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    val leadingInsetPx = with(density) { SlugYardTvMetrics.ScreenHorizontalInset.toPx() }
    return remember(leadingInsetPx) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 0)

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return homeCatalogHorizontalScrollDistance(
                    offset = offset,
                    leadingInsetPx = leadingInsetPx,
                )
            }
        }
    }
}

/**
 * BringIntoView pins a focused shelf **title** below the floating root nav so labels never
 * sit under the header. Separated from [HomeShelfTrailingGap] so catalogs can stack
 * densely (Figma home) without requiring a full nav-height void after every rail.
 */
private val HomeCatalogFocusTopInset = (SlugYardTvMetrics.RootNavBarHeight + 4.dp)

/** Figma MoviePreview / HomePage shelf stack — tight trailing void after each rail. */
private val HomeShelfTrailingGap = SlugYardTvMetrics.ShelfTrailingGap

/** Focused Home tiles expand to a real ~16:9 layout width (pushes neighbors — no overlap). */
private const val HomePosterLandscapeAspect = 16f / 9f
/** Neighbor portrait warm-cache window after focus settles (leading..trailing). */
private const val HomePosterPrefetchBehind = 1
private const val HomePosterPrefetchAhead = 4
/**
 * Landscape warm around focus. Keep tight: too many concurrent Metahub/TMDB decodes make
 * catalog-to-catalog hops stall the main thread on mid-range TV boxes (Onn 4K Pro).
 */
private const val HomeBackdropPrefetchBehind = 1
private const val HomeBackdropPrefetchAhead = 4
/** Popular/Featured (Metahub full-HD) get a slightly wider window, still not a decode storm. */
private const val HomeBackdropPrefetchAheadPrimary = 6
/** Visible LazyRow warm window for portraits. */
private const val HomePosterScrollWarmBehind = 1
private const val HomePosterScrollWarmAhead = 4
/** Eager portrait warm of leading cards during splash (no focus yet). */
private const val HomePosterWarmFirst = 8
private const val HomePosterPrefetchSettleMs = 48L
private const val HomePosterRetryMax = 2
private const val HomePosterRetryDelayMs = 140L

private fun isLowRamTv(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return am?.isLowRamDevice == true
}

/** Rows whose entry cards can receive the next vertical focus move. */
internal fun homeVerticalWarmRowIndices(
    rowCount: Int,
    focusedRowIndex: Int,
    firstCatalogRowIndex: Int,
): List<Int> {
    if (rowCount <= 0) return emptyList()
    val first = firstCatalogRowIndex.coerceIn(0, rowCount - 1)
    if (focusedRowIndex !in 0 until rowCount) {
        return listOf(first, (first + 1).coerceAtMost(rowCount - 1)).distinct()
    }
    return buildList {
        add(if (focusedRowIndex == rowCount - 1) first else focusedRowIndex + 1)
        if (focusedRowIndex > 0) add(focusedRowIndex - 1)
    }.distinct()
}

/** Vertical LazyColumn shelf bring-into-view (nav clearance). */
internal fun homeCatalogVerticalScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
): Float {
    val trailingEdge = offset + size
    // Button-sized targets: never pin toward the top — Home restores the hero via scrollToItem(0).
    if (size <= 96f) {
        return when {
            trailingEdge > containerSize - bottomInsetPx ->
                trailingEdge - containerSize + bottomInsetPx
            else -> 0f
        }
    }
    return when {
        offset < topInsetPx -> offset - topInsetPx
        trailingEdge > containerSize - bottomInsetPx -> trailingEdge - containerSize + bottomInsetPx
        else -> 0f
    }
}

/** Horizontal LazyRow focus bring-into-view — leading-edge pin (not trailing / centered). */
internal fun homeCatalogHorizontalScrollDistance(
    offset: Float,
    leadingInsetPx: Float,
): Float = offset - leadingInsetPx

internal fun heroDownCatalogRowIndex(rowIds: List<String>): Int {
    val continueWatching = rowIds.indexOf("continue_watching")
    if (continueWatching >= 0) return continueWatching
    return rowIds.indexOfFirst { it != "continue_watching" }.let { if (it < 0) 0 else it }
}

/** Hero Play / View Details must not trigger LazyColumn bring-into-view (chops the title). */
@OptIn(ExperimentalFoundationApi::class)
private val HomeHeroNoBringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 0)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
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
fun HomeScreen(
    state: HomeState,
    onPlay: (Hero) -> Unit,
    onDetails: (Hero) -> Unit,
    onPosterSelected: (HomePoster) -> Unit,
    onContinueWatchingSelected: ((HomePoster) -> Unit)? = null,
    onContinueWatchingLongPress: ((HomePoster) -> Unit)? = null,
    onRetry: () -> Unit,
    smoothFocusMovement: Boolean = true,
    // Lets the root nav header's DPAD_DOWN focus search land directly on the hero's Play button
    // instead of going nowhere. See AppShell's homeContentFocusRequester for why this
    // exists.
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    requestInitialFocus: Boolean = true,
    onInitialFocusRequested: () -> Unit = {},
    /** Watchlist stays portrait; Home shelves expand to landscape on focus. */
    expandOnFocus: Boolean = true,
    modifier: Modifier = Modifier,
    // Persisted by the caller (outside this composable's own lifecycle) so returning from
    // Details/Streams doesn't reset which hero was showing or scroll every row back to the
    // start -- this composable is fully disposed and recomposed fresh on Home<->Details nav
    // since AppShell's `when(destination)` only calls it while destination == Home.
    rowScrollStates: MutableMap<String, LazyListState> = remember { mutableMapOf() },
    heroIndexState: MutableState<Int> = remember { mutableStateOf(0) },
    lastHeroKeyState: MutableState<String?> = remember { mutableStateOf(null) },
    /** Bumped after Remove-from-CW so focus stays on the Continue Watching row. */
    continueWatchingFocusNonce: Int = 0,
    suppressHeroFocusRestore: Boolean = false,
    onContinueWatchingFocusRestored: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // OEM "low RAM" TV boxes still choke on simultaneous rail + landscape decode; keep expand
    // on capable devices (Onn 4K Pro etc.) and auto-disable only where Android flags it.
    val effectiveExpandOnFocus = remember(expandOnFocus, context) {
        expandOnFocus && !isLowRamTv(context)
    }
    val rowFocusRequesters = remember(state.rows.map { it.id }) {
        List(state.rows.size) { FocusRequester() }
    }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    // Hero Play lives inside a LazyColumn item, so it unmounts when scrolled away. Header DPAD_DOWN
    // must land on an always-composed bridge that scrolls home to top, then hands focus off.
    val heroPlayFocusRequester = remember { FocusRequester() }
    val heroKey = state.heroCandidates.joinToString(separator = "|") { it.id }
    var heroIndex by heroIndexState
    var heroActionFocused by remember { mutableStateOf(false) }
    val summaryById = remember(state.heroCandidates, state.hero?.id, state.hero?.summary) {
        buildMap {
            state.hero?.summary?.takeIf { it.isNotBlank() }?.let { put(state.hero!!.id, it) }
            state.heroCandidates.forEach { candidate ->
                candidate.summary.takeIf { it.isNotBlank() }?.let { put(candidate.id, it) }
            }
        }
    }
    val visibleHero = state.heroCandidates.getOrNull(heroIndex) ?: state.hero
    /** First non-Continue Watching row, used for the intentional last-row wrap target. */
    val firstCatalogRowIndex = remember(state.rows.map { it.id }) {
        state.rows.indexOfFirst { it.id != "continue_watching" }.let { if (it < 0) 0 else it }
    }
    /** Hero Down should enter Continue Watching when that row exists, not skip to Popular. */
    val heroDownRowIndex = remember(state.rows.map { it.id }) {
        heroDownCatalogRowIndex(state.rows.map { it.id })
    }
    fun firstCatalogListIndex(): Int {
        var index = 0
        if (state.statusMessage != null && (visibleHero != null || state.rows.isNotEmpty())) index++
        if (visibleHero == null && state.rows.isEmpty()) index++
        if (visibleHero != null) {
            index++ // hero panel
            index++ // hero exit gap — empty band under nav when first shelf is focused
        }
        return index
    }
    suspend fun focusPrimaryContent() {
        listState.scrollToItem(0)
        val target = when {
            visibleHero != null -> heroPlayFocusRequester
            state.rows.isNotEmpty() -> rowFocusRequesters.firstOrNull()
            else -> heroPlayFocusRequester // empty/loading panel
        }
        target?.requestFocusReliably(retries = 8)
    }
    suspend fun focusHeroActions() {
        listState.scrollToItem(0)
        kotlinx.coroutines.delay(24)
        heroPlayFocusRequester.requestFocusReliably(retries = 10)
    }
    suspend fun focusCatalogRow(rowIndex: Int) {
        if (state.rows.isEmpty()) return
        val clamped = rowIndex.coerceIn(0, state.rows.lastIndex)
        val listIndex = firstCatalogListIndex() + clamped
        // Only force-compose when the target shelf is off-window. Always re-scrollToItem-ing
        // every Up/Down fought the subsequent row bringIntoView and felt like a double hop.
        val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == listIndex }
        if (!alreadyVisible) {
            listState.scrollToItem(listIndex)
        }
        val requester = rowFocusRequesters.getOrNull(clamped) ?: return
        if (requester.requestFocusReliably(retries = 4)) return
        // The entry card lives at LazyRow index 0; a horizontally scrolled shelf decomposes it,
        // which is what made the later rails (Romance / Family / Crime) unreachable.
        state.rows.getOrNull(clamped)?.id
            ?.let(rowScrollStates::get)
            ?.takeIf { it.firstVisibleItemIndex > 0 }
            ?.scrollToItem(0)
        requester.requestFocusReliably(retries = 4)
    }
    suspend fun focusFirstCatalogRow() {
        focusCatalogRow(firstCatalogRowIndex)
    }
    // Which shelf owns focus right now. Horizontal moves inside one shelf must not re-run the
    // row-level BringIntoView — that per-keypress scroll animation was the catalog lag.
    // Declared before the hero rotation effect so rotation can pause while a shelf is focused.
    val focusedRowIdState = remember { mutableStateOf<String?>(null) }
    val verticalWarmPixels = remember(density) {
        homeLandscapeArtworkSize(density.density)
    }
    val verticalWarmDecode = remember(verticalWarmPixels) { verticalWarmPixels.toCoilSize() }
    val verticalWarmKey = state.rows.joinToString(separator = "|") { row ->
        val entry = row.posters.firstOrNull()
        "${row.id}:${entry?.id}:${entry?.backdropUrl}"
    }
    // Warm only cards reachable by the next Up/Down press. The previous broad startup burst
    // decoded dozens of full-HD backdrops that focus might never visit, saturating Coil exactly
    // when Popular/Featured needed their first landscape frame.
    LaunchedEffect(effectiveExpandOnFocus, verticalWarmKey, focusedRowIdState.value, verticalWarmDecode) {
        if (!effectiveExpandOnFocus) return@LaunchedEffect
        val focusedIndex = state.rows.indexOfFirst { it.id == focusedRowIdState.value }
        val targets = homeVerticalWarmRowIndices(
            rowCount = state.rows.size,
            focusedRowIndex = focusedIndex,
            firstCatalogRowIndex = firstCatalogRowIndex,
        )
        val loader = context.imageLoader
        for (rowIndex in targets) {
            val row = state.rows.getOrNull(rowIndex)
                ?.takeIf { homeRowSupportsLandscape(it.id) }
                ?: continue
            val poster = row.posters.firstOrNull() ?: continue
            val posterUrl = preferLargePosterUrl(poster.imageUrl)
            val url = preferCardBackdropUrl(poster.backdropUrl)
                ?.takeIf { it.isNotBlank() && it != posterUrl }
                ?: continue
            if (landscapeReadyInMemory(context, poster.id, url, verticalWarmPixels)) continue
            loader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(verticalWarmDecode)
                    .precision(coil3.size.Precision.EXACT)
                    .memoryCacheKey(homeBackdropMemoryCacheKey(poster.id, url, verticalWarmPixels))
                    .diskCacheKey("card-backdrop:${poster.id}:$url")
                    .build(),
            )
        }
    }
    LaunchedEffect(heroKey, heroActionFocused, focusedRowIdState.value) {
        // Only reset to the first hero when the candidate set actually changed identity (a
        // fresh catalog load) -- not merely because this composable remounted after returning
        // from Details, which would otherwise always snap back to index 0.
        if (lastHeroKeyState.value != heroKey) {
            heroIndex = 0
            lastHeroKeyState.value = heroKey
        }
        // Rotate only while the hero itself is the active area (or nothing is focused yet).
        // While focus is on a catalog shelf the carousel keeps churning full-HD hero decodes
        // against the focused row's landscape decode — the first-shelf hitch amplifier.
        val shelfFocused = focusedRowIdState.value != null
        if (state.heroCandidates.size > 1 && !heroActionFocused && !shelfFocused) {
            while (true) {
                delay(11_000)
                heroIndex = (heroIndex + 1) % state.heroCandidates.size
            }
        }
    }
    LaunchedEffect(contentFocusRequester, requestInitialFocus, state.hero != null, state.rows.map { it.id }) {
        if (!requestInitialFocus) return@LaunchedEffect
        focusPrimaryContent()
        onInitialFocusRequested()
    }
    LaunchedEffect(continueWatchingFocusNonce) {
        if (continueWatchingFocusNonce <= 0) return@LaunchedEffect
        // Let the CW row recompose after the removed tile leaves the list.
        delay(48)
        val cwIndex = state.rows.indexOfFirst { it.id == "continue_watching" }
        if (cwIndex >= 0) {
            focusCatalogRow(cwIndex)
        } else {
            focusFirstCatalogRow()
        }
        onContinueWatchingFocusRestored()
    }
    val catalogBringIntoViewSpec = rememberHomeBringIntoViewSpec()
    val dpadVerticalNeighbors = remember { DpadVerticalNeighbors() }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides catalogBringIntoViewSpec,
        LocalDpadVerticalNeighbors provides dpadVerticalNeighbors,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SlugYardPalette.Canvas),
        ) {
            if (contentFocusRequester != null) {
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(contentFocusRequester)
                        .focusProperties {
                            // Header calls requestFocus() on this bridge; keep it out of spatial search
                            // so DPAD never lands on an invisible 1dp sink.
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        }
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                scrollScope.launch {
                                    // After CW remove, the disposed tile can dump focus here.
                                    // Don't teleport to the hero while we restore the CW row.
                                    if (suppressHeroFocusRestore) {
                                        val cwIndex = state.rows.indexOfFirst { it.id == "continue_watching" }
                                        if (cwIndex >= 0) focusCatalogRow(cwIndex)
                                        else focusFirstCatalogRow()
                                    } else {
                                        focusPrimaryContent()
                                    }
                                }
                            }
                        }
                        .focusable(),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .dpadRepeatThrottle(),
                contentPadding = PaddingValues(
                    top = if (visibleHero == null) SlugYardTvMetrics.RootNavBarHeight else 0.dp,
                    bottom = 56.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
            if (state.statusMessage != null && (visibleHero != null || state.rows.isNotEmpty())) {
                item(key = "status") {
                    Text(
                        state.statusMessage,
                        modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                        style = MaterialTheme.typography.bodySmall,
                        color = SlugYardPalette.OnCanvasMuted,
                    )
                }
            }
            if (visibleHero == null && state.rows.isEmpty()) {
                item(key = "empty") {
                    HomeStatusPanel(
                        isLoading = state.isLoading,
                        message = state.statusMessage,
                        onRetry = onRetry,
                        focusRequester = heroPlayFocusRequester,
                    )
                }
            }
            visibleHero?.let { hero ->
                item(key = "hero:${hero.id}") {
                    HeroPanel(
                        hero = hero,
                        onPlay = { onPlay(hero) },
                        onDetails = { onDetails(hero) },
                        contentFocusRequester = heroPlayFocusRequester,
                        headerFocusRequester = headerFocusRequester,
                        downFocusRequester = rowFocusRequesters.getOrNull(heroDownRowIndex),
                        onPrepareFocusDown = {
                            listState.scrollToItem(firstCatalogListIndex() + heroDownRowIndex)
                        },
                        onRequestFocusDown = { scrollScope.launch { focusCatalogRow(heroDownRowIndex) } },
                        onHeroFocusChanged = { focused ->
                            heroActionFocused = focused
                            // Leaving a shelf for the hero must clear shelf id so carousel can
                            // resume (horizontal hops only set it; they never clear it).
                            if (focused) {
                                focusedRowIdState.value = null
                                scrollScope.launch { listState.scrollToItem(0) }
                            }
                        },
                    )
                }
                // Tight hero → first shelf band (Figma home density). Focused-shelf nav clearance
                // still comes from BringIntoView + per-shelf trailing spacer, not this gap.
                item(key = "hero_exit_gap") {
                    Spacer(modifier = Modifier.height(SlugYardTvMetrics.ShelfStackGap))
                }
            }
            itemsIndexed(
                items = state.rows,
                key = { _, row -> row.id },
            ) { index, row ->
                val upRequester = when {
                    index <= 0 && visibleHero != null -> heroPlayFocusRequester
                    index <= 0 -> headerFocusRequester
                    else -> rowFocusRequesters.getOrNull(index - 1)
                }
                // Last shelf wraps to Popular/Featured. The Thriller bug was wrapping while later
                // rails were still loading (Thriller temporarily lastIndex) — splash now waits
                // until catalogs settle, so wrap is intentional again, not a dead-end.
                val downRequester = if (index >= state.rows.lastIndex) {
                    rowFocusRequesters.getOrNull(firstCatalogRowIndex)
                } else {
                    rowFocusRequesters.getOrNull(index + 1)
                }
                PosterRow(
                    row = row,
                    listState = rowScrollStates.getOrPut(row.id) { LazyListState() },
                    smoothFocusMovement = smoothFocusMovement,
                    // Continue Watching: portrait-only tiles (no landscape focus morph).
                    expandOnFocus = effectiveExpandOnFocus && row.id != "continue_watching",
                    onPosterSelected = if (row.id == "continue_watching" && onContinueWatchingSelected != null) {
                        onContinueWatchingSelected
                    } else {
                        onPosterSelected
                    },
                    onPosterLongPress = if (row.id == "continue_watching") {
                        onContinueWatchingLongPress
                    } else {
                        null
                    },
                    entryFocusRequester = rowFocusRequesters[index],
                    upFocusRequester = upRequester,
                    downFocusRequester = downRequester,
                    onFocusUp = {
                        scrollScope.launch {
                            when {
                                index <= 0 && visibleHero != null -> focusHeroActions()
                                index <= 0 -> headerFocusRequester?.requestFocusReliably(retries = 6)
                                else -> focusCatalogRow(index - 1)
                            }
                        }
                    },
                    onFocusDown = {
                        scrollScope.launch {
                            if (index >= state.rows.lastIndex) {
                                focusCatalogRow(firstCatalogRowIndex)
                            } else {
                                focusCatalogRow(index + 1)
                            }
                        }
                        Unit
                    },
                    onPrepareFocusUp = {
                        when {
                            index <= 0 && visibleHero != null -> {
                                listState.scrollToItem(0)
                            }
                            index <= 0 -> Unit
                            else -> {
                                val targetIndex = firstCatalogListIndex() + index - 1
                                val visible = listState.layoutInfo.visibleItemsInfo.any {
                                    it.index == targetIndex
                                }
                                if (!visible) listState.scrollToItem(targetIndex)
                            }
                        }
                    },
                    onPrepareFocusDown = {
                        val target = if (index >= state.rows.lastIndex) {
                            firstCatalogRowIndex
                        } else {
                            index + 1
                        }
                        val targetIndex = firstCatalogListIndex() + target
                        val visible = listState.layoutInfo.visibleItemsInfo.any {
                            it.index == targetIndex
                        }
                        if (!visible) listState.scrollToItem(targetIndex)
                    },
                    focusedRowIdState = focusedRowIdState,
                    summaryLookup = summaryById::get,
                )
            }
            }
        }
    }
}

@Composable
private fun HomeStatusPanel(
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
private fun HeroPanel(
    hero: Hero,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onPrepareFocusDown: (suspend () -> Unit)? = null,
    onRequestFocusDown: () -> Unit = {},
    onHeroFocusChanged: (Boolean) -> Unit = {},
) {
    val playFocusRequester = remember { FocusRequester() }
    val initialFocusRequester = contentFocusRequester ?: playFocusRequester
    val dpadNeighbors = LocalDpadVerticalNeighbors.current
    val heroDownModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            onRequestFocusDown()
            true
        } else {
            false
        }
    }
    val heroNeighborModifier = Modifier.onFocusChanged { state ->
        onHeroFocusChanged(state.isFocused)
        if (state.isFocused) {
            dpadNeighbors.up = headerFocusRequester
            dpadNeighbors.down = downFocusRequester
            dpadNeighbors.upBringIntoView = null
            dpadNeighbors.downBringIntoView = onPrepareFocusDown
        } else if (
            dpadNeighbors.up == headerFocusRequester &&
            dpadNeighbors.down == downFocusRequester
        ) {
            dpadNeighbors.up = null
            dpadNeighbors.down = null
            dpadNeighbors.upBringIntoView = null
            dpadNeighbors.downBringIntoView = null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Figma HomePage hero (~810px @ 1440) scaled for 1080p TV; title sits upper-left
            // under the floating nav so the first shelf can stack tightly into the fade.
            .height(SlugYardTvMetrics.HomeHeroHeight)
            .background(SlugYardPalette.Surface),
    ) {
        val heroContext = LocalContext.current
        hero.backdropUrl?.takeIf(String::isNotBlank)?.let { backdropUrl ->
            AsyncImage(
                model = remember(hero.id, backdropUrl) {
                    ImageRequest.Builder(heroContext)
                        .data(preferTvBackdropUrl(backdropUrl))
                        .size(Size(1280, 720))
                        .memoryCacheKey("hero-backdrop:${hero.id}:$backdropUrl")
                        .diskCacheKey("hero-backdrop:${hero.id}:$backdropUrl")
                        .build()
                },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.88f,
            )
        } ?: hero.posterUrl?.let { posterUrl ->
            val posterModel = remember(hero.id, posterUrl) {
                ImageRequest.Builder(heroContext)
                    .data(preferLargePosterUrl(posterUrl))
                    .size(Size(500, 750))
                    .memoryCacheKey("hero-poster:${hero.id}:$posterUrl")
                    .diskCacheKey("hero-poster:${hero.id}:$posterUrl")
                    .build()
            }
            AsyncImage(
                model = posterModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.20f,
            )
            AsyncImage(
                model = posterModel,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(420.dp),
                contentScale = ContentScale.Fit,
                alpha = 0.30f,
            )
        }
        // Top nav scrim — Figma HeaderLinearGradient (70% black → transparent over ~68dp).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlugYardTvMetrics.RootNavBarHeight + 24.dp)
                .align(Alignment.TopCenter)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.70f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            SlugYardPalette.Canvas.copy(alpha = 0.92f),
                            SlugYardPalette.Canvas.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        // Bottom fade into canvas — Figma MovieBlock gradient stops (~0 → solid #141414).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            0.58f to SlugYardPalette.Canvas.copy(alpha = 0.15f),
                            0.72f to SlugYardPalette.Canvas.copy(alpha = 0.35f),
                            0.86f to SlugYardPalette.Canvas.copy(alpha = 0.58f),
                            1.0f to SlugYardPalette.Canvas,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = SlugYardTvMetrics.ScreenHorizontalInset,
                    end = SlugYardTvMetrics.ScreenHorizontalInset,
                    // Clear floating nav (Figma TitlePreview top ~154 with 68 header).
                    top = SlugYardTvMetrics.RootNavBarHeight + 48.dp,
                    bottom = 28.dp,
                )
                .fillMaxWidth(0.52f),
        ) {
            Text(
                hero.title,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Normal,
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
            if (hero.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    hero.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(22.dp))
            // Keep button focus from scrolling/chopping the hero via bring-into-view.
            CompositionLocalProvider(LocalBringIntoViewSpec provides HomeHeroNoBringIntoViewSpec) {
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    HeroActionButton(
                        "Play",
                        Icons.Default.PlayArrow,
                        onPlay,
                        primary = true,
                        modifier = Modifier
                            .focusRequester(initialFocusRequester)
                            .verticalFocusExits(up = headerFocusRequester, down = downFocusRequester)
                            .then(heroDownModifier)
                            .then(heroNeighborModifier),
                    )
                    HeroActionButton(
                        "View Details",
                        Icons.Default.Info,
                        onDetails,
                        primary = false,
                        modifier = Modifier
                            .verticalFocusExits(up = headerFocusRequester, down = downFocusRequester)
                            .then(heroDownModifier)
                            .then(heroNeighborModifier),
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Figma Play = white/black; More Info = rgba(109,109,110,0.7)/white; TV focus → gold accent.
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    val moreInfoFill = Color(0xB26D6D6E)
    val container = when {
        focused -> SlugYardPalette.Accent
        primary -> Color.White
        else -> moreInfoFill
    }
    val content = when {
        focused -> Color(0xFF141414)
        primary -> Color.Black
        else -> Color.White
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = if (focused) 1.06f else 1f
                scaleY = if (focused) 1.06f else 1f
            }
            .height(42.dp)
            .clip(shape)
            .background(container, shape)
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 8.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterRow(
    row: HomeRow,
    smoothFocusMovement: Boolean,
    onPosterSelected: (HomePoster) -> Unit,
    onPosterLongPress: ((HomePoster) -> Unit)? = null,
    entryFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocusUp: (() -> Unit)? = null,
    onFocusDown: (() -> Unit)? = null,
    onPrepareFocusUp: (suspend () -> Unit)? = null,
    onPrepareFocusDown: (suspend () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    expandOnFocus: Boolean = true,
    focusedRowIdState: MutableState<String?>? = null,
    summaryLookup: (String) -> String? = { null },
) {
    // Bring the whole shelf (title + posters) into view so section labels never sit under the nav.
    val rowBringIntoViewRequester = remember { BringIntoViewRequester() }
    val rowScope = rememberCoroutineScope()
    var rowBringIntoViewJob by remember { mutableStateOf<Job?>(null) }
    var focusedPosterIndex by remember(row.id) { mutableIntStateOf(-1) }
    val horizontalBringIntoViewSpec = rememberHomeHorizontalBringIntoViewSpec()
    val context = LocalContext.current
    val density = LocalDensity.current
    // Popular/Featured = Metahub ~1MB media — need a deeper warm than TMDB genre rails.
    val primaryShelf = remember(row.title) { isPrimaryHomeShelfTitle(row.title) }
    val backdropPrefetchAhead =
        if (primaryShelf) HomeBackdropPrefetchAheadPrimary else HomeBackdropPrefetchAhead
    val posterPrefetchPixels = remember(density) { homePortraitArtworkSize(density.density) }
    val posterPrefetchDecode = remember(posterPrefetchPixels) { posterPrefetchPixels.toCoilSize() }
    val landscapePrefetchPixels = remember(density) { homeLandscapeArtworkSize(density.density) }
    val landscapePrefetchDecode = remember(landscapePrefetchPixels) { landscapePrefetchPixels.toCoilSize() }
    // Eager landscape warm for entry tiles + neighbors around focus via ImageLoader only
    // (no AsyncImage cost on every card). Prefer sequential warm so vertical hops aren't
    // fighting a Metahub decode storm for a whole shelf.
    val prefetchedLandscapeIds = remember(row.id) { mutableSetOf<String>() }
    val rowOwnsFocus = focusedRowIdState?.value == row.id
    suspend fun warmBackdrop(
        poster: HomePoster,
        loader: coil3.ImageLoader = context.imageLoader,
    ) {
        val posterUrl = preferLargePosterUrl(poster.imageUrl)
        val url = preferCardBackdropUrl(poster.backdropUrl)
            ?.takeIf { it.isNotBlank() && it != posterUrl }
            ?: return
        if (poster.id in prefetchedLandscapeIds) {
            if (landscapeReadyInMemory(
                    context,
                    poster.id,
                    url,
                    landscapePrefetchPixels,
                )
            ) {
                return
            }
            // Sticky warm-flag after LRU eviction must not claim the tile is cached.
            prefetchedLandscapeIds.remove(poster.id)
        }
        val result = loader.execute(
            ImageRequest.Builder(context)
                .data(url)
                .size(landscapePrefetchDecode)
                .precision(coil3.size.Precision.EXACT)
                .memoryCacheKey(homeBackdropMemoryCacheKey(poster.id, url, landscapePrefetchPixels))
                .diskCacheKey("card-backdrop:${poster.id}:$url")
                .build(),
        )
        if (result.image != null) {
            prefetchedLandscapeIds.add(poster.id)
        }
    }
    LaunchedEffect(row.id, focusedPosterIndex, rowOwnsFocus, row.posters, landscapePrefetchDecode, expandOnFocus) {
        if (!expandOnFocus) return@LaunchedEffect
        val from: Int
        val to: Int
        if (focusedPosterIndex < 0 || !rowOwnsFocus) return@LaunchedEffect
        from = (focusedPosterIndex - HomeBackdropPrefetchBehind).coerceAtLeast(0)
        to = (focusedPosterIndex + backdropPrefetchAhead).coerceAtMost(row.posters.lastIndex)
        if (from > to) return@LaunchedEffect
        // Warm focused/entry first so vertical hop reveal is ready, then neighbors sequentially.
        val order = buildList {
            val anchor = if (focusedPosterIndex in from..to) focusedPosterIndex else from
            add(anchor)
            for (i in from..to) if (i != anchor) add(i)
        }
        for (i in order) {
            warmBackdrop(row.posters[i])
        }
    }
    // Warm a few neighbor portraits after focus settles — cheaper than landscape; fills blanks.
    LaunchedEffect(row.id, focusedPosterIndex, row.posters, posterPrefetchDecode) {
        val loader = context.imageLoader
        fun enqueuePortrait(poster: HomePoster) {
            val url = preferLargePosterUrl(poster.imageUrl) ?: return
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(posterPrefetchDecode)
                    .precision(coil3.size.Precision.EXACT)
                    .memoryCacheKey(homePosterMemoryCacheKey(poster.id, url, posterPrefetchPixels))
                    .diskCacheKey("poster:${poster.id}:$url")
                    .build(),
            )
        }
        val anchor = focusedPosterIndex
        if (anchor < 0) {
            // Splash / pre-focus: warm the leading end so L→R start doesn't flash blanks.
            val to = (HomePosterWarmFirst - 1).coerceAtMost(row.posters.lastIndex)
            if (to < 0) return@LaunchedEffect
            for (i in 0..to) enqueuePortrait(row.posters[i])
            return@LaunchedEffect
        }
        delay(HomePosterPrefetchSettleMs)
        val from = (anchor - HomePosterPrefetchBehind).coerceAtLeast(0)
        val to = (anchor + HomePosterPrefetchAhead).coerceAtMost(row.posters.lastIndex)
        for (i in from..to) {
            if (i == anchor) continue
            enqueuePortrait(row.posters[i])
        }
    }
    // Visible-window portrait warm tracks L→R. Landscape warming has one owner above, keyed to
    // focusedPosterIndex; running it again from this collector duplicated every focused request.
    LaunchedEffect(
        row.id,
        row.posters,
        posterPrefetchDecode,
        listState,
    ) {
        val loader = context.imageLoader
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                listState.firstVisibleItemIndex to listState.firstVisibleItemIndex
            } else {
                visible.first().index to visible.last().index
            }
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                if (row.posters.isEmpty()) return@collect
                val portraitFrom = (first - HomePosterScrollWarmBehind).coerceAtLeast(0)
                val portraitTo = (last + HomePosterScrollWarmAhead).coerceAtMost(row.posters.lastIndex)
                if (portraitFrom <= portraitTo) {
                    for (i in portraitFrom..portraitTo) {
                        val poster = row.posters[i]
                        val url = preferLargePosterUrl(poster.imageUrl) ?: continue
                        loader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(posterPrefetchDecode)
                                .precision(coil3.size.Precision.EXACT)
                                .memoryCacheKey(homePosterMemoryCacheKey(poster.id, url, posterPrefetchPixels))
                                .diskCacheKey("poster:${poster.id}:$url")
                                .build(),
                        )
                    }
                }
            }
    }
    Column {
        Column(
            modifier = Modifier.bringIntoViewRequester(rowBringIntoViewRequester),
            verticalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.ShelfTitleGap),
        ) {
            Text(
                text = row.title,
                modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                // Figma MovieBlock / Name — Medium ~20px, #E5E5E5
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE5E5E5),
            )
            CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                        vertical = SlugYardTvMetrics.ShelfRowVerticalPad,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
                ) {
                    itemsIndexed(row.posters, key = { _, poster -> "${row.id}:${poster.id}" }) { index, poster ->
                        PosterCard(
                            poster = poster,
                            smoothFocusMovement = smoothFocusMovement,
                            onClick = { onPosterSelected(poster) },
                            onLongClick = onPosterLongPress?.let { handler -> { handler(poster) } },
                            episodeLabel = if (row.id == "continue_watching") {
                                val se = episodeLabel(poster.season, poster.episode)
                                if (poster.nextUp) {
                                    se?.let { "Up next · $it" } ?: "Up next"
                                } else {
                                    se
                                }
                            } else {
                                null
                            },
                            summary = summaryLookup(poster.id) ?: poster.summary,
                            // Entry requester stays on index 0 for row-to-row landings; every index still
                            // gets the same vertical FocusRequester exits (LazyRow index-0 Up/Down bug).
                            focusRequester = if (index == 0) entryFocusRequester else null,
                            upFocusRequester = upFocusRequester,
                            downFocusRequester = downFocusRequester,
                            onFocusUp = onFocusUp,
                            onFocusDown = onFocusDown,
                            onPrepareFocusUp = onPrepareFocusUp,
                            onPrepareFocusDown = onPrepareFocusDown,
                            lockFocusLeft = index == 0,
                            lockFocusRight = index == row.posters.lastIndex,
                            onFocusedChanged = { focused ->
                                if (focused) {
                                    focusedPosterIndex = index
                                    // Keep shelf marked focused across left/right moves so hero
                                    // carousel stays paused (was clearing null between tiles).
                                    if (focusedRowIdState?.value != row.id) {
                                        focusedRowIdState?.value = row.id
                                        rowBringIntoViewJob?.cancel()
                                        rowBringIntoViewJob = rowScope.launch {
                                            // Single pin for nav clearance — scroll animation is duration 0.
                                            // focusCatalogRow only force-composes off-window shelves;
                                            // avoid a second delayed hop (that read as choppy Up/Down).
                                            rowBringIntoViewRequester.bringIntoView()
                                        }
                                    }
                                }
                            },
                            showTypeBadge = false,
                            expandOnFocus = expandOnFocus,
                            cardWidth = 176.dp,
                            cardHeight = 264.dp,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(HomeShelfTrailingGap))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PosterCard(
    poster: HomePoster,
    smoothFocusMovement: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocusUp: (() -> Unit)? = null,
    onFocusDown: (() -> Unit)? = null,
    onPrepareFocusUp: (suspend () -> Unit)? = null,
    onPrepareFocusDown: (suspend () -> Unit)? = null,
    /** When true, Left does not leave the shelf (first poster). */
    lockFocusLeft: Boolean = false,
    /** When true, Right does not leave the shelf into header/nav (last poster). */
    lockFocusRight: Boolean = false,
    cardWidth: Dp = 176.dp,
    cardHeight: Dp = 264.dp,
    showTypeBadge: Boolean = false,
    episodeLabel: String? = null,
    summary: String? = null,
    onFocusedChanged: ((Boolean) -> Unit)? = null,
    /** When false, focused cards stay portrait (search). Home uses landscape expand. */
    expandOnFocus: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var bringIntoViewJob by remember { mutableStateOf<Job?>(null) }
    val dpadNeighbors = LocalDpadVerticalNeighbors.current
    // Real layout width expand so the next portrait tile is pushed, not drawn underneath.
    // Frame is ~16:9 at the row height — backdrops are 16:9; a near-square crop looked "off-center".
    val focusedWidth = if (expandOnFocus) {
        (cardHeight.value * HomePosterLandscapeAspect).dp
    } else {
        cardWidth
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val posterPixels = remember(cardWidth, cardHeight, density) {
        homePortraitArtworkSize(density.density, cardWidth.value, cardHeight.value)
    }
    val posterDecode = remember(posterPixels) { posterPixels.toCoilSize() }
    val landscapePixels = remember(cardHeight, density) {
        homeLandscapeArtworkSize(density.density, cardHeight.value)
    }
    val landscapeDecode = remember(landscapePixels) { landscapePixels.toCoilSize() }
    // Prefer true poster art for the unfocused tile — never substitute backdrop here.
    val posterUrl = remember(poster.id, poster.imageUrl) {
        preferLargePosterUrl(poster.imageUrl)
    }
    val landscapeUrl = remember(poster.id, poster.backdropUrl, expandOnFocus) {
        if (!expandOnFocus) null
        else poster.backdropUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { preferCardBackdropUrl(it) }
            ?.takeIf { it != posterUrl }
    }
    // Seed painted from Coil memory when warm landed before this item recomposed (fast hop).
    var landscapePainted by remember(poster.id, landscapeUrl, landscapePixels) {
        mutableStateOf(
            landscapeUrl != null &&
                landscapeReadyInMemory(
                    context,
                    poster.id,
                    landscapeUrl,
                    landscapePixels,
                ),
        )
    }
    val wantsLandscape = expandOnFocus && landscapeUrl != null
    // ROOT CAUSE of grey sidebars on every shelf:
    // Expanding to 16:9 before landscape is painted leaves a portrait tile centered on
    // SurfaceElevated (grey bars). Expand and reveal land in the *same* frame so the only
    // transition is portrait → landscape, never portrait-in-a-wide-box.
    // Prefetch must make painted true before/at focus for a hitch-free hop; if not, stay
    // portrait-sized until Coil has land (still better UX than grey bars).
    val expandFrame = focused && wantsLandscape && landscapePainted
    val layoutWidth = if (expandFrame) focusedWidth else cardWidth
    val showLandscapeArt = expandFrame
    // Focus chrome only when expanded landscape is live.
    val showFocusChrome = expandFrame
    LaunchedEffect(focused) {
        onFocusedChanged?.invoke(focused)
    }
    // Catch-up: seed painted from memory before mounting AsyncImage, and if focused, decode this
    // tile so grey bars never span a multi-frame wait for a lazy AsyncImage enqueue.
    LaunchedEffect(focused, landscapeUrl, landscapePixels) {
        if (landscapeUrl == null) return@LaunchedEffect
        if (
            landscapeReadyInMemory(
                context,
                poster.id,
                landscapeUrl,
                landscapePixels,
            )
        ) {
            landscapePainted = true
            return@LaunchedEffect
        }
        if (!focused) return@LaunchedEffect
        val result = runCatching {
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(landscapeUrl)
                    .size(landscapeDecode)
                    .precision(coil3.size.Precision.EXACT)
                    .memoryCacheKey(homeBackdropMemoryCacheKey(poster.id, landscapeUrl, landscapePixels))
                    .diskCacheKey("card-backdrop:${poster.id}:$landscapeUrl")
                    .build(),
            )
        }.getOrNull()
        if (result?.image != null) {
            landscapePainted = true
        }
    }

    val focusMovementModifier = if (smoothFocusMovement && onFocusedChanged == null) {
        // Standalone cards (e.g. Search) bring themselves into view. Home shelves use the
        // row-level requester so the section title stays visible under the floating nav.
        Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                bringIntoViewJob?.cancel()
                bringIntoViewJob = null
                if (state.isFocused) {
                    bringIntoViewJob = scope.launch {
                        bringIntoViewRequester.bringIntoView()
                    }
                }
            }
    } else Modifier

    // One owner for vertical exits: scroll-then-focus callbacks when provided, else FocusRequesters.
    // Do not also leave focusProperties pointing at live requesters — that double-fires with this.
    val verticalNavModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp -> {
                when {
                    onFocusUp != null -> {
                        onFocusUp()
                        true
                    }
                    upFocusRequester != null -> {
                        upFocusRequester.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            Key.DirectionDown -> {
                when {
                    onFocusDown != null -> {
                        onFocusDown()
                        true
                    }
                    downFocusRequester != null -> {
                        downFocusRequester.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            else -> false
        }
    }

    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }

    val interactionModifier = if (onLongClick != null) {
        // D-pad long-press is owned by onPreviewKeyEvent below. Do not also use
        // combinedClickable(onLongClick) — that double-fires and races the options dialog.
        Modifier.clickable(
            interactionSource = interactions,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = interactions,
            indication = null,
            onClick = onClick,
        )
    }

    Column(
        modifier = focusMovementModifier
            .then(verticalNavModifier)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            // Cancel geometric Up/Down — verticalNavModifier owns a single move (avoids thrash).
            .verticalFocusExits(up = null, down = null)
            .horizontalFocusBounds(lockLeft = lockFocusLeft, lockRight = lockFocusRight)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    dpadNeighbors.up = upFocusRequester
                    dpadNeighbors.down = downFocusRequester
                    dpadNeighbors.upBringIntoView = onPrepareFocusUp
                    dpadNeighbors.downBringIntoView = onPrepareFocusDown
                } else if (
                    dpadNeighbors.up == upFocusRequester &&
                    dpadNeighbors.down == downFocusRequester
                ) {
                    dpadNeighbors.up = null
                    dpadNeighbors.down = null
                    dpadNeighbors.upBringIntoView = null
                    dpadNeighbors.downBringIntoView = null
                }
            }
            .zIndex(if (focused) 1f else 0f)
            .width(layoutWidth)
            .semantics {
                contentDescription = buildString {
                    append(poster.title)
                    episodeLabel?.let { append(". $it") }
                    if (onLongClick != null) {
                        append(". Hold Select or press Menu for more options")
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
            .then(interactionModifier),
    ) {
        Box(
            modifier = Modifier
                .size(width = layoutWidth, height = cardHeight)
                .then(
                    if (focused) {
                        Modifier.border(3.dp, SlugYardPalette.FocusRing, RoundedCornerShape(SlugYardTvMetrics.CardCornerRadius))
                    } else Modifier,
                )
                .clip(RoundedCornerShape(SlugYardTvMetrics.CardCornerRadius))
                .background(SlugYardPalette.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                poster.title,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelLarge,
                color = SlugYardPalette.OnCanvasMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            // Portrait always under the landscape layer. Never unmount it on a warm-flag guess —
            // only landscape alpha hides it after AsyncImage onSuccess. That kills the blank
            // "SurfaceElevated" flash when LRU dropped a prefetched bitmap.
            if (posterUrl != null) {
                composeKey(poster.id, posterUrl) {
                    var posterReloadToken by remember(poster.id, posterUrl) { mutableIntStateOf(0) }
                    val posterRequest = remember(poster.id, posterUrl, posterDecode, posterReloadToken) {
                        val cacheKey = homePosterMemoryCacheKey(poster.id, posterUrl, posterPixels)
                        ImageRequest.Builder(context)
                            .data(posterUrl)
                            .size(posterDecode)
                            .precision(coil3.size.Precision.EXACT)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey("poster:${poster.id}:$posterUrl")
                            .build()
                    }
                    AsyncImage(
                        model = posterRequest,
                        contentDescription = null,
                        // Portrait fills the unfocused frame. While focused-and-waiting we stay
                        // this size (no expand) so we never show portrait + grey side rails.
                        modifier = Modifier
                            .size(cardWidth, cardHeight)
                            .align(Alignment.Center),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        onError = {
                            val token = posterReloadToken
                            if (token >= HomePosterRetryMax) return@AsyncImage
                            scope.launch {
                                delay(HomePosterRetryDelayMs)
                                if (posterReloadToken == token) posterReloadToken = token + 1
                            }
                        },
                    )
                }
            } else if (!poster.backdropUrl.isNullOrBlank() && !expandOnFocus) {
                val fallbackUrl = remember(poster.id, poster.backdropUrl) {
                    preferLargePosterUrl(poster.backdropUrl)
                }
                if (fallbackUrl != null) {
                    var fallbackReloadToken by remember(poster.id, fallbackUrl) { mutableIntStateOf(0) }
                    val fallbackRequest = remember(poster.id, fallbackUrl, posterDecode, fallbackReloadToken) {
                        ImageRequest.Builder(context)
                            .data(fallbackUrl)
                            .size(posterDecode)
                            .precision(coil3.size.Precision.EXACT)
                            .memoryCacheKey(homePosterFallbackMemoryCacheKey(poster.id, fallbackUrl, posterPixels))
                            .build()
                    }
                    AsyncImage(
                        model = fallbackRequest,
                        contentDescription = null,
                        modifier = Modifier.size(cardWidth, cardHeight).align(Alignment.Center),
                        contentScale = ContentScale.Crop,
                        onError = {
                            val token = fallbackReloadToken
                            if (token >= HomePosterRetryMax) return@AsyncImage
                            scope.launch {
                                delay(HomePosterRetryDelayMs)
                                if (fallbackReloadToken == token) fallbackReloadToken = token + 1
                            }
                        },
                    )
                }
            }
            // Landscape mounts only while focused. Prefetch warms Coil memory for neighbors;
            // keeping a hidden AsyncImage on every composed tile was a major Up/Down cost
            // (N shelves × M cards × dual decode pipelines on mid-range TV SOCs).
            if (wantsLandscape && landscapeUrl != null && focused) {
                composeKey(poster.id, landscapeUrl) {
                    val landscapeRequest = remember(poster.id, landscapeUrl, landscapeDecode) {
                        val cacheKey = homeBackdropMemoryCacheKey(poster.id, landscapeUrl, landscapePixels)
                        ImageRequest.Builder(context)
                            .data(landscapeUrl)
                            .size(landscapeDecode)
                            // EXACT: INEXACT reuses tiny/wrong scales → soft mush under Crop.
                            .precision(coil3.size.Precision.EXACT)
                            .memoryCacheKey(cacheKey)
                            .diskCacheKey("card-backdrop:${poster.id}:$landscapeUrl")
                            .build()
                    }
                    AsyncImage(
                        model = landscapeRequest,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (showLandscapeArt) 1f else 0f),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        onSuccess = { landscapePainted = true },
                        onError = { landscapePainted = false },
                    )
                }
            }
            if (showFocusChrome) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(SlugYardPalette.Canvas.copy(alpha = 0.72f), Color.Transparent),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    poster.ratingLabel?.takeIf { it.isNotBlank() }?.let { score ->
                        LandscapeMetaChip(
                            label = buildString {
                                poster.ratingSource?.takeIf { it.isNotBlank() }?.let { append("$it ") }
                                append(score)
                            },
                        )
                    } ?: Spacer(modifier = Modifier.width(1.dp))
                    poster.contentGenres?.takeIf { it.isNotBlank() }?.let { genres ->
                        LandscapeMetaChip(label = genres)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, SlugYardPalette.Canvas.copy(alpha = 0.9f)),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            poster.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = QuicksandFamily,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = SlugYardPalette.OnCanvas,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        episodeLabel?.let { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = SlugYardPalette.Accent,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                        summary?.takeIf { it.isNotBlank() }?.let { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (focused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, SlugYardPalette.Canvas.copy(alpha = 0.88f)),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        episodeLabel?.let { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = SlugYardPalette.Accent,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                        summary?.takeIf { it.isNotBlank() }?.let { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            episodeLabel?.takeIf { !focused }?.let { label ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(SlugYardPalette.Canvas.copy(alpha = 0.88f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (showTypeBadge && !showFocusChrome) {
                contentTypeLabel(poster.contentType)?.let { typeLabel ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(SlugYardPalette.Accent)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            typeLabel.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF181818),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            poster.progressFraction?.let { progress ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(SlugYardPalette.Accent),
                )
            }
        }
    }
}


@Composable
private fun LandscapeMetaChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(SlugYardPalette.Canvas.copy(alpha = 0.78f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = SlugYardPalette.OnCanvas,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * True when the exact landscape bitmap is still present in Coil's memory cache.
 *
 * The request uses a custom size-aware `memoryCacheKey("card-backdrop:…:WxH")`, which Coil uses
 * verbatim as the `MemoryCache.Key`. Probing the cache at reveal time — instead of trusting the
 * row prefetcher's completion flag — is what closes the eviction race: a prefetch can succeed
 * and then be evicted under memory pressure before the neighbor gains focus.
 */
private fun landscapeReadyInMemory(
    context: android.content.Context,
    posterId: String,
    url: String,
    size: HomeArtworkPixelSize,
): Boolean =
    context.imageLoader.memoryCache?.get(
        MemoryCache.Key(homeBackdropMemoryCacheKey(posterId, url, size)),
    ) != null
