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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics

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
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    LaunchedEffect(focused, contentDescription) {
        ExperimentalDiagnostics.event(
            "settings",
            "focus_changed",
            mapOf("control" to contentDescription, "focused" to focused),
        )
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (focused) SlugYardPalette.SurfaceElevated else Color.Transparent)
                .then(
                    if (focused) {
                        // Rect focus — never a stadium/pill outline inside settings rows.
                        Modifier.border(2.dp, SlugYardPalette.FocusRing, RoundedCornerShape(6.dp))
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = {
                                ExperimentalDiagnostics.event(
                                    "settings",
                                    "button_clicked",
                                    mapOf("control" to contentDescription),
                                )
                                onClick()
                            },
                        )
                    } else {
                        // Read-only rows still need to receive D-pad focus so users can navigate to them.
                        Modifier.focusable(interactionSource = interaction)
                    }
                )
                .then(
                    contentDescription?.let { description ->
                        Modifier.semantics {
                            this.contentDescription = description
                            role = Role.Button
                        }
                    } ?: Modifier,
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
    fun applyToggle(value: Boolean) {
        ExperimentalDiagnostics.event("settings", "toggle_changed", mapOf("setting" to title, "value" to value))
        onCheckedChange(value)
    }
    RewriteSettingsRowShell(
        onClick = { applyToggle(!checked) },
        focused = focused,
        interaction = interaction,
        contentDescription = "$title. $description. ${if (checked) "On" else "Off"}.",
    ) {
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
            onCheckedChange = ::applyToggle,
            modifier = Modifier.focusProperties { canFocus = false },
        )
    }
}

@Composable
internal fun RewriteValueRow(title: String, value: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    RewriteSettingsRowShell(
        onClick = null,
        focused = focused,
        interaction = interaction,
        contentDescription = "$title: $value",
    ) {
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    RewriteSettingsRowShell(
        onClick = onClick,
        focused = focused,
        interaction = interaction,
        contentDescription = "$title. $description. $actionLabel",
        modifier = modifier,
    ) {
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
                ExperimentalDiagnostics.event("settings", "value_selected", mapOf("setting" to title, "value" to it))
                onValueChanged(it)
                showPicker = false
            },
            onDismiss = {
                ExperimentalDiagnostics.event("settings", "value_picker_dismissed", mapOf("setting" to title))
                showPicker = false
            },
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
        DebridService.TORBOX -> "TorBox"
        DebridService.PREMIUMIZE -> "Premiumize"
    }
