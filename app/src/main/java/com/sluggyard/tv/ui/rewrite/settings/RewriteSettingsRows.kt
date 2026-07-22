package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.design.SlugYardPalette

@Composable
internal fun RewriteSettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
internal fun RewriteToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer(scaleX = if (focused) 1.015f else 1f, scaleY = if (focused) 1.015f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
            .clickable(interactionSource = interaction, indication = null) { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun RewriteValueRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SlugYardPalette.Surface)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
    }
}

@Composable
internal fun RewriteActionRow(title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SlugYardPalette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
        }
        Text("Open", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun RewriteCycleRow(title: String, value: String, values: List<String>, onValueChanged: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }

    RewriteActionRow(title, value) { showPicker = true }

    if (showPicker) {
        RewriteSettingsValuePicker(
            title = title,
            values = values,
            selected = value,
            onValueSelected = {
                onValueChanged(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

internal fun resizeModeLabel(mode: Int): String =
    listOf("Fit", "Fill", "Stretch", "Center", "Automatic").getOrElse(mode.coerceIn(0, 4)) { "Fit" }

internal fun addonLabel(url: String): String =
    url.substringAfter("://").substringBefore('/').ifBlank { "Addon source" }

internal val DebridService.label: String
    get() = when (this) {
        DebridService.REAL_DEBRID -> "Real-Debrid"
        DebridService.TORBOX -> "Torbox"
        DebridService.PREMIUMIZE -> "Premiumize"
    }
