package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.data.local.MoreLikeThisSourcePreference
import com.sluggyard.tv.data.local.TraktAuthState
import com.sluggyard.tv.data.local.WatchProgressSource
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.domain.model.LibrarySourceMode
import com.sluggyard.tv.domain.model.MDBListSettings
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridConnection
import kotlinx.coroutines.launch

@Composable
internal fun IntegrationsSettings(
    facade: RewriteSettingsFacade,
    traktAuth: TraktAuthState,
    tmdb: TmdbSettings,
    mdblist: MDBListSettings,
    debrid: DebridSettings,
    openSubtitlesEnabled: Boolean,
    openSubtitlesKey: String,
    openSubtitlesAutoDownload: Boolean,
    debridConnection: RewriteDebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedService by remember { mutableStateOf<DebridService?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var credentialTarget by remember { mutableStateOf<String?>(null) }
    var traktCode by remember { mutableStateOf<com.sluggyard.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto?>(null) }
    var traktMessage by remember { mutableStateOf<String?>(null) }

    RewriteSettingsGroup("Trakt") {
        RewriteValueRow("Connection", if (traktAuth.isAuthenticated) "Connected${traktAuth.username?.let { " as $it" } ?: ""}" else "Not connected")
        RewriteActionRow("Authorize this device", "Use Trakt device authorization. No API key is required.") {
            scope.launch { facade.startTraktDeviceAuth().onSuccess { traktCode = it }.onFailure { traktMessage = it.message } }
        }
        traktCode?.let { code ->
            RewriteValueRow("Open", code.verificationUrl)
            RewriteValueRow("Code", code.userCode)
            RewriteActionRow("Finish authorization", "Poll Trakt after entering the code.") {
                scope.launch {
                    traktMessage = facade.pollTraktDeviceAuth().toString()
                    traktCode = null
                }
            }
        }
        traktMessage?.let { RewriteValueRow("Trakt", it) }
        if (traktAuth.isAuthenticated) RewriteActionRow("Disconnect Trakt", "Remove the Trakt token for this profile.") { scope.launch { facade.disconnectTrakt() } }
        val watchSource by facade.traktSettings.watchProgressSource.collectAsState(initial = WatchProgressSource.TRAKT)
        val librarySource by facade.traktSettings.librarySourceMode.collectAsState(initial = LibrarySourceMode.LOCAL)
        val moreSource by facade.traktSettings.moreLikeThisSource.collectAsState(initial = MoreLikeThisSourcePreference.TMDB)
        RewriteCycleRow("Continue Watching source", watchSource.name, listOf("TRAKT", "SLUGYARD_SYNC")) { value -> scope.launch { facade.setWatchProgressSource(WatchProgressSource.valueOf(value)) } }
        RewriteCycleRow("Library source", librarySource.name, listOf("LOCAL", "TRAKT")) { value -> scope.launch { facade.setLibrarySourceMode(LibrarySourceMode.valueOf(value)) } }
        RewriteCycleRow("More Like This source", moreSource.name, MoreLikeThisSourcePreference.entries.map { it.name }) { value -> scope.launch { facade.setMoreLikeThisSource(MoreLikeThisSourcePreference.valueOf(value)) } }
    }
    RewriteSettingsGroup("TMDB and ratings") {
        RewriteValueRow("TMDB", if (tmdb.enabled) "Enabled" else "Disabled")
        RewriteValueRow("MDBList", if (mdblist.enabled) "Configured" else "Not configured")
        RewriteToggleRow("MDBList ratings", "Show ratings from your MDBList account.", mdblist.enabled) { scope.launch { facade.setMdblistEnabled(it) } }
        RewriteActionRow("Set MDBList API key", "The key is stored for this profile and never displayed after saving.") { credentialTarget = "MDBList" }
    }
    RewriteSettingsGroup("Debrid") {
        RewriteToggleRow("Debrid playback", "Use connected debrid services for supported streams.", debrid.enabled) { scope.launch { facade.setDebridEnabled(it) } }
        DebridService.entries.forEach { service ->
            val connected = debridConnection.isConnected(service)
            RewriteActionRow(service.label, if (connected) "Connected${if (debridConnection.activeService == service) " and active" else ""}" else "Connect encrypted credentials.") {
                if (connected) scope.launch { onSelect(service) } else selectedService = service
            }
        }
    }
    RewriteSettingsGroup("OpenSubtitles") {
        RewriteToggleRow("OpenSubtitles", "Enrich subtitle results after playback starts.", openSubtitlesEnabled) { scope.launch { facade.setOpenSubtitlesEnabled(it) } }
        RewriteToggleRow("Automatic subtitle download", "Download a matching subtitle when available.", openSubtitlesAutoDownload) { scope.launch { facade.setOpenSubtitlesAutoDownload(it) } }
        RewriteActionRow("Credentials", if (openSubtitlesKey.isBlank()) "Not configured" else "Saved securely") { credentialTarget = "OpenSubtitles" }
    }
    selectedService?.let { service ->
        AlertDialog(
            onDismissRequest = { selectedService = null },
            title = { Text("Connect ${service.label}") },
            text = { OutlinedTextField(apiKey, { apiKey = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("API key") }) },
            confirmButton = {
                Button(onClick = { scope.launch { runCatching { onConnect(service, apiKey) }.onSuccess { apiKey = ""; selectedService = null } } }, enabled = apiKey.isNotBlank()) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { selectedService = null }) { Text("Cancel") } },
        )
    }
    credentialTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { credentialTarget = null },
            title = { Text("$target API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(apiKey, { apiKey = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("API key") })
                }
            },
            confirmButton = {
                Button(onClick = { scope.launch { if (target == "MDBList") facade.setMdblistApiKey(apiKey) else facade.setOpenSubtitlesApiKey(apiKey); apiKey = ""; credentialTarget = null } }, enabled = apiKey.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { credentialTarget = null }) { Text("Cancel") } },
        )
    }
}
