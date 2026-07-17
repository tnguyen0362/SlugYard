package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.debrid.DebridProvider
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.ui.theme.NuvioTheme

/** A consumer setup screen: choose one service, enter its key, and stream. */
@Composable
fun PlayFlixDebridSettingsContent(
    viewModel: DebridSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isValidating by viewModel.validating.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val providers = remember { DebridProviders.visible() }
    var selectedProviderId by remember { mutableStateOf(uiState.preferredResolverProviderId.ifBlank { DebridProviders.TORBOX_ID }) }
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.first()
    var apiKeyInput by remember(selectedProvider.id) {
        mutableStateOf(uiState.apiKeyFor(selectedProvider.id))
    }
    var hasChanges by remember(selectedProvider.id) { mutableStateOf(false) }
    var sourceManifestUrl by remember { mutableStateOf("") }
    var sourceSaved by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.validationError.collect { connectionMessage = it }
    }

    LaunchedEffect(uiState.apiKeyFor(selectedProvider.id)) {
        if (!hasChanges) apiKeyInput = uiState.apiKeyFor(selectedProvider.id)
    }
    LaunchedEffect(uiState.preferredResolverProviderId) {
        uiState.preferredResolverProviderId
            .takeIf { preferred -> providers.any { it.id == preferred } }
            ?.let { selectedProviderId = it }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Connect debrid", style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
        Text(
            "Choose your service, paste its API key, and start streaming.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            providers.forEach { provider ->
                DebridProviderChoice(
                    provider = provider,
                    connected = uiState.apiKeyFor(provider.id).isNotBlank(),
                    selected = provider.id == selectedProvider.id,
                    onClick = {
                        selectedProviderId = provider.id
                        viewModel.setPreferredResolverProviderId(provider.id)
                    },
                    modifier = if (provider.id == DebridProviders.TORBOX_ID && initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else Modifier
                )
            }
        }

        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it; hasChanges = true },
            label = { Text("${selectedProvider.displayName} API key") },
            placeholder = { Text("Paste your API key") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboardController?.hide()
                if (apiKeyInput.isNotBlank()) {
                    viewModel.validateAndSaveProviderApiKey(selectedProvider.id, apiKeyInput) {
                        hasChanges = false
                        connectionMessage = "${selectedProvider.displayName} connected"
                    }
                }
            }),
            modifier = Modifier.fillMaxWidth(),
            colors = debridTextFieldColors(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    viewModel.validateAndSaveProviderApiKey(selectedProvider.id, apiKeyInput) {
                        hasChanges = false
                        connectionMessage = "${selectedProvider.displayName} connected"
                    }
                },
                enabled = apiKeyInput.isNotBlank() && hasChanges && !isValidating
            ) { Text(if (isValidating) "Checking…" else "Connect") }

            if (uiState.apiKeyFor(selectedProvider.id).isNotBlank()) {
                Button(onClick = {
                    viewModel.validateAndSaveProviderApiKey(selectedProvider.id, "") { hasChanges = false }
                    apiKeyInput = ""
                }) { Text("Disconnect") }
            }
        }

        Text(
            connectionMessage ?: "Your key is validated before it is saved. PlayFlix automatically enables its curated sources and subtitles.",
            style = MaterialTheme.typography.bodySmall,
            color = if (connectionMessage?.contains("connected") == true) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
        )

        // Advanced source entry stays out of the consumer UI. PlayFlix provisions
        // its curated source set automatically after a debrid account connects.
        if (false) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Optional content sources", style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste your own MediaFusion or Comet manifest URL to add it to the automatic source search.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )
        OutlinedTextField(
            value = sourceManifestUrl,
            onValueChange = { sourceManifestUrl = it; sourceSaved = false },
            label = { Text("Private manifest URL") },
            placeholder = { Text("https://…/manifest.json") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            colors = debridTextFieldColors(),
            singleLine = true
        )
        Button(
            onClick = {
                viewModel.addPrivateContentSourceManifest(sourceManifestUrl) {
                    sourceSaved = true
                    sourceManifestUrl = ""
                }
            },
            enabled = sourceManifestUrl.isNotBlank()
        ) { Text(if (sourceSaved) "Source added" else "Add source") }
        }
    }
}

@Composable
private fun DebridProviderChoice(
    provider: DebridProvider,
    connected: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(onClick = onClick, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(provider.displayName)
            if (connected) Icon(Icons.Default.Check, contentDescription = "Connected")
            if (selected) Text("Selected", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun debridTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NuvioTheme.colors.TextPrimary,
    unfocusedTextColor = NuvioTheme.colors.TextPrimary,
    focusedLabelColor = NuvioTheme.colors.Secondary,
    unfocusedLabelColor = NuvioTheme.colors.TextSecondary,
    cursorColor = NuvioTheme.colors.Secondary,
    focusedBorderColor = NuvioTheme.colors.Secondary,
    unfocusedBorderColor = NuvioTheme.colors.Border,
    focusedPlaceholderColor = NuvioTheme.colors.TextTertiary,
    unfocusedPlaceholderColor = NuvioTheme.colors.TextTertiary
)
