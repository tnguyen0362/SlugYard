package com.sluggyard.tv.ui.app.settings

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
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = SlugYardPalette.OnCanvas,
        )
        Column { content() }
    }
}

/** Flat row shell shared by settings controls. Soft-rect focus ring + elevated surface. */
@Composable
private fun SettingsRowShell(
    onClick: (() -> Unit)?,
    focused: Boolean,
    interaction: MutableInteractionSource,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(SlugYardTvMetrics.SettingsRowRadius)
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
                .background(if (focused) Color.White.copy(alpha = 0.08f) else Color.Transparent, shape)
                .then(
                    if (focused) {
                        Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
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
                .padding(horizontal = 14.dp, vertical = 16.dp),
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SlugYardPalette.Divider.copy(alpha = 0.85f)),
        )
    }
}

@Composable
internal fun ToggleRow(
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
    SettingsRowShell(
        onClick = { applyToggle(!checked) },
        focused = focused,
        interaction = interaction,
        contentDescription = "$title. $description. ${if (checked) "On" else "Off"}.",
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = SlugYardPalette.Canvas,
                checkedTrackColor = SlugYardPalette.Accent,
                uncheckedThumbColor = SlugYardPalette.OnCanvasMuted,
                uncheckedTrackColor = SlugYardPalette.SurfaceElevated,
                uncheckedBorderColor = SlugYardPalette.Divider,
            ),
        )
    }
}

@Composable
internal fun ValueRow(title: String, value: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    SettingsRowShell(
        onClick = null,
        focused = focused,
        interaction = interaction,
        contentDescription = "$title: $value",
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
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
internal fun ActionRow(
    title: String,
    description: String,
    actionLabel: String = "Open",
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    SettingsRowShell(
        onClick = onClick,
        focused = focused,
        interaction = interaction,
        contentDescription = "$title. $description. $actionLabel",
        modifier = modifier,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = SlugYardPalette.OnCanvasMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            actionLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) SlugYardPalette.Accent else SlugYardPalette.OnCanvasMuted,
        )
    }
}

@Composable
internal fun CycleRow(
    title: String,
    value: String,
    values: List<String>,
    valueLabel: (String) -> String = { it },
    onValueChanged: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    ActionRow(title, valueLabel(value)) { showPicker = true }

    if (showPicker) {
        SettingsValuePicker(
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
