package com.sluggyard.tv.ui.rewrite.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.rewrite.requestFocusReliably

@Composable
fun RewriteContinueWatchingOptionsDialog(
    title: String,
    onViewDetails: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detailsFocusRequester = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(onBack = onDismiss)
        LaunchedEffect(Unit) {
            detailsFocusRequester.requestFocusReliably(retries = 6)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .background(SlugYardPalette.SurfaceElevated, RoundedCornerShape(8.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = SlugYardPalette.OnCanvas,
            )
            Text(
                text = "View details or remove this title from Continue Watching.",
                style = MaterialTheme.typography.bodyMedium,
                color = SlugYardPalette.OnCanvasMuted,
            )
            Button(
                onClick = onViewDetails,
                modifier = Modifier.focusRequester(detailsFocusRequester),
            ) {
                Text("View details")
            }
            OutlinedButton(onClick = onRemove) {
                Text("Remove from Continue Watching")
            }
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    }
}
