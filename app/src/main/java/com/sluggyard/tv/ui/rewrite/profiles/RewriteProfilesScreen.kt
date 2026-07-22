package com.sluggyard.tv.ui.rewrite.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.data.RewriteProfile
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileState

/** Name-led TV profile chooser with a deliberate absence of avatar and colour decoration. */
@Composable
fun RewriteProfilesScreen(
    state: RewriteProfileState,
    onSelect: (RewriteProfile) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (RewriteProfile, String) -> Unit,
    onRemove: (RewriteProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<RewriteProfile?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas)
            .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Who's watching?", style = MaterialTheme.typography.displaySmall)
        Text(
            "Profiles keep your saved titles and progress separate.",
            style = MaterialTheme.typography.bodyLarge,
            color = SlugYardPalette.OnCanvasMuted,
        )
        state.profiles.forEach { profile ->
            ProfileRow(
                profile = profile,
                selected = profile.id == state.activeProfileId,
                onSelect = { onSelect(profile) },
                onEdit = { editing = profile },
            )
        }
        OutlinedButton(onClick = { creating = true }, modifier = Modifier.widthIn(min = 210.dp)) {
            Text("Add profile")
        }
    }

    val target = editing
    if (target != null) {
        ProfileNameDialog(
            title = "Edit profile",
            initialName = target.name,
            confirmLabel = "Save",
            showRemove = state.profiles.size > 1,
            onConfirm = { name -> onRename(target, name); editing = null },
            onRemove = { onRemove(target); editing = null },
            onDismiss = { editing = null },
        )
    } else if (creating) {
        ProfileNameDialog(
            title = "New profile",
            initialName = "",
            confirmLabel = "Create",
            showRemove = false,
            onConfirm = { name -> onCreate(name); creating = false },
            onRemove = {},
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun ProfileRow(profile: RewriteProfile, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = if (focused) 1.025f else 1f
    Row(
        modifier = Modifier.fillMaxWidth().graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(profile.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
        if (selected) Text("Active", color = SlugYardPalette.OnCanvasMuted, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = onEdit) { Text("Edit") }
    }
}

@Composable
private fun ProfileNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    showRemove: Boolean,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { Button(onClick = { onConfirm(name) }) { Text(confirmLabel) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showRemove) OutlinedButton(onClick = onRemove) { Text("Remove") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
