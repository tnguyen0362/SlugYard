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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonResource
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

private data class AddonCardModel(
    val manifestUrl: String,
    val name: String,
    val version: String?,
    val categoryLine: String,
    val description: String,
    val logoUrl: String?,
    val showLogo: Boolean,
    val installed: Boolean,
)

@Composable
internal fun AddonsSettings(
    addons: List<ManagedAddon>,
    onInstallAddon: suspend (manifestUrl: String) -> Unit,
    onUninstallAddon: suspend (manifestUrl: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var listFilter by remember { mutableStateOf(AddonsListFilter.Installed) }
    var busyUrl by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val cards = remember(addons, listFilter) {
        buildAddonCards(addons, listFilter)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AddonsFilterRow(
            listFilter = listFilter,
            onSelect = { listFilter = it },
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
                    AddonsListFilter.Installed -> "No installed catalog or subtitle addons."
                    AddonsListFilter.Community -> "No community addons available."
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
                )
            }
        }
    }
}

@Composable
private fun AddonsFilterRow(
    listFilter: AddonsListFilter,
    onSelect: (AddonsListFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddonsListFilter.entries.forEach { filter ->
            FilterChip(
                label = filter.label,
                selected = listFilter == filter,
                onClick = { onSelect(filter) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius)
    Box(
        modifier = Modifier
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.12f)
                    selected -> Color.White.copy(alpha = 0.08f)
                    else -> SlugYardPalette.SurfaceElevated
                },
                shape,
            )
            .border(
                width = SlugYardTvMetrics.FocusRingWidth,
                color = when {
                    focused -> SlugYardPalette.FocusRing
                    selected -> SlugYardPalette.Accent.copy(alpha = 0.65f)
                    else -> SlugYardPalette.Divider
                },
                shape,
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
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun AddonCard(
    model: AddonCardModel,
    busy: Boolean,
    onPrimaryAction: () -> Unit,
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
        if (model.showLogo) {
            AddonIcon(logoUrl = model.logoUrl, name = model.name)
        }
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
                name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = SlugYardPalette.OnCanvas,
            )
        }
    }
}

private fun buildAddonCards(
    addons: List<ManagedAddon>,
    listFilter: AddonsListFilter,
): List<AddonCardModel> {
    return when (listFilter) {
        AddonsListFilter.Installed -> addons
            .filter { SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(it) }
            .map { it.toCardModel(installed = true) }
        AddonsListFilter.Community -> buildCommunityCatalog(addons)
    }
}

private fun buildCommunityCatalog(installed: List<ManagedAddon>): List<AddonCardModel> {
    // Optional allowlisted community packs — empty today (scrapers are default provision, not Community).
    return SlugYardCommunitySourcePolicy.optionalCommunityManifestUrls.map { url ->
        val existing = installed.firstOrNull {
            it.manifestUrl.equals(url, ignoreCase = true)
        }
        if (existing != null) {
            existing.toCardModel(installed = true)
        } else {
            AddonCardModel(
                manifestUrl = url,
                name = "Community addon",
                version = null,
                categoryLine = "Movie & Series",
                description = "Stremio-compatible addon",
                logoUrl = null,
                showLogo = true,
                installed = false,
            )
        }
    }
}

private fun ManagedAddon.toCardModel(installed: Boolean): AddonCardModel =
    AddonCardModel(
        manifestUrl = manifestUrl,
        name = SlugYardCommunitySourcePolicy.addonDisplayName(this),
        version = SlugYardCommunitySourcePolicy.addonDisplayVersion(this),
        categoryLine = formatAddonTypes(manifest),
        description = SlugYardCommunitySourcePolicy.addonDisplayDescription(this),
        logoUrl = manifest.logoUrl,
        showLogo = true,
        installed = installed,
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
        labels.isEmpty() -> resourceFallbackLabel(manifest)
        labels.size == 1 -> labels.single()
        labels.size == 2 -> "${labels[0]} & ${labels[1]}"
        else -> labels.dropLast(1).joinToString(", ") + " & ${labels.last()}"
    }
}

private fun resourceFallbackLabel(manifest: AddonManifestContract): String = when {
    AddonResource.SUBTITLES in manifest.resources -> "Subtitles"
    AddonResource.META in manifest.resources -> "Metadata"
    AddonResource.CATALOG in manifest.resources -> "Catalog"
    else -> "Other"
}
