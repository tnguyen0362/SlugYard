package com.sluggyard.tv.ui.app.search

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.app.home.HomePoster
import com.sluggyard.tv.ui.app.home.PosterCard
import com.sluggyard.tv.ui.app.requestFocusReliably
import com.sluggyard.tv.ui.app.isMovieType
import com.sluggyard.tv.ui.app.isSeriesType
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long to let typing settle before firing a query, so every keystroke does not fan out a
 * request across every enabled addon search catalog. */
private const val SEARCH_DEBOUNCE_MS = 450L

// Match Home poster dimensions (176x264dp) so search results read at the same scale on TV.
private val SearchPosterWidth = 176.dp
private val SearchPosterHeight = 264.dp

sealed interface SearchResultState {
    data object Idle : SearchResultState
    data object Loading : SearchResultState
    data class Loaded(val posters: List<HomePoster>) : SearchResultState
    data class Error(val message: String) : SearchResultState
}

@Composable
fun SearchScreen(
    search: suspend (String) -> List<HomePoster>,
    onPosterSelected: (HomePoster) -> Unit,
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var resultState: SearchResultState by remember { mutableStateOf(SearchResultState.Idle) }
    var retryNonce by remember { mutableStateOf(0) }
    val fieldFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusScope = rememberCoroutineScope()
    val initialFocusRequester = contentFocusRequester ?: fieldFocusRequester

    LaunchedEffect(initialFocusRequester) {
        initialFocusRequester.requestFocusReliably(retries = 8)
    }

    LaunchedEffect(query, retryNonce) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            resultState = SearchResultState.Idle
            return@LaunchedEffect
        }
        resultState = SearchResultState.Loading
        delay(SEARCH_DEBOUNCE_MS)
        val startedAt = SystemClock.elapsedRealtime()
        ExperimentalDiagnostics.event(
            "search",
            "request_started",
            mapOf(
                "queryLength" to trimmed.length,
                "queryHash" to trimmed.hashCode().toUInt().toString(16),
                "retry" to retryNonce,
            ),
        )
        try {
            val posters = search(trimmed)
            resultState = SearchResultState.Loaded(posters)
            ExperimentalDiagnostics.event(
                "search",
                "request_completed",
                mapOf(
                    "durationMs" to SystemClock.elapsedRealtime() - startedAt,
                    "resultCount" to posters.size,
                    "queryLength" to trimmed.length,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            resultState = SearchResultState.Error("Search could not load. Check your connection and try again.")
            ExperimentalDiagnostics.failure(
                "search",
                "request_failed",
                failure,
                *mapOf(
                    "durationMs" to SystemClock.elapsedRealtime() - startedAt,
                    "queryLength" to trimmed.length,
                ).toList().toTypedArray(),
            )
        }
    }

    var fieldFocused by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Search",
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = SlugYardPalette.OnCanvas,
        )
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                ExperimentalDiagnostics.event("search", "query_changed", mapOf("queryLength" to it.length))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)
                .height(56.dp)
                .border(
                    width = if (fieldFocused) SlugYardTvMetrics.FocusRingWidth else 1.dp,
                    color = if (fieldFocused) SlugYardPalette.FocusRing else SlugYardPalette.Divider,
                    shape = fieldShape,
                )
                .focusRequester(initialFocusRequester)
                .onFocusChanged { state ->
                    fieldFocused = state.isFocused
                    // Android TV should not keep a soft keyboard owning DPAD; hide as soon as
                    // the field focuses so Down can reach Movies/TV results.
                    if (state.isFocused) keyboardController?.hide()
                }
                .then(
                    headerFocusRequester?.let { requester ->
                        Modifier.focusProperties { up = requester }
                    } ?: Modifier,
                )
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            val loaded = resultState as? SearchResultState.Loaded
                            if (loaded == null || loaded.posters.isEmpty()) {
                                false
                            } else {
                                // Soft keyboard on emulators/phones can swallow geometric Down;
                                // hide it and hand focus to the first result poster explicitly.
                                keyboardController?.hide()
                                focusScope.launch {
                                    firstResultFocusRequester.requestFocusReliably(retries = 8)
                                }
                                true
                            }
                        }
                        Key.DirectionUp -> {
                            if (headerFocusRequester != null) {
                                headerFocusRequester.requestFocus()
                            } else {
                                focusManager.moveFocus(FocusDirection.Up)
                            }
                            true
                        }
                        Key.Back -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                color = SlugYardPalette.OnCanvas,
            ),
            placeholder = {
                Text(
                    "Search movies & TV",
                    style = MaterialTheme.typography.titleMedium,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = SlugYardPalette.OnCanvasMuted)
            },
            shape = fieldShape,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = SlugYardPalette.SurfaceElevated,
                unfocusedContainerColor = SlugYardPalette.Surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = SlugYardPalette.Accent,
                focusedTextColor = SlugYardPalette.OnCanvas,
                unfocusedTextColor = SlugYardPalette.OnCanvas,
                focusedLeadingIconColor = SlugYardPalette.OnCanvas,
                unfocusedLeadingIconColor = SlugYardPalette.OnCanvasMuted,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = resultState) {
                is SearchResultState.Idle -> SearchHint()
                is SearchResultState.Loading -> SearchLoading()
                is SearchResultState.Error -> SearchError(state.message) {
                    ExperimentalDiagnostics.event("search", "retry_clicked", mapOf("queryLength" to query.length))
                    retryNonce++
                }
                is SearchResultState.Loaded -> if (state.posters.isEmpty()) {
                    SearchEmpty(query)
                } else {
                    SearchResultsSections(
                        posters = state.posters,
                        onPosterSelected = { poster ->
                            ExperimentalDiagnostics.event(
                                "search",
                                "result_selected",
                                mapOf(
                                    "contentType" to poster.contentType,
                                    "season" to poster.season,
                                    "episode" to poster.episode,
                                    "hasAddon" to (poster.addonId != null),
                                ),
                            )
                            onPosterSelected(poster)
                        },
                        firstFocusRequester = firstResultFocusRequester,
                        queryFieldFocusRequester = initialFocusRequester,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsSections(
    posters: List<HomePoster>,
    onPosterSelected: (HomePoster) -> Unit,
    firstFocusRequester: FocusRequester,
    queryFieldFocusRequester: FocusRequester,
) {
    val movies = remember(posters) {
        posters.filter { isMovieType(it.contentType) || !isSeriesType(it.contentType) }
    }
    val shows = remember(posters) {
        posters.filter { isSeriesType(it.contentType) }
    }
    val showBothSections = movies.isNotEmpty() && shows.isNotEmpty()
    // The query field's DPAD_DOWN lands on whichever section renders first; the second section
    // needs its own entry requester so Movies -> TV Shows is reachable at all.
    val moviesEntryFocusRequester = remember { FocusRequester() }
    val showsEntryFocusRequester = remember { FocusRequester() }
    val moviesEntry = if (movies.isNotEmpty()) firstFocusRequester else moviesEntryFocusRequester
    val showsEntry = if (movies.isEmpty()) firstFocusRequester else showsEntryFocusRequester
    val listState = rememberLazyListState()
    val focusScope = rememberCoroutineScope()
    val showsRowItemIndex = if (movies.isEmpty()) 0 else 2

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (showBothSections) 6.dp else 12.dp),
    ) {
        if (movies.isNotEmpty()) {
            item(key = "section_movies_header") {
                Text(
                    "Movies",
                    modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SlugYardPalette.OnCanvas,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item(key = "section_movies_row") {
                SearchPosterRow(
                    posters = movies,
                    sectionKey = "movie",
                    onPosterSelected = onPosterSelected,
                    firstFocusRequester = moviesEntry,
                    upFocusRequester = queryFieldFocusRequester,
                    onFocusDown = if (shows.isEmpty()) {
                        null
                    } else {
                        {
                            focusScope.launch {
                                listState.scrollToItem(showsRowItemIndex)
                                showsEntry.requestFocusReliably(retries = 8)
                            }
                            Unit
                        }
                    },
                    compactVerticalPadding = showBothSections,
                )
            }
        }
        if (shows.isNotEmpty()) {
            item(key = "section_shows_header") {
                Text(
                    "TV Shows",
                    modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
                    style = MaterialTheme.typography.headlineSmall,
                    color = SlugYardPalette.OnCanvas,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item(key = "section_shows_row") {
                SearchPosterRow(
                    posters = shows,
                    sectionKey = "show",
                    onPosterSelected = onPosterSelected,
                    firstFocusRequester = showsEntry,
                    onFocusUp = if (movies.isEmpty()) {
                        null
                    } else {
                        {
                            focusScope.launch {
                                listState.scrollToItem(0)
                                moviesEntry.requestFocusReliably(retries = 8)
                            }
                            Unit
                        }
                    },
                    upFocusRequester = if (movies.isEmpty()) queryFieldFocusRequester else null,
                    compactVerticalPadding = showBothSections,
                )
            }
        }
    }
}

@Composable
private fun SearchPosterRow(
    posters: List<HomePoster>,
    sectionKey: String,
    onPosterSelected: (HomePoster) -> Unit,
    firstFocusRequester: FocusRequester?,
    upFocusRequester: FocusRequester? = null,
    onFocusUp: (() -> Unit)? = null,
    onFocusDown: (() -> Unit)? = null,
    compactVerticalPadding: Boolean = false,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        contentPadding = PaddingValues(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = if (compactVerticalPadding) 10.dp else 14.dp,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(posters, key = { _, poster -> "$sectionKey:${poster.id}" }) { index, poster ->
            PosterCard(
                poster = poster,
                smoothFocusMovement = false,
                onClick = { onPosterSelected(poster) },
                showTypeBadge = true,
                expandOnFocus = false,
                cardWidth = SearchPosterWidth,
                cardHeight = SearchPosterHeight,
                focusRequester = if (index == 0) firstFocusRequester else null,
                upFocusRequester = upFocusRequester,
                onFocusUp = onFocusUp,
                onFocusDown = onFocusDown,
            )
        }
    }
}

@Composable
private fun SearchHint() {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 8.dp)) {
        Text(
            "Start typing to search across your installed addons.",
            style = MaterialTheme.typography.bodyLarge,
            color = SlugYardPalette.OnCanvasMuted,
        )
    }
}

@Composable
private fun SearchLoading() {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)) {
        CircularProgressIndicator(color = SlugYardPalette.Accent)
    }
}

@Composable
private fun SearchEmpty(query: String) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 8.dp)) {
        Text(
            "No results for \"${query.trim()}\".",
            style = MaterialTheme.typography.titleMedium,
            color = SlugYardPalette.OnCanvasMuted,
        )
    }
}

@Composable
private fun SearchError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvasMuted)
        TvButton(
            label = "Retry",
            onClick = onRetry,
            style = ButtonStyle.Primary,
        )
    }
}
