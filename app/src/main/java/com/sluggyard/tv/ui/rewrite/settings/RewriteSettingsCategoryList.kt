package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics

@Composable
fun RewriteSettingsCategoryList(
    categories: List<RewriteSettingsCategory>,
    onOpen: (RewriteSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    restoreFocusCategory: RewriteSettingsCategory? = null,
) {
    val restoreFocusRequester = remember { FocusRequester() }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = 34.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", style = MaterialTheme.typography.displayLarge)
                Text(
                    "Tune the TV experience without changing automatic playback policy.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            }
        }
        items(categories, key = RewriteSettingsCategory::name) { category ->
            RewriteSettingsCategoryRow(
                category = category,
                onClick = { onOpen(category) },
                modifier = if (category == restoreFocusCategory) Modifier.focusRequester(restoreFocusRequester) else Modifier,
            )
        }
    }
    if (restoreFocusCategory != null) {
        LaunchedEffect(restoreFocusCategory) {
            restoreFocusRequester.requestFocus()
        }
    }
}

@Composable
private fun RewriteSettingsCategoryRow(
    category: RewriteSettingsCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (focused) SlugYardPalette.Surface else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 18.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(if (focused) SlugYardPalette.Accent else Color.Transparent),
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    category.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (focused) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvas,
                )
                Text(category.description, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SlugYardPalette.OnCanvasMuted.copy(alpha = 0.16f)),
        )
    }
}
