package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette

/**
 * Full-screen scrollable picker used in place of the old click-to-cycle interaction. Modeled on
 * [com.sluggyard.tv.ui.rewrite.player.RewritePlayerTrackOverlay]'s track sheet so both the player
 * and settings share one "pick a value from a list" pattern.
 */
@Composable
fun RewriteSettingsValuePicker(
    title: String,
    values: List<String>,
    selected: String,
    onValueSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedIndex = values.indexOf(selected).takeIf { it >= 0 } ?: 0
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        listState.scrollToItem(selectedIndex)
        focusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(560.dp).heightIn(max = 720.dp).clip(RoundedCornerShape(12.dp))
                .background(SlugYardPalette.Canvas).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onDismiss) { Text("Close") }
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 560.dp),
            ) {
                itemsIndexed(values) { index, value ->
                    ValuePickerRow(
                        value = value,
                        selected = value == selected,
                        modifier = if (index == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier,
                        onClick = { onValueSelected(value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ValuePickerRow(value: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (selected) {
            Text("Selected", style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.Accent)
        }
    }
}
