package com.sluggyard.tv.ui.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import kotlinx.coroutines.launch

private enum class AddonsListFilter(val label: String) {
    Installed("Installed"),
    Community("Community addons"),
}

private enum class AddonsTypeFilter(val label: String) {
    All("All"),
    Movie("Movie"),
    Series("Series"),
    Channel("Channel"),
    Other("Other"),
}

private data class AddonCardModel(
    val manifestUrl: String,
    val name: String,
    val version: String?,
    val categoryLine: String,
    val description: String,
    val logoUrl: String?,
    val installed: Boolean,
    val installedAddon: ManagedAddon? = null,
)

@Composable
internal fun AddonsSettings(
    addons: List<ManagedAddon>,
    onInstallAddon: suspend (manifestUrl: String) -> Unit,
    onUninstallAddon: suspend (manifestUrl: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var listFilter by remember { mutableStateOf(AddonsListFilter.Installed) }
    var typeFilter by remember { mutableStateOf(AddonsTypeFilter.All) }
    var searchQuery by remember { mutableStateOf("") }
    var busyUrl by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val cards = remember(addons, listFilter, typeFilter, searchQuery) {
        buildAddonCards(addons, listFilter, typeFilter, searchQuery)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AddonsToolbar(
            listFilter = listFilter,
            typeFilter = typeFilter,
            searchQuery = searchQuery,
            onListFilterCycle = {
                listFilter = when (listFilter) {
                    AddonsListFilter.Installed -> AddonsListFilter.Community
                    AddonsListFilter.Community -> AddonsListFilter.Installed
                }
            },
            onTypeFilterCycle = {
                val values = AddonsTypeFilter.entries
                typeFilter = values[(values.indexOf(typeFilter) + 1) % values.size]
            },
            onSearchQueryChange = { searchQuery = it },
            onAddAddon = { showAddDialog = true },
        )

        statusMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = SlugYardPalette.OnCanvasMuted,
            )
        }

        if (cards.isEmpty()) {
            Text(
                when (listFilter) {
                    AddonsListFilter.Installed -> "No installed addons match this filter."
                    AddonsListFilter.Community -> "No community addons match this filter."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = SlugYardPalette.OnCanvasMuted,
            )
        } else {
            cards.forEach { card ->
                AddonCard(
                    model = card,
                    busy = busyUrl == card.manifestUrl,
                    onPrimaryAction = {
                        if (busyUrl != null) return@AddonCard
                        busyUrl = card.manifestUrl
                        statusMessage = null
                        scope.launch {
                            runCatching {
                                if (card.installed) {
                                    onUninstallAddon(card.manifestUrl)
                                } else {
                                    onInstallAddon(card.manifestUrl)
                                }
                            }.onSuccess {
                                statusMessage = if (card.installed) {
                                    "Uninstalled ${card.name}."
                                } else {
                                    "Installed ${card.name}."
                                }
                            }.onFailure {
                                statusMessage = it.message ?: "Addon action failed."
                            }
                            busyUrl = null
                        }
                    },
                    onShare = {
                        copyAddonShareUrl(context, card.manifestUrl)
                        statusMessage = "Share link copied for ${card.name}."
                    },
                )
            }
        }
    }

    if (showAddDialog) {
        AddAddonDialog(
            busy = busyUrl == "__add__",
            onDismiss = { if (busyUrl != "__add__") showAddDialog = false },
            onInstall = { url ->
                busyUrl = "__add__"
                statusMessage = null
                scope.launch {
                    runCatching { onInstallAddon(url) }
                        .onSuccess {
                            statusMessage = "Addon installed."
                            showAddDialog = false
                        }
                        .onFailure {
                            statusMessage = it.message ?: "Could not install addon."
                        }
                    busyUrl = null
                }
            },
        )
    }
}

@Composable
private fun AddonsToolbar(
    listFilter: AddonsListFilter,
    typeFilter: AddonsTypeFilter,
    searchQuery: String,
    onListFilterCycle: () -> Unit,
    onTypeFilterCycle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAddAddon: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(label = listFilter.label, onClick = onListFilterCycle)
        FilterChip(label = typeFilter.label, onClick = onTypeFilterCycle)
        Spacer(Modifier.weight(1f))
        TvButton(
            label = "+ Add addon",
            onClick = onAddAddon,
            style = ButtonStyle.Primary,
        )
        SearchField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.width(260.dp),
        )
    }
}

@Composable
private fun FilterChip(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius)
    Box(
        modifier = Modifier
            .background(
                if (focused) Color.White.copy(alpha = 0.12f) else SlugYardPalette.SurfaceElevated,
                shape,
            )
            .border(
                width = SlugYardTvMetrics.FocusRingWidth,
                color = if (focused) SlugYardPalette.FocusRing else SlugYardPalette.Divider,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = SlugYardPalette.OnCanvas,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius)
    Row(
        modifier = modifier
            .height(48.dp)
            .background(SlugYardPalette.SurfaceElevated, shape)
            .border(
                width = SlugYardTvMetrics.FocusRingWidth,
                color = if (focused) SlugYardPalette.FocusRing else SlugYardPalette.Divider,
                shape = shape,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", color = SlugYardPalette.OnCanvasMuted)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            cursorBrush = SolidColor(SlugYardPalette.Accent),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SlugYardPalette.OnCanvas),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            decorationBox = { inner ->
                if (value.isBlank()) {
                    Text(
                        "Search addons",
                        style = MaterialTheme.typography.bodyLarge,
                        color = SlugYardPalette.OnCanvasMuted,
                    )
                }
                inner()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AddonCard(
    model: AddonCardModel,
    busy: Boolean,
    onPrimaryAction: () -> Unit,
    onShare: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1A2B), shape)
            .border(1.dp, SlugYardPalette.Divider.copy(alpha = 0.55f), shape)
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AddonIcon(logoUrl = model.logoUrl, name = model.name)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = SlugYardPalette.OnCanvas,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                model.version?.takeIf { it.isNotBlank() }?.let { version ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (version.startsWith("v", ignoreCase = true)) version else "v.$version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB7B0C8),
                    )
                }
            }
            Text(
                model.categoryLine,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB7A4E0),
            )
            Text(
                model.description,
                style = MaterialTheme.typography.bodyLarge,
                color = SlugYardPalette.OnCanvasMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvButton(
                label = when {
                    busy && model.installed -> "Removing…"
                    busy -> "Installing…"
                    model.installed -> "Uninstall"
                    else -> "Install"
                },
                onClick = onPrimaryAction,
                enabled = !busy,
                style = ButtonStyle.Secondary,
            )
            if (model.installed) {
                Text(
                    "↗ Share addon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlugYardPalette.OnCanvas,
                    modifier = Modifier
                        .clickable(onClick = onShare)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AddonIcon(logoUrl: String?, name: String) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(SlugYardPalette.SurfaceElevated, shape)
            .border(1.dp, SlugYardPalette.Divider, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp),
            )
        } else {
            Text(
                "＋",
                style = MaterialTheme.typography.headlineMedium,
                color = SlugYardPalette.OnCanvas,
            )
        }
    }
}

@Composable
private fun AddAddonDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    BackHandler(enabled = !busy, onBack = onDismiss)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add addon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste a curated addon manifest URL to install it.")
                androidx.compose.material3.OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("https://example.com/manifest.json") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (busy) {
                    CircularProgressIndicator()
                    Text("Installing…")
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onInstall(url.trim()) },
                enabled = !busy && url.isNotBlank(),
            ) { Text(if (busy) "Working…" else "Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

private fun buildAddonCards(
    addons: List<ManagedAddon>,
    listFilter: AddonsListFilter,
    typeFilter: AddonsTypeFilter,
    searchQuery: String,
): List<AddonCardModel> {
    val installedCards = addons.map { it.toCardModel(installed = true) }
    val communityCatalog = buildCommunityCatalog(addons)
    val source = when (listFilter) {
        AddonsListFilter.Installed -> installedCards
        AddonsListFilter.Community -> communityCatalog
    }
    val query = searchQuery.trim()
    return source.filter { card ->
        matchesTypeFilter(card, typeFilter) &&
            (query.isBlank() ||
                card.name.contains(query, ignoreCase = true) ||
                card.description.contains(query, ignoreCase = true) ||
                card.categoryLine.contains(query, ignoreCase = true))
    }
}

private fun buildCommunityCatalog(installed: List<ManagedAddon>): List<AddonCardModel> {
    val playFlixInstalled = installed.firstOrNull {
        SlugYardCommunitySourcePolicy.isPlayFlixManifest(it.manifestUrl)
    }
    return listOf(communityPlayFlixCard(playFlixInstalled))
}

private fun communityPlayFlixCard(installed: ManagedAddon?): AddonCardModel {
    if (installed != null) return installed.toCardModel(installed = true)
    return AddonCardModel(
        manifestUrl = SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL,
        name = SlugYardCommunitySourcePolicy.PLAYFLIX_DISPLAY_NAME,
        version = null,
        categoryLine = "Movie & Series",
        description = "PlayFlix community streams and catalogs. Install when you want this source enabled.",
        logoUrl = null,
        installed = false,
    )
}

private fun ManagedAddon.toCardModel(installed: Boolean): AddonCardModel =
    AddonCardModel(
        manifestUrl = manifestUrl,
        name = SlugYardCommunitySourcePolicy.addonDisplayName(this),
        version = manifest.version,
        categoryLine = formatAddonTypes(manifest),
        description = manifest.description?.takeIf { it.isNotBlank() }
            ?: if (SlugYardCommunitySourcePolicy.isPlayFlixManifest(manifestUrl)) {
                "PlayFlix community streams and catalogs."
            } else {
                "Stremio-compatible addon"
            },
        logoUrl = manifest.logoUrl,
        installed = installed,
        installedAddon = this.takeIf { installed },
    )

private fun formatAddonTypes(manifest: AddonManifestContract): String {
    val labels = manifest.types.mapNotNull { raw ->
        when (raw.trim().lowercase()) {
            "movie" -> "Movie"
            "series" -> "Series"
            "channel", "tv", "tvchannels" -> "Channel"
            "anime" -> "Anime"
            "other" -> "Other"
            else -> raw.trim().replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }
        }
    }.distinct()
    return when {
        labels.isEmpty() -> "Other"
        labels.size == 1 -> labels.single()
        labels.size == 2 -> "${labels[0]} & ${labels[1]}"
        else -> labels.dropLast(1).joinToString(", ") + " & ${labels.last()}"
    }
}

private fun matchesTypeFilter(card: AddonCardModel, filter: AddonsTypeFilter): Boolean {
    if (filter == AddonsTypeFilter.All) return true
    val line = card.categoryLine.lowercase()
    return when (filter) {
        AddonsTypeFilter.All -> true
        AddonsTypeFilter.Movie -> "movie" in line
        AddonsTypeFilter.Series -> "series" in line
        AddonsTypeFilter.Channel -> "channel" in line
        AddonsTypeFilter.Other ->
            "movie" !in line && "series" !in line && "channel" !in line
    }
}

private fun copyAddonShareUrl(context: Context, manifestUrl: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Addon URL", manifestUrl))
}
