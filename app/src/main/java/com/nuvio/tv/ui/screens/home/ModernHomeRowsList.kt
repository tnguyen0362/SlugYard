package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.LocalHomeHeaderFocusRequester

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.ui.ExperimentalComposeUiApi
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableMap
import com.nuvio.tv.ui.util.StableRef
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    kotlinx.coroutines.FlowPreview::class
)
@Composable
internal fun ModernHomeRowsList(
    isVerticalRowsScrollingState: State<Boolean>,
    carouselRows: StableList<HeroCarouselRow>,
    verticalRowListState: LazyListState,
    focusedItemByRow: StableRef<MutableMap<String, Int>>,
    rowListStates: StableRef<MutableMap<String, LazyListState>>,
    loadMoreRequestedTotals: StableRef<MutableMap<String, Int>>,
    focusState: HomeScreenFocusState,
    activeRowKey: State<String?>,
    activeItemIndex: State<Int>,
    isFastScrolling: State<Boolean>,
    onFastScrollingChanged: (Boolean) -> Unit,
    contentFocusRequester: FocusRequester,
    rowsViewportHeight: Dp,
    catalogTopPadding: Dp,
    catalogBottomPadding: Dp,
    trailerContentAlpha: () -> Float,
    verticalRowBringIntoViewSpec: BringIntoViewSpec,
    onRowItemFocusedInternal: (String, Int, Boolean) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    onRequestLazyCatalogLoad: (String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    enrichedPreviews: State<StableMap<String, MetaPreview>>,
    trailerPreviewUrls: StableMap<String, String>,
    trailerPreviewAudioUrls: StableMap<String, String>,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: State<String?>,
    expandedTrailerPreviewUrl: () -> String?,
    expandedTrailerPreviewAudioUrl: () -> String?,
    portraitCatalogCardWidth: Dp,
    portraitCatalogCardHeight: Dp,
    landscapeCatalogCardWidth: Dp,
    landscapeCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    pendingRowFocusKey: State<String?>,
    pendingRowFocusIndex: State<Int?>,
    pendingRowFocusNonce: State<Int>,
    onPendingRowFocusCleared: () -> Unit,
    onActiveRowKeyChange: (String?) -> Unit,
    onActiveItemIndexChange: (Int) -> Unit,
    lastHeroNavigationAtMs: State<Long>,
    onLastHeroNavigationAtMsChange: (Long) -> Unit,
    onHeroFocusSettleDelayChange: (Long) -> Unit,
    lastFocusedContinueWatchingIndex: State<Int>,
    onLastFocusedContinueWatchingIndexChange: (Int) -> Unit,
    focusedCatalogSelection: State<FocusedCatalogSelection?>,
    onFocusedCatalogSelectionChange: (FocusedCatalogSelection?) -> Unit,
    focusedHeroMediaNonce: State<Int>,
    onFocusedHeroMediaNonceChange: (Int) -> Unit,
    onExpansionInteractionNonceChange: (Int) -> Unit,
    blockLeftOnFirstExpandedItem: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Unwrap StableRef wrappers for internal use (not passed to child composables)
    val focusedItemByRowMap = focusedItemByRow.value
    val rowListStatesMap = rowListStates.value
    val loadMoreRequestedTotalsMap = loadMoreRequestedTotals.value

    val latestOnActiveRowKeyChange = rememberUpdatedState(onActiveRowKeyChange)
    val latestOnActiveItemIndexChange = rememberUpdatedState(onActiveItemIndexChange)

    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val stableItemFocusRequestersByRow = remember { mutableMapOf<String, StableRef<MutableMap<Int, FocusRequester>>>() }
    val focusScope = rememberCoroutineScope()
    var rowFocusJob by remember { mutableStateOf<Job?>(null) }
    var directContentFocus by remember { mutableStateOf(false) }
    val homeHeaderFocusRequester = LocalHomeHeaderFocusRequester.current

    val density = LocalDensity.current
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val verticalPrefetchImageLoader = context.imageLoader
    val latestCarouselRowsForImagePrefetch = rememberUpdatedState(carouselRows)

    LaunchedEffect(
        verticalPrefetchImageLoader,
        verticalRowListState,
        density,
        useLandscapePosters,
        effectiveExpandEnabled,
        portraitCatalogCardWidth,
        portraitCatalogCardHeight,
        landscapeCatalogCardWidth,
        landscapeCatalogCardHeight
    ) {
        val prefetchAheadRows = 1
        // Limit background decode work while focus-driven navigation is active.
        val prefetchItemsPerRow = 2
        snapshotFlow {
            verticalRowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .debounce(120L) // VERTICAL_PREFETCH_DEBOUNCE_MS
            .collect { lastVisibleRowIndex ->
                withContext(Dispatchers.IO) {
                    for (rowOffset in 1..prefetchAheadRows) {
                        val row = latestCarouselRowsForImagePrefetch.value.list
                            .getOrNull(lastVisibleRowIndex + rowOffset) ?: continue
                        for (i in 0 until minOf(prefetchItemsPerRow, row.items.list.size)) {
                            val item = row.items.list[i]
                            val url = item.imageUrl ?: continue
                            val metrics = item.catalogCardRequestMetrics(
                                useLandscapePosters = useLandscapePosters,
                                portraitCardWidth = portraitCatalogCardWidth,
                                portraitCardHeight = portraitCatalogCardHeight,
                                landscapeCardWidth = landscapeCatalogCardWidth,
                                landscapeCardHeight = landscapeCatalogCardHeight,
                                expandEnabled = effectiveExpandEnabled
                            )
                            val wPx = with(density) { metrics.width.roundToPx() }
                            val hPx = with(density) { metrics.height.roundToPx() }
                            if (wPx <= 0 || hPx <= 0) continue
                            val cacheKey = "${url}_${wPx}x${hPx}"
                            if (verticalPrefetchImageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null) continue
                            verticalPrefetchImageLoader.enqueue(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .memoryCacheKey(cacheKey)
                                    .size(width = wPx, height = hPx)
                                    .build()
                            )
                        }
                    }
                }
            }
    }

    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    val latestCarouselRowsForLazy = rememberUpdatedState(carouselRows)
    LaunchedEffect(verticalRowListState) {
        val prefetchAheadForLazy = 1
        snapshotFlow {
            val scrolling = verticalRowListState.isScrollInProgress
            val info = verticalRowListState.layoutInfo
            val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(scrolling, firstVisible, lastVisible)
        }.collect { (scrolling, firstVisible, lastVisible) ->
            if (scrolling || lastVisible < 0) return@collect
            delay(150)
            if (verticalRowListState.isScrollInProgress) return@collect
            val rows = latestCarouselRowsForLazy.value
            for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + prefetchAheadForLazy)) {
                val row = rows.list.getOrNull(idx) ?: continue
                if (row.isLoading && row.items.list.firstOrNull()?.imageUrl == "placeholder://empty") {
                    latestOnRequestLazyCatalogLoad.value(row.key)
                }
            }
        }
    }

    // Secondary trigger: when scroll settles after focus-driven BringIntoView,
    // check again for placeholder rows that need loading. The primary snapshotFlow
    // above may miss this if visible indices didn't change.
    LaunchedEffect(verticalRowListState) {
        snapshotFlow { verticalRowListState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) return@collect
                delay(200)
                if (verticalRowListState.isScrollInProgress) return@collect
                val rows = latestCarouselRowsForLazy.value
                val info = verticalRowListState.layoutInfo
                val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: return@collect
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + 1)) {
                    val row = rows.list.getOrNull(idx) ?: continue
                    if (row.isLoading && row.items.list.firstOrNull()?.imageUrl == "placeholder://empty") {
                        latestOnRequestLazyCatalogLoad.value(row.key)
                    }
                }
            }
    }

    val focusRestorerRequester = remember(activeRowKey) {
        {
            activeRowKey.value?.let { rowFocusRequesters[it] } ?: FocusRequester.Default
        }
    }

    // TV focus search can jump to the app chrome when a LazyRow above is no
    // longer composed.  Make row-to-row movement explicit once a browse row
    // owns focus, while preserving the header as the only Up destination from
    // the first row.
    fun moveFocusToAdjacentRow(direction: Int): Boolean {
        val currentIndex = carouselRows.list.indexOfFirst { it.key == activeRowKey.value }
        val targetIndex = currentIndex + direction
        val targetRow = carouselRows.list.getOrNull(targetIndex) ?: return false
        val savedIndex = (focusedItemByRowMap[targetRow.key] ?: 0)
            .coerceIn(0, (targetRow.items.list.size - 1).coerceAtLeast(0))
        // D-pad repeats can arrive while a prior bring-into-view animation is
        // still active. Keep one job so animations cannot accumulate.
        rowFocusJob?.cancel()
        latestOnActiveRowKeyChange.value(targetRow.key)
        latestOnActiveItemIndexChange.value(savedIndex)
        rowFocusJob = focusScope.launch {
            verticalRowListState.animateScrollToItem(targetIndex)
            // The target row may only enter composition after the scroll.
            delay(48L)
            val itemRequester = stableItemFocusRequestersByRow[targetRow.key]
                ?.value
                ?.get(savedIndex)
            runCatching {
                (itemRequester ?: rowFocusRequesters[targetRow.key])?.requestFocus()
            }
        }
        return true
    }

    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current

    // The header sits above an intentionally full-screen hero.  Native focus
    // search cannot reach a rail that is below that first viewport, so a Down
    // press hands focus to this container.  Bring the first rail on screen and
    // then focus its saved card; subsequent movement remains ordinary rail
    // navigation.
    LaunchedEffect(directContentFocus, carouselRows) {
        if (!directContentFocus) return@LaunchedEffect
        val firstRow = carouselRows.list.firstOrNull() ?: return@LaunchedEffect
        val topPaddingPx = with(density) { catalogTopPadding.roundToPx() }
        verticalRowListState.scrollToItem(0, topPaddingPx)
        delay(32L)
        val targetIndex = (focusedItemByRowMap[firstRow.key] ?: 0)
            .coerceIn(0, (firstRow.items.list.size - 1).coerceAtLeast(0))
        runCatching {
            stableItemFocusRequestersByRow[firstRow.key]
                ?.value
                ?.get(targetIndex)
                ?.requestFocus()
                ?: rowFocusRequesters[firstRow.key]?.requestFocus()
        }
    }

    val sharedPlaceholderShimmerOffsetState = rememberPlaceholderShimmerOffsetState(label = "sharedRowShimmer")

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec,
        LocalFastScrollActive provides isFastScrolling,
        LocalVerticalRowsScrolling provides isVerticalRowsScrollingState
    ) {
        LazyColumn(
            state = verticalRowListState,
            modifier = modifier
                .fillMaxWidth()
                .recompositionHighlighter()
                .height(rowsViewportHeight)
                .padding(bottom = catalogBottomPadding)
                .clipToBounds()
                .graphicsLayer { alpha = trailerContentAlpha() }
                .focusRequester(contentFocusRequester)
                .onFocusChanged { focusState -> directContentFocus = focusState.isFocused }
                .focusable()
                .focusRestorer { focusRestorerRequester() }
                .onPreviewKeyEvent { event ->
                    val firstRowKey = carouselRows.list.firstOrNull()?.key
                    val lastRowKey = carouselRows.list.lastOrNull()?.key
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp &&
                        verticalRowListState.firstVisibleItemIndex == 0 &&
                        verticalRowListState.firstVisibleItemScrollOffset > 0
                    ) {
                        // Restore the feature viewport first. Previously this
                        // jumped directly from the first rail to the header,
                        // skipping the hero while moving back up Home. This
                        // must use scroll position rather than the active row:
                        // Continue Watching can be inserted ahead of catalogs.
                        rowFocusJob?.cancel()
                        rowFocusJob = focusScope.launch {
                            verticalRowListState.animateScrollToItem(0, 0)
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp &&
                        activeRowKey.value == firstRowKey
                    ) {
                        // Once the hero is visible, the next Up returns to the
                        // actual Home tab. Header actions (including Settings)
                        // are therefore always reachable with the remote.
                        runCatching { homeHeaderFocusRequester.requestFocus() }
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp &&
                        activeRowKey.value != null && activeRowKey.value != firstRowKey
                    ) {
                        return@onPreviewKeyEvent moveFocusToAdjacentRow(direction = -1)
                    }
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown &&
                        activeRowKey.value != null && activeRowKey.value != lastRowKey
                    ) {
                        return@onPreviewKeyEvent moveFocusToAdjacentRow(direction = 1)
                    }
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionUp &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeRowKey.value == firstRowKey
                    ) return@onPreviewKeyEvent true
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeRowKey.value == lastRowKey
                    ) return@onPreviewKeyEvent true
                    val blockKey = if (layoutDirection == LayoutDirection.Rtl)
                        Key.DirectionRight else Key.DirectionLeft
                    if (blockLeftOnFirstExpandedItem &&
                        event.type == KeyEventType.KeyDown &&
                        event.key == blockKey &&
                        effectiveExpandEnabled &&
                        expandedCatalogFocusKey.value != null &&
                        activeItemIndex.value == 0
                    ) return@onPreviewKeyEvent true
                    false
                }
                .dpadVerticalFastScroll(
                    scrollableState = verticalRowListState,
                    onFastScrollingChanged = onFastScrollingChanged,
                    shouldHaltForward = {
                        val info = verticalRowListState.layoutInfo
                        val lastIdx = carouselRows.list.size - 1
                        val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == lastIdx }
                        lastIdx >= 0 && lastVisible != null &&
                            lastVisible.offset + lastVisible.size <= info.viewportEndOffset
                    },
                    resolveVerticalLanding = { sign ->
                        val layoutInfo = verticalRowListState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        val lastIdx = carouselRows.list.size - 1
                        val viewportEnd = layoutInfo.viewportEndOffset
                        val lastRowAtBottom = lastIdx >= 0 &&
                            visibleItems.lastOrNull { it.index == lastIdx }?.let {
                                it.offset + it.size <= viewportEnd
                            } == true
                        val upwardTopRow: LazyListItemInfo? = if (sign < 0) {
                            visibleItems.firstOrNull()?.takeIf {
                                it.offset > -it.size / 2
                            }
                        } else null
                        val targetRowIndex = when {
                            lastRowAtBottom -> lastIdx
                            upwardTopRow != null -> upwardTopRow.index
                            else ->
                                visibleItems.firstOrNull { it.offset >= 0 }?.index
                                    ?: visibleItems.firstOrNull()?.index
                                    ?: verticalRowListState.firstVisibleItemIndex
                        }
                        val targetRow = carouselRows.list.getOrNull(targetRowIndex)
                        if (targetRow == null) null
                        else {
                            val savedIdx = (focusedItemByRowMap[targetRow.key] ?: 0)
                                .coerceIn(0, (targetRow.items.list.size - 1).coerceAtLeast(0))
                            latestOnActiveRowKeyChange.value(targetRow.key)
                            latestOnActiveItemIndexChange.value(savedIdx)

                            val targetItemKey = targetRow.items.list.getOrNull(savedIdx)?.key
                                ?: "${targetRow.key}_$savedIdx"
                            targetItemKey
                        }
                    },
                ),
            // The feature and genre shelf occupy the first viewport above the
            // rails. Keep that space in this same LazyColumn so a normal
            // vertical scroll moves the entire home surface as one feed.
            contentPadding = PaddingValues(
                top = catalogTopPadding,
                bottom = rowsViewportHeight
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            itemsIndexed(
                items = carouselRows.list,
                key = { index, row -> "${row.key}_$index" },
                contentType = { _, row -> row.apiType ?: "modern_home_row" }
            ) { _, row ->
                val stableOnContinueWatchingOptions = remember(onContinueWatchingOptions) {
                    { item: ContinueWatchingItem -> onContinueWatchingOptions(item) }
                }
                val stableOnRowItemFocused = remember {
                    { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                        val rowBecameActive = activeRowKey.value != rowKey
                        val itemChanged = activeItemIndex.value != index
                        
                        if (rowBecameActive || itemChanged) {
                            val now = System.currentTimeMillis()
                            val timeSinceLastHeroNav = now - lastHeroNavigationAtMs.value
                            onHeroFocusSettleDelayChange(
                                if (lastHeroNavigationAtMs.value != 0L &&
                                    timeSinceLastHeroNav in 1 until 130L // MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                                ) 400L // MODERN_HERO_RAPID_NAV_SETTLE_MS
                                else 450L // MODERN_HERO_FOCUS_DEBOUNCE_MS
                            )
                            onLastHeroNavigationAtMsChange(now)
                            onActiveRowKeyChange(rowKey)
                            onActiveItemIndexChange(index)
                        }

                        // Always keep the focusedItemByRow map in sync for ALL rows
                        if (focusedItemByRowMap[rowKey] != index) {
                            focusedItemByRowMap[rowKey] = index
                        }

                        if (isContinueWatchingRow) {
                            if (lastFocusedContinueWatchingIndex.value != index) {
                                onLastFocusedContinueWatchingIndexChange(index)
                            }
                            if (focusedCatalogSelection.value != null) {
                                onFocusedCatalogSelectionChange(null)
                            }
                        }
                        onRowItemFocusedInternal(rowKey, index, isContinueWatchingRow)
                    }
                }
                val isActiveRowLambda = remember(row.key) {
                    { row.key == activeRowKey.value }
                }
                val stableOnCatalogSelectionFocused = remember {
                    { selection: FocusedCatalogSelection ->
                        val isCollectionFolder = selection.payload is ModernPayload.CollectionFolder
                        if (focusedCatalogSelection.value != selection || isCollectionFolder) {
                            onFocusedCatalogSelectionChange(selection)
                            if (isCollectionFolder) {
                                onFocusedHeroMediaNonceChange(focusedHeroMediaNonce.value + 1)
                            }
                        }
                    }
                }
                ModernRowSection(
                    row = row,
                    isActiveRow = isActiveRowLambda,
                    rowFocusRequester = rowFocusRequesters.getOrPut(row.key) { FocusRequester() },
                    rowTitleBottom = 14.dp, // rowTitleBottom
                    defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                    focusStateCatalogRowScrollIndex = focusState.catalogRowScrollStates[row.key] ?: 0,
                    focusedItemByRow = focusedItemByRow,
                    rowListStates = rowListStates,
                    loadMoreRequestedTotals = loadMoreRequestedTotals,
                    pendingRowFocusKey = pendingRowFocusKey,
                    pendingRowFocusIndex = pendingRowFocusIndex,
                    pendingRowFocusNonce = pendingRowFocusNonce,
                    onPendingRowFocusCleared = onPendingRowFocusCleared,
                    onRowItemFocused = stableOnRowItemFocused,
                    useLandscapePosters = useLandscapePosters,
                    showLabels = showLabels,
                    posterCardCornerRadius = posterCardCornerRadius,
                    focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                    effectiveExpandEnabled = effectiveExpandEnabled,
                    effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                    trailerPlaybackTarget = trailerPlaybackTarget,
                    expandedCatalogFocusKey = expandedCatalogFocusKey,
                    expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                    expandedTrailerPreviewAudioUrl = expandedTrailerPreviewAudioUrl,
                    portraitCatalogCardWidth = portraitCatalogCardWidth,
                    portraitCatalogCardHeight = portraitCatalogCardHeight,
                    landscapeCatalogCardWidth = landscapeCatalogCardWidth,
                    landscapeCatalogCardHeight = landscapeCatalogCardHeight,
                    continueWatchingCardWidth = continueWatchingCardWidth,
                    continueWatchingCardHeight = continueWatchingCardHeight,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    onContinueWatchingClick = onContinueWatchingClick,
                    onContinueWatchingOptions = stableOnContinueWatchingOptions,
                    isCatalogItemWatched = isCatalogItemWatched,
                    onCatalogItemLongPress = onCatalogItemLongPress,
                    onItemFocus = onItemFocus,
                    onPreloadAdjacentItem = onPreloadAdjacentItem,
                    enrichedPreviews = enrichedPreviews,
                    onCatalogSelectionFocused = stableOnCatalogSelectionFocused,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToFolderDetail = onNavigateToFolderDetail,
                    onLoadMoreCatalog = onLoadMoreCatalog,
                    onBackdropInteraction = onBackdropInteraction,
                    onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange,
                    sharedPlaceholderShimmerOffsetState = sharedPlaceholderShimmerOffsetState,
                    isVerticalRowsScrollingState = isVerticalRowsScrollingState,
                    itemFocusRequesters = stableItemFocusRequestersByRow.getOrPut(row.key) {
                        StableRef(mutableMapOf())
                    }
                )
            }
        }
    }
}
