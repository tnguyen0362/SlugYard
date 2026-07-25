package com.sluggyard.tv.ui.rewrite.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.data.RewriteProfile
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileState

/** Deterministic colored-tile identity so returning to this screen never reshuffles which
 * profile wears which color -- picked from the same warm/cool family as [SlugYardPalette.Accent]
 * so no avatar clashes with the rest of the TV-first palette. */
private val ProfileAvatarColors = listOf(
    Color(0xFFF2C94C), // SlugYardPalette.Accent
    Color(0xFFEF6A6A), // SlugYardPalette.Danger
    Color(0xFF6FCF97),
    Color(0xFF56CCF2),
    Color(0xFFBB6BD9),
    Color(0xFFF2994A),
)

private fun avatarColorFor(profileId: String): Color =
    ProfileAvatarColors[(profileId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % ProfileAvatarColors.size]

/** Netflix-style "Who's watching?" profile chooser: a row of colored avatar tiles with the
 * profile name below each, plus an "Add profile" tile. */
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
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Who's watching?", style = MaterialTheme.typography.displaySmall)
            Text(
                "Profiles keep your saved titles and progress separate.",
                style = MaterialTheme.typography.bodyLarge,
                color = SlugYardPalette.OnCanvasMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            state.profiles.forEach { profile ->
                ProfileAvatarTile(
                    profile = profile,
                    selected = profile.id == state.activeProfileId,
                    onSelect = { onSelect(profile) },
                    onEdit = { editing = profile },
                )
            }
            AddProfileTile(onClick = { creating = true })
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

private val AvatarTileSize = 148.dp

@Composable
private fun ProfileAvatarTile(profile: RewriteProfile, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    val avatarInteraction = remember { MutableInteractionSource() }
    val avatarFocused by avatarInteraction.collectIsFocusedAsState()
    val avatarScale = if (avatarFocused) 1.08f else 1f
    val color = remember(profile.id) { avatarColorFor(profile.id) }

    Column(
        modifier = Modifier.width(AvatarTileSize),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .size(AvatarTileSize)
                .graphicsLayer(scaleX = avatarScale, scaleY = avatarScale)
                .clip(RoundedCornerShape(16.dp))
                .background(color)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, SlugYardPalette.OnCanvas, RoundedCornerShape(16.dp))
                    } else Modifier,
                )
                .clickable(interactionSource = avatarInteraction, indication = null, onClick = onSelect),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = profile.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = Color(0xFF181818),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
            )
            if (selected) {
                Text("• Active", style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.Accent)
            }
        }
        val editInteraction = remember { MutableInteractionSource() }
        val editFocused by editInteraction.collectIsFocusedAsState()
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (editFocused) SlugYardPalette.SurfaceElevated else Color.Transparent)
                .clickable(interactionSource = editInteraction, indication = null, onClick = onEdit)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = SlugYardPalette.OnCanvasMuted)
            Text("Edit", style = MaterialTheme.typography.labelMedium, color = SlugYardPalette.OnCanvasMuted)
        }
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale = if (focused) 1.08f else 1f
    Column(
        modifier = Modifier.width(AvatarTileSize),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .size(AvatarTileSize)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(16.dp))
                .background(if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = SlugYardPalette.OnCanvasMuted, modifier = Modifier.size(40.dp))
        }
        Text("Add profile", style = MaterialTheme.typography.titleMedium, color = SlugYardPalette.OnCanvasMuted)
        Spacer(Modifier.height(28.dp))
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
