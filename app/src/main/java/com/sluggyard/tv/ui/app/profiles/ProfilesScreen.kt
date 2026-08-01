package com.sluggyard.tv.ui.app.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.data.Profile
import com.sluggyard.tv.ui.app.data.ProfileState
import com.sluggyard.tv.ui.app.requestFocusReliably

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
fun ProfilesScreen(
    state: ProfileState,
    onSelect: (Profile) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Profile, String) -> Unit,
    onRemove: (Profile) -> Unit,
    onBack: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    val firstProfileFocusRequester = contentFocusRequester ?: remember { FocusRequester() }

    androidx.compose.runtime.LaunchedEffect(firstProfileFocusRequester, state.profiles.firstOrNull()?.id) {
        firstProfileFocusRequester.requestFocusReliably(retries = 8)
    }

    Column(
        modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas)
            .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Text("Who's watching?", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(28.dp))
        LazyRow(
            modifier = Modifier.widthIn(max = 1000.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items(state.profiles, key = Profile::id) { profile ->
                ProfileAvatarTile(
                    profile = profile,
                    selected = profile.id == state.activeProfileId,
                    onSelect = { onSelect(profile) },
                    onEdit = { editing = profile },
                    focusRequester = if (profile == state.profiles.firstOrNull()) firstProfileFocusRequester else null,
                )
            }
            item { AddProfileTile(onClick = { creating = true }) }
        }
        Text(
            "Hold OK on a profile to rename or remove it.",
            style = MaterialTheme.typography.bodySmall,
            color = SlugYardPalette.OnCanvasMuted,
        )
    }

    val target = editing
    if (target != null) {
        ProfileNameDialog(
            title = "Edit profile",
            initialName = target.name,
            confirmLabel = "Save",
            showRemove = state.profiles.size > 1 && target.id !in setOf("default", "1"),
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

private val AvatarTileSize = 96.dp

@Composable
private fun ProfileAvatarTile(
    profile: Profile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val avatarInteraction = remember { MutableInteractionSource() }
    val avatarFocused by avatarInteraction.collectIsFocusedAsState()
    val avatarScale = if (avatarFocused) 1.08f else 1f
    val color = remember(profile.id) { avatarColorFor(profile.id) }

    Column(
        modifier = Modifier.width(AvatarTileSize),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(AvatarTileSize)
                .graphicsLayer(scaleX = avatarScale, scaleY = avatarScale)
                .clip(RoundedCornerShape(50))
                .background(color)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, SlugYardPalette.OnCanvas, RoundedCornerShape(50))
                    } else Modifier,
                )
                .then(
                    if (avatarFocused) {
                        Modifier.border(3.dp, SlugYardPalette.FocusRing, RoundedCornerShape(50))
                    } else Modifier,
                )
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .semantics {
                    contentDescription = "Profile ${profile.name}${if (selected) ", selected" else ""}. Hold OK to edit."
                    role = Role.Button
                }
                .combinedClickable(
                    interactionSource = avatarInteraction,
                    indication = null,
                    onClick = onSelect,
                    onLongClick = onEdit,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = profile.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = Color(0xFF181818),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected profile",
                    tint = SlugYardPalette.Canvas,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(20.dp),
                )
            }
        }
        Text(
            profile.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
        )
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
                .clip(RoundedCornerShape(50))
                .background(if (focused) SlugYardPalette.SurfaceElevated else SlugYardPalette.Surface)
                .border(1.dp, SlugYardPalette.OnCanvasMuted.copy(alpha = 0.5f), RoundedCornerShape(50))
                .semantics { contentDescription = "Add profile"; role = Role.Button }
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
    var confirmRemove by remember(initialName) { mutableStateOf(false) }
    val validName = name.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; confirmRemove = false },
                    singleLine = true,
                    label = { Text("Name") },
                )
                if (!validName) Text("Enter a profile name.", color = SlugYardPalette.Danger)
                if (confirmRemove) Text("Remove this profile and its local settings?", color = SlugYardPalette.Danger)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = validName) { Text(confirmLabel) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showRemove) {
                    OutlinedButton(onClick = { if (confirmRemove) onRemove() else confirmRemove = true }) {
                        Text(if (confirmRemove) "Confirm remove" else "Remove")
                    }
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
