package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.sluggyard.tv.domain.model.TmdbSettings
import kotlinx.coroutines.launch

@Composable
internal fun ContentDiscoverySettings(
    facade: RewriteSettingsFacade,
    tmdb: TmdbSettings,
    onTmdbEnabled: (Boolean) -> Unit,
    onEnrichContinueWatching: (Boolean) -> Unit,
    communityAddonCount: Int,
) {
    val scope = rememberCoroutineScope()
    val installedAddons by facade.addonPreferences.installedAddonUrls.collectAsState(initial = emptyList())
    val addonStates by facade.addonPreferences.addonEnabledStates.collectAsState(initial = emptyMap())
    RewriteSettingsGroup("Content enrichment") {
        RewriteToggleRow("TMDB enrichment", "Improve artwork, metadata, dates, and details.", tmdb.enabled, onTmdbEnabled)
        RewriteToggleRow("Enrich Continue Watching", "Add TMDB artwork and metadata to local progress.", tmdb.enrichContinueWatching, onEnrichContinueWatching)
        RewriteValueRow("Community sources", if (communityAddonCount > 0) "$communityAddonCount managed" else "Managed by PlayFlix")
        RewriteValueRow("Catalog ordering", "Follow installed addon order")
    }
    RewriteSettingsGroup("Installed addons") {
        installedAddons.forEach { url ->
            RewriteToggleRow(
                title = addonLabel(url),
                description = url,
                checked = addonStates[url] ?: true,
                onCheckedChange = { enabled -> scope.launch { facade.addonPreferences.setAddonEnabled(url, enabled) } },
            )
        }
        if (installedAddons.isEmpty()) RewriteValueRow("Sources", "No addons installed")
    }
}
