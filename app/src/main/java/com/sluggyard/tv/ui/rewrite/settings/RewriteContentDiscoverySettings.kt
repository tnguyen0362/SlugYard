package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.domain.model.MDBListSettings
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.aggregation.HomeCatalogKey

@Composable
internal fun ContentDiscoverySettings(
    tmdb: TmdbSettings,
    mdblist: MDBListSettings,
    onTmdbEnabled: (Boolean) -> Unit,
    onTmdbEnrichContinueWatching: (Boolean) -> Unit,
    onMdblistEnabled: (Boolean) -> Unit,
    rewriteAddons: List<ManagedAddon>,
    catalogOrderKeys: List<String>,
    onCatalogOrderChanged: (List<HomeCatalogKey>) -> Unit,
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
        val customOrder = catalogOrderKeys.isNotEmpty()
        RewriteCycleRow(
            title = "Catalog ordering",
            value = if (customOrder) "Reverse addon order" else "Installed addon order",
            values = listOf("Installed addon order", "Reverse addon order"),
        ) { value ->
            onCatalogOrderChanged(
                if (value == "Reverse addon order") {
                    rewriteAddons.asReversed().flatMap { addon ->
                        addon.manifest.catalogs.map { catalog -> HomeCatalogKey(addon.manifest.id, catalog.id) }
                    }
                } else {
                    emptyList()
                },
            )
        }
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
                !debridConnected -> "Connect a debrid provider first. Setup configures Torrentio with your key; MediaFusion stays keyless unless you self-host encrypt."
                configuredAutoSources == autoSourceAddons.size && autoSourceAddons.isNotEmpty() -> "Ready. Torrentio uses your active debrid provider; MediaFusion is keyless unless self-hosted."
                configuredAutoSources > 0 -> "$configuredAutoSources of ${autoSourceAddons.size.coerceAtLeast(2)} sources ready. Run setup again to retry the unavailable source."
                else -> "Configure Torrentio for debrid streaming. MediaFusion catalogs stay available without sending your key to a third party."
            },
            actionLabel = if (configuredAutoSources > 0) "Reconfigure" else "Set up",
            onClick = onConfigureAutoSources,
        )
        autoSourceAddons.forEach { addon ->
            RewriteValueRow(
                title = addon.manifest.name,
                value = if (addon.configuredManifestUrl.isNullOrBlank()) "Needs setup" else "Ready for active debrid provider",
            )
        }
    }
    RewriteSettingsGroup("Installed addons") {
        visibleAddons.forEach { addon ->
            RewriteValueRow(
                title = addon.manifest.name,
                value = "Managed source",
            )
        }
        if (visibleAddons.isEmpty()) RewriteValueRow("Sources", "No managed addons available")
    }
}
