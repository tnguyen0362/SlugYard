package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.domain.model.MDBListSettings
import com.sluggyard.tv.core.addonprotocol.ManagedAddon

@Composable
internal fun ContentDiscoverySettings(
    tmdb: TmdbSettings,
    mdblist: MDBListSettings,
    onTmdbEnabled: (Boolean) -> Unit,
    onTmdbEnrichContinueWatching: (Boolean) -> Unit,
    onMdblistEnabled: (Boolean) -> Unit,
    rewriteAddons: List<ManagedAddon>,
    onAddonEnabledChanged: (String, Boolean) -> Unit,
    debridConnected: Boolean,
    onConfigureAutoSources: () -> Unit,
) {
    val autoSourceAddons = rewriteAddons.filter { addon ->
        val name = "${addon.manifest.name} ${addon.manifest.id} ${addon.manifestUrl}".lowercase()
        "torrentio" in name || "mediafusion" in name
    }
    val visibleAddons = rewriteAddons - autoSourceAddons.toSet()
    val configuredAutoSources = autoSourceAddons.count { !it.configuredManifestUrl.isNullOrBlank() }
    RewriteSettingsGroup("Content enrichment") {
        RewriteToggleRow("TMDB enrichment", "Improve artwork, metadata, dates, and details.", tmdb.enabled, onTmdbEnabled)
        RewriteToggleRow("Continue Watching artwork", "Use TMDB enrichment for saved playback tiles.", tmdb.enrichContinueWatching, onTmdbEnrichContinueWatching)
        RewriteToggleRow("MDBList ratings", "Show ratings from the configured MDBList account.", mdblist.enabled, onMdblistEnabled)
        RewriteValueRow("Catalog ordering", "Follow installed addon order")
    }
    RewriteSettingsGroup("Auto Sources") {
        Text(
            "Third-party addons are provided by their authors. You are responsible for using them in accordance with the laws and terms that apply where you live.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = com.sluggyard.tv.ui.design.SlugYardPalette.OnCanvasMuted,
        )
        RewriteActionRow(
            title = "SlugYard Auto Sources",
            description = when {
                !debridConnected -> "Connect a debrid provider first. Setup will configure Torrentio and MediaFusion automatically."
                configuredAutoSources == autoSourceAddons.size && autoSourceAddons.isNotEmpty() -> "Ready. Torrentio and MediaFusion use your active debrid provider."
                configuredAutoSources > 0 -> "$configuredAutoSources of ${autoSourceAddons.size.coerceAtLeast(2)} sources ready. Run setup again to retry the unavailable source."
                else -> "Configure Torrentio and MediaFusion for plug-and-play streaming."
            },
            actionLabel = if (configuredAutoSources > 0) "Reconfigure" else "Set up",
            onClick = onConfigureAutoSources,
        )
        autoSourceAddons.forEach { addon ->
            RewriteToggleRow(
                title = addon.manifest.name,
                description = if (addon.configuredManifestUrl.isNullOrBlank()) "Needs setup" else "Configured for active debrid provider",
                checked = addon.enabled,
                onCheckedChange = { enabled -> onAddonEnabledChanged(addon.manifest.id, enabled) },
            )
        }
    }
    RewriteSettingsGroup("Installed addons") {
        visibleAddons.forEach { addon ->
            RewriteToggleRow(
                title = addon.manifest.name,
                description = addon.manifestUrl,
                checked = addon.enabled,
                onCheckedChange = { enabled -> onAddonEnabledChanged(addon.manifest.id, enabled) },
            )
        }
        if (visibleAddons.isEmpty()) RewriteValueRow("Sources", "No managed addons available")
    }
}
