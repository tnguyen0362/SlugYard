package com.sluggyard.tv.ui.app.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.app.requestFocusReliably
import kotlinx.coroutines.delay

@Composable
fun ContinueWatchingOptionsDialog(
    title: String,
    onViewDetails: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }
    var actionsArmed by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(onBack = onDismiss)
        // Long-press Select KeyUp would otherwise activate the newly focused primary button
        // and jump straight into View details before the user can choose.
        LaunchedEffect(Unit) {
            delay(900)
            actionsArmed = true
            cancelFocusRequester.requestFocusReliably(retries = 6)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .background(SlugYardPalette.Surface, RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius))
                .border(1.dp, SlugYardPalette.Divider, RoundedCornerShape(SlugYardTvMetrics.SheetCornerRadius))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = SlugYardPalette.OnCanvas,
            )
            Text(
                text = "View details or remove this title from Continue Watching.",
                style = MaterialTheme.typography.bodyMedium,
                color = SlugYardPalette.OnCanvasMuted,
            )
            TvButton(
                label = "Cancel",
                onClick = onDismiss,
                style = ButtonStyle.Secondary,
                enabled = true,
                fillMaxWidth = true,
                focusRequester = cancelFocusRequester,
            )
            TvButton(
                label = "View details",
                onClick = { if (actionsArmed) onViewDetails() },
                style = ButtonStyle.Primary,
                enabled = actionsArmed,
                fillMaxWidth = true,
            )
            TvButton(
                label = "Remove from Continue Watching",
                onClick = { if (actionsArmed) onRemove() },
                style = ButtonStyle.Ghost,
                enabled = actionsArmed,
                fillMaxWidth = true,
            )
        }
    }
}
