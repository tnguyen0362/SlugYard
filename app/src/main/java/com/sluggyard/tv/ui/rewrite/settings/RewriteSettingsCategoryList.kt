package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics

@Composable
fun RewriteSettingsCategoryList(
    categories: List<RewriteSettingsCategory>,
    onOpen: (RewriteSettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = 34.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Tune the TV experience without changing automatic playback policy.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            }
        }
        items(categories, key = RewriteSettingsCategory::name) { category ->
            RewriteSettingsCategoryRow(category = category, onClick = { onOpen(category) })
        }
    }
}

@Composable
private fun RewriteSettingsCategoryRow(
    category: RewriteSettingsCategory,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = if (focused) 1.02f else 1f; scaleY = if (focused) 1.02f else 1f }
            .clip(RoundedCornerShape(SlugYardTvMetrics.CardCornerRadius))
            .background(if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(category.title, style = MaterialTheme.typography.titleLarge)
        Text(category.description, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
    }
}
