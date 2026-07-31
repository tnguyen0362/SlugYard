package com.sluggyard.tv.ui.rewrite.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics
import kotlinx.coroutines.launch

@Composable
internal fun ContentDiscoverySettings(
    tmdb: TmdbSettings,
    onTmdbEnabled: (Boolean) -> Unit,
    onTmdbEnrichContinueWatching: (Boolean) -> Unit,
    rewriteAddons: List<ManagedAddon>,
    debridConnected: Boolean,
    onInstallCommunityAddons: suspend () -> Unit,
    onUninstallCommunityAddons: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var setupBusy by remember { mutableStateOf(false) }
    var setupMessage by remember { mutableStateOf<String?>(null) }
    var showSetupDialog by remember { mutableStateOf(false) }
    var uninstallConfirm by remember { mutableStateOf(false) }
    val packInstalled = SlugYardCommunitySourcePolicy.isCommunityPackInstalled(rewriteAddons)
    val watchHubInstalled = SlugYardCommunitySourcePolicy.isWatchHubInstalled(rewriteAddons)
    val contentSourcesReady = rewriteAddons.any(SlugYardCommunitySourcePolicy::isConfiguredEnabledStreamAddon)
    val torrentioReady = SlugYardCommunitySourcePolicy.isTorrentioReady(rewriteAddons)
    val catalogAddons = rewriteAddons.filter { addon ->
        !SlugYardCommunitySourcePolicy.isContentSourceManifest(addon.manifestUrl) &&
            !SlugYardCommunitySourcePolicy.isWatchHubManifest(addon.manifestUrl)
    }
    RewriteSettingsGroup("Content enrichment") {
        RewriteToggleRow("TMDB enrichment", "Improve artwork, metadata, cast, and details.", tmdb.enabled, onTmdbEnabled)
        RewriteToggleRow("Continue Watching artwork", "Use TMDB enrichment for saved playback tiles.", tmdb.enrichContinueWatching, onTmdbEnrichContinueWatching)
    }
    RewriteSettingsGroup("Community addons") {
        Text(
            when {
                debridConnected && packInstalled ->
                    if (torrentioReady) {
                        "SlugYard Sources are installed. Torrentio and the configured stream addons are enabled."
                    } else {
                        "SlugYard Sources are installed, but Torrentio is not currently configured as an enabled stream source."
                    }
                debridConnected ->
                    "Your debrid key is connected. Install community addons for catalogs, WatchHub, and stream sources."
                packInstalled && watchHubInstalled ->
                    "Community addons are installed. Without debrid, Play opens WatchHub (where to watch)."
                else ->
                    "Install community addons for catalogs and WatchHub. Connect TorBox / Real-Debrid / Premiumize in Cloud Manager for torrent streams."
            },
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = com.sluggyard.tv.ui.design.SlugYardPalette.OnCanvasMuted,
        )
        RewriteActionRow(
            title = "SlugYard Sources",
            description = when {
                packInstalled && debridConnected && torrentioReady && contentSourcesReady -> "Installed with Torrentio and debrid stream sources."
                packInstalled && debridConnected && contentSourcesReady -> "Installed with debrid stream sources; Torrentio is not ready."
                packInstalled && watchHubInstalled -> "Installed (WatchHub + catalogs)."
                packInstalled -> "Installed."
                else -> "Install the bundled community addon pack (catalogs, WatchHub, stream sources)."
            },
            actionLabel = if (packInstalled) "Reinstall" else "Install",
            onClick = {
                ExperimentalDiagnostics.event("settings", "community_addons_setup_opened", mapOf("installed" to packInstalled))
                setupMessage = null
                showSetupDialog = true
            },
        )
        if (packInstalled) {
            RewriteActionRow(
                title = "Uninstall community addons",
                description = "Remove curated catalogs, WatchHub, and stream sources from this device.",
                actionLabel = "Uninstall",
                onClick = { uninstallConfirm = true },
            )
        }
        RewriteValueRow(
            title = "Status",
            value = when {
                !packInstalled -> "Not installed"
                debridConnected && contentSourcesReady -> "Installed · debrid ready"
                watchHubInstalled -> "Installed · WatchHub ready"
                else -> "Installed"
            },
        )
        RewriteValueRow(
            title = "WatchHub",
            value = if (watchHubInstalled) "Ready" else "Not installed",
        )
    }
    RewriteSettingsGroup("Catalogs") {
        catalogAddons.forEach { addon ->
            RewriteValueRow(
                title = addon.manifest.name,
                value = "Managed catalog",
            )
        }
        if (catalogAddons.isEmpty()) RewriteValueRow("Catalogs", "No managed catalogs available")
    }
    if (showSetupDialog) {
        CommunityAddonsSetupDialog(
            busy = setupBusy,
            message = setupMessage,
            debridConnected = debridConnected,
            installed = packInstalled,
            onConfirm = {
                ExperimentalDiagnostics.event("settings", "community_addons_install_confirmed", mapOf("debridConnected" to debridConnected))
                setupBusy = true
                setupMessage = null
                scope.launch {
                    runCatching { onInstallCommunityAddons() }
                        .onSuccess {
                            ExperimentalDiagnostics.event("settings", "community_addons_install_succeeded")
                            setupMessage = if (debridConnected) {
                                "Community addons installed. Stream sources are ready."
                            } else {
                                "Community addons installed. WatchHub is ready without debrid."
                            }
                        }
                        .onFailure {
                            ExperimentalDiagnostics.failure("settings", "community_addons_install_failed", it)
                            setupMessage = it.message ?: "Could not install community addons."
                        }
                    setupBusy = false
                }
            },
            onDismiss = {
                if (!setupBusy) {
                    showSetupDialog = false
                    setupMessage = null
                }
            },
        )
    }
    if (uninstallConfirm) {
        CommunityAddonsUninstallDialog(
            busy = setupBusy,
            message = setupMessage,
            onConfirm = {
                ExperimentalDiagnostics.event("settings", "community_addons_uninstall_confirmed")
                setupBusy = true
                setupMessage = null
                scope.launch {
                    runCatching { onUninstallCommunityAddons() }
                        .onSuccess {
                            ExperimentalDiagnostics.event("settings", "community_addons_uninstall_succeeded")
                            setupMessage = "Community addons removed."
                            uninstallConfirm = false
                            setupBusy = false
                        }
                        .onFailure {
                            ExperimentalDiagnostics.failure("settings", "community_addons_uninstall_failed", it)
                            setupMessage = it.message ?: "Could not uninstall community addons."
                            setupBusy = false
                        }
                }
            },
            onDismiss = {
                if (!setupBusy) {
                    uninstallConfirm = false
                    setupMessage = null
                }
            },
        )
    }
}

@Composable
private fun CommunityAddonsSetupDialog(
    busy: Boolean,
    message: String?,
    debridConnected: Boolean,
    installed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    BackHandler(enabled = !busy, onBack = onDismiss)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (installed) "Reinstall community addons" else "Install community addons") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (debridConnected) {
                        "Install catalogs, WatchHub, and stream sources for your active debrid provider."
                    } else {
                        "Install catalogs and WatchHub. Play will show where to watch until you connect a debrid provider."
                    },
                )
                if (busy) {
                    CircularProgressIndicator()
                    Text("Installing…")
                }
                message?.let {
                    Text(
                        it,
                        color = if (it.contains("installed", ignoreCase = true) || it.contains("ready", ignoreCase = true)) {
                            com.sluggyard.tv.ui.design.SlugYardPalette.OnCanvas
                        } else {
                            com.sluggyard.tv.ui.design.SlugYardPalette.Danger
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.focusRequester(focusRequester),
            ) { Text(if (busy) "Working…" else if (installed) "Reinstall" else "Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        },
    )
}

@Composable
private fun CommunityAddonsUninstallDialog(
    busy: Boolean,
    message: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    BackHandler(enabled = !busy, onBack = onDismiss)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Uninstall community addons") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Remove curated catalogs, WatchHub, and stream sources. You can install them again anytime.")
                if (busy) {
                    CircularProgressIndicator()
                    Text("Removing…")
                }
                message?.let {
                    Text(it, color = com.sluggyard.tv.ui.design.SlugYardPalette.Danger)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.focusRequester(focusRequester),
            ) { Text(if (busy) "Working…" else "Uninstall") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
