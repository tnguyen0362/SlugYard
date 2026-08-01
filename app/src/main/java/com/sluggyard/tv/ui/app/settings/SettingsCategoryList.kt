package com.sluggyard.tv.ui.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.requestFocusReliably
import kotlinx.coroutines.launch

@Composable
fun SettingsCategoryList(
    categories: List<SettingsCategory>,
    onOpen: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    restoreFocusCategory: SettingsCategory? = null,
    initialFocusRequester: FocusRequester? = null,
) {
    val restoreFocusRequester = remember { FocusRequester() }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = 34.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = SlugYardPalette.OnCanvas,
                )
                Text(
                    "Tune the TV experience without changing automatic playback policy.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            }
        }
        items(categories, key = SettingsCategory::name) { category ->
            SettingsCategoryRow(
                category = category,
                onClick = { onOpen(category) },
                modifier = Modifier,
                focusRequester = when {
                    category == restoreFocusCategory -> restoreFocusRequester
                    category == categories.firstOrNull() -> initialFocusRequester
                    else -> null
                },
            )
        }
    }
    if (restoreFocusCategory != null) {
        LaunchedEffect(restoreFocusCategory) {
            restoreFocusRequester.requestFocusReliably()
        }
    }
    if (initialFocusRequester != null && restoreFocusCategory == null) {
        LaunchedEffect(initialFocusRequester, categories.firstOrNull()) {
            initialFocusRequester.requestFocusReliably(retries = 8)
        }
    }
}

@Composable
fun SettingsSplitPane(
    categories: List<SettingsCategory>,
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    categoryListState: LazyListState,
    initialFocusRequester: FocusRequester?,
    headerFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit,
) {
    // Header Down must land on an always-composed anchor. Attaching the shell FR only to a
    // LazyColumn category row fails intermittently when that row is disposed off-screen.
    val entryFocusRequester = initialFocusRequester ?: remember { FocusRequester() }
    val selectedCategoryFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun focusSelectedCategory() {
        scope.launch {
            val index = categories.indexOf(selected).coerceAtLeast(0)
            categoryListState.scrollToItem(index)
            selectedCategoryFocusRequester.requestFocusReliably(retries = 10)
        }
    }
    var didRequestInitialFocus by remember { mutableStateOf(false) }
    var detailHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(entryFocusRequester) {
        if (!didRequestInitialFocus) {
            didRequestInitialFocus = true
            entryFocusRequester.requestFocusReliably(retries = 8)
        }
    }
    // Back from the detail pane returns focus to the category rail; Back on the rail
    // is left to the shell so Settings can exit normally.
    BackHandler(enabled = detailHasFocus, onBack = { focusSelectedCategory() })
    Row(
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .background(SlugYardPalette.Surface)
                .padding(horizontal = 40.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(entryFocusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) focusSelectedCategory()
                    }
                    .focusable(),
            )
            Text(
                "Settings",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = SlugYardPalette.OnCanvas,
            )
            LazyColumn(
                state = categoryListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(categories, key = SettingsCategory::name) { category ->
                    SettingsCategoryRow(
                        category = category,
                        onClick = { onSelect(category) },
                        focusRequester = if (category == selected) selectedCategoryFocusRequester else null,
                        // Only the first category escapes Up into the root header so list
                        // traversal still walks category → category.
                        upFocusRequester = headerFocusRequester.takeIf { category == categories.firstOrNull() },
                        selected = category == selected,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(SlugYardPalette.Divider),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.58f)
                .background(SlugYardPalette.Canvas)
                .onFocusChanged { detailHasFocus = it.hasFocus },
        ) {
            detail()
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(SlugYardTvMetrics.SettingsRowRadius)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusProperties { upFocusRequester?.let { up = it } }
                .fillMaxWidth()
                .background(
                    when {
                        focused -> Color.White.copy(alpha = 0.12f)
                        selected -> SlugYardPalette.SurfaceElevated
                        else -> Color.Transparent
                    },
                    shape,
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            // Single focus cue: left accent bar — no competing pill/outline on top of the fill.
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .width(3.dp)
                    .height(34.dp)
                    .background(
                        when {
                            focused -> SlugYardPalette.Accent
                            selected -> SlugYardPalette.OnCanvasMuted.copy(alpha = 0.45f)
                            else -> Color.Transparent
                        },
                        RoundedCornerShape(2.dp),
                    ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    category.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = SlugYardPalette.OnCanvas,
                )
                Text(
                    category.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SlugYardPalette.Divider.copy(alpha = 0.65f)),
        )
    }
}
