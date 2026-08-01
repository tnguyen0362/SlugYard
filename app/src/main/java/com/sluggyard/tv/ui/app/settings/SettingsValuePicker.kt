package com.sluggyard.tv.ui.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton

/**
 * Full-screen scrollable picker used in place of the old click-to-cycle interaction. Modeled on
 * the player's track-sheet pattern so both surfaces share one "pick a value from a list" idiom.
 */
@Composable
fun SettingsValuePicker(
    title: String,
    values: List<String>,
    selected: String,
    valueLabel: (String) -> String = { it },
    onValueSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        ExperimentalDiagnostics.event(
            "settings",
            "value_picker_opened",
            mapOf("setting" to title, "optionCount" to values.size, "selected" to selected),
        )
    }
    val selectedIndex = values.indexOf(selected).takeIf { it >= 0 } ?: 0
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        listState.scrollToItem(selectedIndex)
        focusRequester.requestFocus()
    }

    // This picker is invoked inline from wherever its CycleRow sits in the settings
    // screen's own scrollable content (see PlaybackSettings.kt). A plain composable here
    // only gets whatever space is left below that row in the parent Column, so rows near the
    // bottom of a long settings list (e.g. subtitle Size/Position) rendered this "full-screen"
    // picker squeezed into a sliver at the very bottom of the viewport. A real Dialog always
    // gets the whole window regardless of where it was triggered from.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)

        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .heightIn(max = 720.dp)
                    .clip(RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius))
                    .background(SlugYardPalette.Surface)
                    .border(1.dp, SlugYardPalette.Divider, RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = SlugYardPalette.OnCanvas,
                    )
                    TvButton(
                        label = "Close",
                        onClick = onDismiss,
                        style = ButtonStyle.Secondary,
                    )
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 560.dp),
                ) {
                    itemsIndexed(values) { index, value ->
                        ValuePickerRow(
                            value = valueLabel(value),
                            selected = value == selected,
                            modifier = if (index == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier,
                            onClick = { onValueSelected(value) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValuePickerRow(value: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> SlugYardPalette.SurfaceElevated
                    else -> SlugYardPalette.Canvas
                },
            )
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (focused) Color(0xFF141414) else SlugYardPalette.OnCanvas,
        )
        if (selected) {
            Text(
                "Selected",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) Color(0xFF141414) else SlugYardPalette.Accent,
            )
        }
    }
}
