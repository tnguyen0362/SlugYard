package com.sluggyard.tv.ui.rewrite.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster
import com.sluggyard.tv.ui.rewrite.home.RewritePosterCard
import com.sluggyard.tv.ui.rewrite.requestFocusReliably
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/** How long to let typing settle before firing a query, so every keystroke doesn't fan out a
 * request across every enabled addon's search catalog. */
private const val SEARCH_DEBOUNCE_MS = 450L

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
    // These navigate to a real filtered catalog (the same Browse screen used elsewhere), unlike
    // the old behavior where "Trending Shows" etc. literally typed that phrase into the text
    // search field and full-text-searched it -- returning unrelated junk that happened to match
    // those words (e.g. wrestling videos, comedy specials).
    onBrowseMovies: () -> Unit,
    onBrowseShows: () -> Unit,
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
    val initialFocusRequester = contentFocusRequester ?: fieldFocusRequester

    BackHandler(onBack = onBack)

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
            .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .focusRequester(initialFocusRequester)
                .then(
                    headerFocusRequester?.let { requester ->
                        Modifier.focusProperties { up = requester }
                    } ?: Modifier,
                )
                // Keep vertical D-pad movement deterministic while the text field owns typing.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            focusManager.moveFocus(FocusDirection.Down)
                            true
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

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SearchCategoryRail(
                onBrowseMovies = onBrowseMovies,
                onBrowseShows = onBrowseShows,
                modifier = Modifier.width(240.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (val state = resultState) {
                    is RewriteSearchResultState.Idle -> SearchHint()
                    is RewriteSearchResultState.Loading -> SearchLoading()
                    is RewriteSearchResultState.Error -> SearchError(state.message) { retryNonce++ }
                    is RewriteSearchResultState.Loaded -> if (state.posters.isEmpty()) {
                        SearchEmpty(query)
                    } else {
                        SearchResultsGrid(
                            posters = state.posters,
                            onPosterSelected = onPosterSelected,
                            firstFocusRequester = firstResultFocusRequester,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCategoryRail(
    onBrowseMovies: () -> Unit,
    onBrowseShows: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val browseItems = listOf("Movies" to onBrowseMovies, "TV Shows" to onBrowseShows)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(5.dp))
            .background(SlugYardPalette.Surface),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Text(
                "Browse",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                color = SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        items(browseItems, key = { it.first }) { (label, onSelect) ->
            Text(
                text = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlugYardPalette.Surface)
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                color = SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SearchResultsGrid(
    posters: List<RewriteHomePoster>,
    onPosterSelected: (RewriteHomePoster) -> Unit,
    firstFocusRequester: FocusRequester,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = 4.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        verticalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(posters, key = { _, poster -> poster.id }) { index, poster ->
            RewritePosterCard(
                poster = poster,
                // Search previously hardcoded this to false, so focus never scrolled the grid
                // into view -- pressing D-pad down from the search field looked like nothing
                // happened at all. Matches the Home rows' behavior now.
                smoothFocusMovement = true,
                onClick = { onPosterSelected(poster) },
                focusRequester = if (index == 0) firstFocusRequester else null,
                cardWidth = 132.dp,
                cardHeight = 198.dp,
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
