package com.sluggyard.tv.ui.app.settings

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
import com.sluggyard.tv.ui.app.debrid.DebridConnection
import kotlinx.coroutines.launch

@Composable
internal fun IntegrationsSettings(
    facade: SettingsFacade,
    traktAuth: TraktAuthState,
    debridConnection: DebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onDisconnect: suspend (DebridService) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedService by remember { mutableStateOf<DebridService?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var credentialError by remember { mutableStateOf<String?>(null) }
    var traktCode by remember { mutableStateOf<com.sluggyard.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto?>(null) }
    var traktMessage by remember { mutableStateOf<String?>(null) }

    val watchProgressSource by facade.traktSettings.watchProgressSource.collectAsState(
        initial = WatchProgressSource.TRAKT,
    )
    SettingsGroup("Trakt") {
        ValueRow("Connection", if (traktAuth.isAuthenticated) "Connected${traktAuth.username?.let { " as $it" } ?: ""}" else "Not connected")
        if (traktAuth.isAuthenticated) {
            CycleRow(
                title = "Continue Watching source",
                value = watchProgressSourceLabel(watchProgressSource),
                values = listOf(
                    watchProgressSourceLabel(WatchProgressSource.TRAKT),
                    watchProgressSourceLabel(WatchProgressSource.SLUGYARD_SYNC),
                ),
            ) { label ->
                val source = when (label) {
                    watchProgressSourceLabel(WatchProgressSource.SLUGYARD_SYNC) -> WatchProgressSource.SLUGYARD_SYNC
                    else -> WatchProgressSource.TRAKT
                }
                scope.launch { facade.setWatchProgressSource(source) }
            }
            Text(
                "Trakt scrobbling stays active. This only chooses whether Home Continue Watching uses Trakt or SlugYard Sync.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = com.sluggyard.tv.ui.design.SlugYardPalette.OnCanvasMuted,
            )
        }
        if (!traktAuth.isAuthenticated) {
            ActionRow("Connect with Trakt", "Scan a QR code with your phone to link this device.") {
                scope.launch { facade.startTraktDeviceAuth().onSuccess { traktCode = it }.onFailure { traktMessage = it.message } }
            }
            traktCode?.let { code ->
                val activationUrl = remember(code) {
                    val base = code.verificationUrl.trimEnd('/')
                    "$base/${code.userCode}"
                }
                TraktQrCard(
                    activationUrl = activationUrl,
                    userCode = code.userCode,
                    verificationUrl = code.verificationUrl,
                )
                LaunchedEffect(code) {
                    var intervalMs = 2500L
                    repeat(240) {
                        kotlinx.coroutines.delay(intervalMs)
                        if (traktCode?.userCode != code.userCode) return@LaunchedEffect
                        when (val result = facade.pollTraktDeviceAuth()) {
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Pending,
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.AlreadyUsed -> Unit
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.SlowDown -> {
                                intervalMs = (result.pollIntervalSeconds.coerceAtLeast(1) * 1000L)
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Approved -> {
                                traktMessage = "Connected${result.username?.let { " as $it" } ?: ""}"
                                traktCode = null
                                return@LaunchedEffect
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Denied -> {
                                traktMessage = "Authorization denied."
                                traktCode = null
                                return@LaunchedEffect
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Expired -> {
                                traktMessage = "QR code expired. Try again."
                                traktCode = null
                                return@LaunchedEffect
                            }
                            is com.sluggyard.tv.data.repository.TraktTokenPollResult.Failed -> {
                                traktMessage = result.reason
                                traktCode = null
                                return@LaunchedEffect
                            }
                        }
                    }
                    if (traktCode?.userCode == code.userCode) {
                        traktMessage = "QR code expired. Try again."
                        traktCode = null
                    }
                }
            }
        }
        traktMessage?.let { ValueRow("Trakt", it) }
        if (traktAuth.isAuthenticated) ActionRow("Disconnect Trakt", "Remove the Trakt token for this profile.") { scope.launch { facade.disconnectTrakt() } }
    }
    val tmdb by facade.tmdbSettings.collectAsState(initial = com.sluggyard.tv.domain.model.TmdbSettings())
    SettingsGroup("TMDB") {
        ToggleRow(
            "Enable TMDB",
            "Use The Movie Database for Home catalogs, artwork, and metadata.",
            tmdb.enabled,
        ) { scope.launch { facade.setTmdbEnabled(it) } }
        ToggleRow(
            "Continue Watching artwork",
            "Refresh Continue Watching posters from TMDB when artwork is missing or stale.",
            tmdb.enrichContinueWatching && tmdb.enabled,
        ) { enabled ->
            scope.launch {
                if (enabled && !tmdb.enabled) facade.setTmdbEnabled(true)
                facade.setTmdbEnrichContinueWatching(enabled)
            }
        }
    }
    SettingsGroup("Cloud / Debrid") {
        Text(
            "Connecting a provider in Cloud Manager turns debrid playback on automatically.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = com.sluggyard.tv.ui.design.SlugYardPalette.OnCanvasMuted,
        )
        DebridService.entries.forEach { service ->
            val connected = debridConnection.isConnected(service)
            ActionRow(
                service.label,
                if (connected) {
                    "Connected${if (debridConnection.activeService == service) " and active" else ""}"
                } else {
                    "Connect in Cloud Manager"
                },
            ) {
                if (connected) {
                    scope.launch { onSelect(service) }
                } else {
                    apiKey = ""
                    credentialError = null
                    selectedService = service
                }
            }
            if (connected) {
                ActionRow("Remove ${service.label}", "Delete the saved credential for this profile.") {
                    scope.launch { onDisconnect(service) }
                }
            }
        }
    }
    selectedService?.let { service ->
        CredentialDialog(
            title = "Connect ${service.label}",
            apiKey = apiKey,
            errorMessage = credentialError,
            onApiKeyChanged = { apiKey = it; credentialError = null },
            onConfirm = {
                val value = credentialSubmissionValue(apiKey) ?: return@CredentialDialog
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
}

@Composable
private fun TraktQrCard(
    activationUrl: String,
    userCode: String,
    verificationUrl: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QrCode(content = activationUrl, modifier = Modifier.padding(vertical = 8.dp))
        Text("Scan with your phone's camera to connect", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        Text(
            "Or visit $verificationUrl and enter code $userCode",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = com.sluggyard.tv.ui.design.SlugYardPalette.OnCanvasMuted,
        )
    }
}

@Composable
private fun CredentialDialog(
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
            Button(onClick = onConfirm, enabled = apiKey.isNotBlank()) { Text("Save & Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun watchProgressSourceLabel(source: WatchProgressSource): String = when (source) {
    WatchProgressSource.TRAKT -> "Trakt"
    WatchProgressSource.SLUGYARD_SYNC -> "SlugYard Sync"
}
