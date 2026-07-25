package com.sluggyard.tv.ui.rewrite.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster
import com.sluggyard.tv.ui.rewrite.home.RewritePosterCard
import com.sluggyard.tv.ui.rewrite.requestFocusReliably
import kotlinx.coroutines.delay

/** How long to let typing settle before firing a query, so every keystroke doesn't fan out a
 * request across every enabled addon's search catalog. */
private const val SEARCH_DEBOUNCE_MS = 450L

sealed interface RewriteSearchResultState {
    data object Idle : RewriteSearchResultState
    data object Loading : RewriteSearchResultState
    data class Loaded(val posters: List<RewriteHomePoster>) : RewriteSearchResultState
}

@Composable
fun RewriteSearchScreen(
    search: suspend (String) -> List<RewriteHomePoster>,
    onPosterSelected: (RewriteHomePoster) -> Unit,
    showPosterLabels: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var resultState: RewriteSearchResultState by remember { mutableStateOf(RewriteSearchResultState.Idle) }
    val fieldFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        fieldFocusRequester.requestFocusReliably()
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            resultState = RewriteSearchResultState.Idle
            return@LaunchedEffect
        }
        resultState = RewriteSearchResultState.Loading
        delay(SEARCH_DEBOUNCE_MS)
        val results = search(trimmed)
        resultState = RewriteSearchResultState.Loaded(results)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .padding(top = 34.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Search", style = MaterialTheme.typography.displaySmall)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .focusRequester(fieldFocusRequester)
                    // A TV text field otherwise swallows every D-pad key for cursor movement,
                    // so DOWN/UP can never hand focus off to the results grid below. Explicitly
                    // forward vertical D-pad presses to Compose's focus traversal instead.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                focusManager.moveFocus(FocusDirection.Down)
                                true
                            }
                            Key.DirectionUp -> {
                                focusManager.moveFocus(FocusDirection.Up)
                                true
                            }
                            else -> false
                        }
                    },
                singleLine = true,
                placeholder = { Text("Movies, shows, people...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
        }

        when (val state = resultState) {
            is RewriteSearchResultState.Idle -> SearchHint()
            is RewriteSearchResultState.Loading -> SearchLoading()
            is RewriteSearchResultState.Loaded -> if (state.posters.isEmpty()) {
                SearchEmpty(query)
            } else {
                SearchResultsGrid(
                    posters = state.posters,
                    showPosterLabels = showPosterLabels,
                    onPosterSelected = onPosterSelected,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsGrid(
    posters: List<RewriteHomePoster>,
    showPosterLabels: Boolean,
    onPosterSelected: (RewriteHomePoster) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 176.dp),
        contentPadding = PaddingValues(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = 4.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        verticalArrangement = Arrangement.spacedBy(SlugYardTvMetrics.RowGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(posters, key = RewriteHomePoster::id) { poster ->
            RewritePosterCard(
                poster = poster,
                showLabel = showPosterLabels,
                // Search previously hardcoded this to false, so focus never scrolled the grid
                // into view -- pressing D-pad down from the search field looked like nothing
                // happened at all. Matches the Home rows' behavior now.
                smoothFocusMovement = true,
                onClick = { onPosterSelected(poster) },
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
