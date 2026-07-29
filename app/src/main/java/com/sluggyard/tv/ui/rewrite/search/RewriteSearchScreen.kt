package com.sluggyard.tv.ui.rewrite.search

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
import androidx.compose.material3.Button
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
import com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster
import com.sluggyard.tv.ui.rewrite.home.RewritePosterCard
import com.sluggyard.tv.ui.rewrite.requestFocusReliably
import com.sluggyard.tv.ui.rewrite.rewriteIsMovieType
import com.sluggyard.tv.ui.rewrite.rewriteIsSeriesType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long to let typing settle before firing a query, so every keystroke does not fan out a
 * request across every enabled addon search catalog. */
private const val SEARCH_DEBOUNCE_MS = 450L

private val SearchPosterWidth = 152.dp
private val SearchPosterHeight = 228.dp

sealed interface RewriteSearchResultState {
    data object Idle : RewriteSearchResultState
    data object Loading : RewriteSearchResultState
    data class Loaded(val posters: List<RewriteHomePoster>) : RewriteSearchResultState
    data class Error(val message: String) : RewriteSearchResultState
}

@Composable
fun RewriteSearchScreen(
    search: suspend (String) -> List<RewriteHomePoster>,
    onPosterSelected: (RewriteHomePoster) -> Unit,
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var resultState: RewriteSearchResultState by remember { mutableStateOf(RewriteSearchResultState.Idle) }
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
            resultState = RewriteSearchResultState.Idle
            return@LaunchedEffect
        }
        resultState = RewriteSearchResultState.Loading
        delay(SEARCH_DEBOUNCE_MS)
        try {
            resultState = RewriteSearchResultState.Loaded(search(trimmed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            resultState = RewriteSearchResultState.Error("Search could not load. Check your connection and try again.")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)
                .height(52.dp)
                .focusRequester(initialFocusRequester)
                .onFocusChanged { state ->
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
                            val loaded = resultState as? RewriteSearchResultState.Loaded
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
            textStyle = MaterialTheme.typography.titleMedium,
            placeholder = { Text("Search movies & TV") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = RoundedCornerShape(5.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = SlugYardPalette.SurfaceElevated,
                unfocusedContainerColor = SlugYardPalette.Surface,
                focusedIndicatorColor = SlugYardPalette.OnCanvas,
                unfocusedIndicatorColor = SlugYardPalette.OnCanvasMuted,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = resultState) {
                is RewriteSearchResultState.Idle -> SearchHint()
                is RewriteSearchResultState.Loading -> SearchLoading()
                is RewriteSearchResultState.Error -> SearchError(state.message) { retryNonce++ }
                is RewriteSearchResultState.Loaded -> if (state.posters.isEmpty()) {
                    SearchEmpty(query)
                } else {
                    SearchResultsSections(
                        posters = state.posters,
                        onPosterSelected = onPosterSelected,
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
    posters: List<RewriteHomePoster>,
    onPosterSelected: (RewriteHomePoster) -> Unit,
    firstFocusRequester: FocusRequester,
    queryFieldFocusRequester: FocusRequester,
) {
    val movies = remember(posters) {
        posters.filter { rewriteIsMovieType(it.contentType) || !rewriteIsSeriesType(it.contentType) }
    }
    val shows = remember(posters) {
        posters.filter { rewriteIsSeriesType(it.contentType) }
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
                    style = MaterialTheme.typography.titleLarge,
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
                    style = MaterialTheme.typography.titleLarge,
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
    posters: List<RewriteHomePoster>,
    sectionKey: String,
    onPosterSelected: (RewriteHomePoster) -> Unit,
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
            RewritePosterCard(
                poster = poster,
                smoothFocusMovement = false,
                onClick = { onPosterSelected(poster) },
                showTypeBadge = false,
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
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)) {
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
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset)) {
        Text(
            "No results for \"${query.trim()}\".",
            style = MaterialTheme.typography.bodyLarge,
            color = SlugYardPalette.OnCanvasMuted,
        )
    }
}

@Composable
private fun SearchError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvasMuted)
        Button(onClick = onRetry) { Text("Retry") }
    }
}
