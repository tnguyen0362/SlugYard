package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.design.SlugYardPalette

@Composable
internal fun RewriteSettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Column { content() }
    }
}

/** Flat row shell shared by settings controls. Focus uses surface contrast, not a decorative bar. */
@Composable
private fun RewriteSettingsRowShell(
    onClick: (() -> Unit)?,
    focused: Boolean,
    interaction: MutableInteractionSource,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (focused) SlugYardPalette.Surface else Color.Transparent)
                .then(if (focused) Modifier.border(2.dp, SlugYardPalette.FocusRing, RoundedCornerShape(8.dp)) else Modifier)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    } else Modifier
                )
                .padding(horizontal = 4.dp, vertical = 18.dp),
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SlugYardPalette.OnCanvasMuted.copy(alpha = 0.16f)),
        )
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
    RewriteSettingsRowShell(onClick = { onCheckedChange(!checked) }, focused = focused, interaction = interaction) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = SlugYardPalette.OnCanvasMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.focusProperties { canFocus = false },
        )
    }
}

@Composable
internal fun RewriteValueRow(title: String, value: String) {
    val interaction = remember { MutableInteractionSource() }
    RewriteSettingsRowShell(onClick = null, focused = false, interaction = interaction) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = SlugYardPalette.OnCanvasMuted,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun RewriteActionRow(
    title: String,
    description: String,
    actionLabel: String = "Open",
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    RewriteSettingsRowShell(onClick = onClick, focused = focused, interaction = interaction) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = SlugYardPalette.OnCanvasMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = SlugYardPalette.OnCanvasMuted)
    }
}

@Composable
internal fun RewriteCycleRow(
    title: String,
    value: String,
    values: List<String>,
    valueLabel: (String) -> String = { it },
    onValueChanged: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    RewriteActionRow(title, valueLabel(value)) { showPicker = true }

    if (showPicker) {
        RewriteSettingsValuePicker(
            title = title,
            values = values,
            selected = value,
            valueLabel = valueLabel,
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
