package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.data.local.TraktAuthState
import com.sluggyard.tv.data.local.WatchProgressSource
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.domain.model.LibrarySourceMode
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridConnection
import kotlinx.coroutines.launch

@Composable
internal fun IntegrationsSettings(
    facade: RewriteSettingsFacade,
    traktAuth: TraktAuthState,
    tmdb: TmdbSettings,
    debrid: DebridSettings,
    openSubtitlesEnabled: Boolean,
    openSubtitlesKey: String,
    debridConnection: RewriteDebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onDebridEnabledChanged: suspend (Boolean) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedService by remember { mutableStateOf<DebridService?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var credentialTarget by remember { mutableStateOf<String?>(null) }
    var credentialError by remember { mutableStateOf<String?>(null) }
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
        RewriteCycleRow("Continue Watching source", watchSource.name, listOf("TRAKT", "SLUGYARD_SYNC")) { value -> scope.launch { facade.setWatchProgressSource(WatchProgressSource.valueOf(value)) } }
        RewriteCycleRow("Library source", librarySource.name, listOf("LOCAL", "TRAKT")) { value -> scope.launch { facade.setLibrarySourceMode(LibrarySourceMode.valueOf(value)) } }
    }
    RewriteSettingsGroup("TMDB and ratings") {
        RewriteValueRow("TMDB", if (tmdb.enabled) "Enabled" else "Disabled")
        RewriteValueRow("Ratings", "TMDB setting controls player metadata")
    }
    RewriteSettingsGroup("Debrid") {
        RewriteToggleRow("Debrid playback", "Use connected debrid services for supported streams.", debrid.enabled) {
            scope.launch {
                facade.setDebridEnabled(it)
                onDebridEnabledChanged(it)
            }
        }
        DebridService.entries.forEach { service ->
            val connected = debridConnection.isConnected(service)
            RewriteActionRow(service.label, if (connected) "Connected${if (debridConnection.activeService == service) " and active" else ""}" else "Connect encrypted credentials.") {
                if (connected) {
                    scope.launch { onSelect(service) }
                } else {
                    apiKey = ""
                    credentialError = null
                    selectedService = service
                }
            }
        }
    }
    RewriteSettingsGroup("OpenSubtitles") {
        RewriteToggleRow("OpenSubtitles", "Enrich subtitle results after playback starts.", openSubtitlesEnabled) { scope.launch { facade.setOpenSubtitlesEnabled(it) } }
        RewriteActionRow("Credentials", if (openSubtitlesKey.isBlank()) "Not configured" else "Saved securely") {
            apiKey = ""
            credentialError = null
            credentialTarget = "OpenSubtitles"
        }
    }
    selectedService?.let { service ->
        RewriteCredentialDialog(
            title = "Connect ${service.label}",
            apiKey = apiKey,
            errorMessage = credentialError,
            onApiKeyChanged = { apiKey = it; credentialError = null },
            onConfirm = {
                val value = credentialSubmissionValue(apiKey) ?: return@RewriteCredentialDialog
                scope.launch {
                    runCatching { onConnect(service, value) }
                        .onSuccess {
                            apiKey = ""
                            credentialError = null
                            selectedService = null
                        }
                        .onFailure { error -> credentialError = error.message ?: "Could not connect ${service.label}." }
                }
            },
            onDismiss = { apiKey = ""; credentialError = null; selectedService = null },
        )
    }
    credentialTarget?.let { target ->
        RewriteCredentialDialog(
            title = "$target API key",
            apiKey = apiKey,
            errorMessage = credentialError,
            onApiKeyChanged = { apiKey = it; credentialError = null },
            onConfirm = {
                val value = credentialSubmissionValue(apiKey) ?: return@RewriteCredentialDialog
                scope.launch {
                    runCatching { facade.setOpenSubtitlesApiKey(value) }
                        .onSuccess {
                            apiKey = ""
                            credentialError = null
                            credentialTarget = null
                        }
                        .onFailure { error -> credentialError = error.message ?: "Could not save the API key." }
                }
            },
            onDismiss = { apiKey = ""; credentialError = null; credentialTarget = null },
        )
    }
}

@Composable
private fun RewriteCredentialDialog(
    title: String,
    apiKey: String,
    errorMessage: String?,
    onApiKeyChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChanged,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                    label = { Text("API key") },
                )
                errorMessage?.let { Text(it, color = com.sluggyard.tv.ui.design.SlugYardPalette.Danger) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = apiKey.isNotBlank()) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
