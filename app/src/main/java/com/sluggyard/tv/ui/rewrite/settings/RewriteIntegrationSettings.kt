package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.sluggyard.tv.domain.model.MDBListSettings
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
    onDisconnect: suspend (DebridService) -> Unit,
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
    val moreLikeThisSource by facade.traktSettings.moreLikeThisSource.collectAsState(initial = com.sluggyard.tv.data.local.MoreLikeThisSourcePreference.TMDB)

    RewriteSettingsGroup("Trakt") {
        RewriteValueRow("Connection", if (traktAuth.isAuthenticated) "Connected${traktAuth.username?.let { " as $it" } ?: ""}" else "Not connected")
        if (!traktAuth.isAuthenticated) {
            RewriteActionRow("Connect with Trakt", "Scan a QR code with your phone to link this device.") {
                scope.launch { facade.startTraktDeviceAuth().onSuccess { traktCode = it }.onFailure { traktMessage = it.message } }
            }
            traktCode?.let { code ->
                val activationUrl = remember(code) {
                    val base = code.verificationUrl.trimEnd('/')
                    "$base/${code.userCode}"
                }
                RewriteTraktQrCard(
                    activationUrl = activationUrl,
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
                LaunchedEffect(code) {
                    var intervalMs = 2500L
                    while (true) {
                        kotlinx.coroutines.delay(intervalMs)
                        when (val result = facade.pollTraktDeviceAuth()) {
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Pending,
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.AlreadyUsed -> Unit
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.SlowDown -> {
                                intervalMs = (result.pollIntervalSeconds.coerceAtLeast(1) * 1000L)
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Approved -> {
                                traktMessage = "Connected${result.username?.let { " as $it" } ?: ""}"
                                traktCode = null
                                break
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Denied -> {
                                traktMessage = "Authorization denied."
                                traktCode = null
                                break
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Expired -> {
                                traktMessage = "QR code expired. Try again."
                                traktCode = null
                                break
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Failed -> {
                                traktMessage = result.reason
                                traktCode = null
                                break
                            }
                        }
                    }
                }
            }
        }
        traktMessage?.let { RewriteValueRow("Trakt", it) }
        if (traktAuth.isAuthenticated) RewriteActionRow("Disconnect Trakt", "Remove the Trakt token for this profile.") { scope.launch { facade.disconnectTrakt() } }
        val watchSource by facade.traktSettings.watchProgressSource.collectAsState(initial = WatchProgressSource.TRAKT)
        val librarySource by facade.traktSettings.librarySourceMode.collectAsState(initial = LibrarySourceMode.LOCAL)
        RewriteCycleRow("Continue Watching source", watchSource.name, listOf("TRAKT", "SLUGYARD_SYNC")) { value -> scope.launch { facade.setWatchProgressSource(WatchProgressSource.valueOf(value)) } }
        RewriteCycleRow("Library source", librarySource.name, listOf("LOCAL", "TRAKT")) { value -> scope.launch { facade.setLibrarySourceMode(LibrarySourceMode.valueOf(value)) } }
        RewriteCycleRow("More Like This source", moreLikeThisSource.name, com.sluggyard.tv.data.local.MoreLikeThisSourcePreference.entries.map { it.name }) { value -> scope.launch { facade.setMoreLikeThisSource(com.sluggyard.tv.data.local.MoreLikeThisSourcePreference.valueOf(value)) } }
    }
    RewriteSettingsGroup("TMDB and ratings") {
        RewriteValueRow("TMDB", if (tmdb.enabled) "Enabled" else "Disabled")
        RewriteValueRow("Ratings", "TMDB setting controls player metadata")
        RewriteActionRow("MDBList credentials", if (mdblist.apiKey.isBlank()) "Not configured" else "Saved securely") {
            apiKey = ""
            credentialError = null
            credentialTarget = "MDBList"
        }
        if (mdblist.apiKey.isNotBlank()) {
            RewriteActionRow("Clear MDBList credentials", "Delete the saved MDBList key for this profile.") {
                scope.launch { facade.setMdblistApiKey("") }
            }
        }
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
                if (connected) {
                    RewriteActionRow("Remove ${service.label}", "Delete the saved credential for this profile.") {
                        scope.launch { onDisconnect(service) }
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
        RewriteToggleRow("Automatic subtitle download", "Download matching subtitles when playback starts.", openSubtitlesAutoDownload) {
            scope.launch { facade.setOpenSubtitlesAutoDownload(it) }
        }
        if (openSubtitlesKey.isNotBlank()) {
            RewriteActionRow("Clear credentials", "Delete the saved OpenSubtitles key for this profile.") {
                scope.launch { facade.setOpenSubtitlesApiKey("") }
            }
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
                val target = credentialTarget
                scope.launch {
                    runCatching {
                        when (target) {
                            "MDBList" -> facade.setMdblistApiKey(value)
                            else -> facade.setOpenSubtitlesApiKey(value)
                        }
                    }
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
private fun RewriteTraktQrCard(
    activationUrl: String,
    userCode: String,
    verificationUrl: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RewriteQrCode(content = activationUrl, modifier = Modifier.padding(vertical = 8.dp))
        Text("Scan with your phone's camera to connect", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        Text(
            "Or visit $verificationUrl and enter code $userCode",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = com.sluggyard.tv.ui.design.SlugYardPalette.OnCanvasMuted,
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
                    // Long provider keys are entered with a TV remote. Keep them visible so users
                    // can verify characters instead of losing the entire key to one typo.
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
