package com.sluggyard.tv.ui.rewrite.debrid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.coroutines.launch
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics

@Composable
fun RewriteCloudManagerScreen(
    contentFocusRequester: FocusRequester,
    connection: RewriteDebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
    onDisconnect: suspend (DebridService) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var selectedService by remember { mutableStateOf(connection.activeService ?: DebridService.entries.first()) }
    var apiKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .padding(SlugYardTvMetrics.ScreenHorizontalInset),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Cloud Manager", style = MaterialTheme.typography.displaySmall)
        Text(
            "Connect and manage the cloud service used to resolve streams.",
            color = SlugYardPalette.OnCanvasMuted,
            style = MaterialTheme.typography.bodyLarge,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlugYardPalette.Surface, RoundedCornerShape(6.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val active = connection.activeService
            Text(
                if (active == null) "No cloud service connected"
                else "${serviceLabel(active)} is active",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Connect one or more cloud services here. The active service is used for torrent resolution.",
                color = SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DebridService.entries.forEachIndexed { index, service ->
                    FilterChip(
                        selected = selectedService == service,
                        onClick = { selectedService = service; message = null },
                        modifier = if (index == 0) Modifier.focusRequester(contentFocusRequester) else Modifier,
                        label = { Text(serviceLabel(service)) },
                    )
                }
            }
            if (connection.isConnected(selectedService)) {
                Text(
                    if (connection.activeService == selectedService) "Connected and active" else "Connected",
                    color = SlugYardPalette.OnCanvas,
                    style = MaterialTheme.typography.bodyLarge,
                )
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (connection.activeService != selectedService) {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launchSafely(
                                action = { onSelect(selectedService) },
                                onMessage = { message = it },
                                onComplete = { busy = false },
                            )
                        }) { Text(if (busy) "Activating..." else "Make active") }
                    }
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launchSafely(
                            action = { onDisconnect(selectedService) },
                            onMessage = { message = it },
                            onComplete = { busy = false },
                        )
                    }) { Text("Disconnect") }
                }
            } else {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${serviceLabel(selectedService)} API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(enabled = !busy && apiKey.isNotBlank(), onClick = {
                    busy = true
                    scope.launchSafely(
                        action = { onConnect(selectedService, apiKey) },
                        onMessage = { message = it },
                        onComplete = { busy = false; if (message == null) apiKey = "" },
                    )
                }) { Text(if (busy) "Validating..." else "Connect") }
            }
            message?.let { Text(it, color = SlugYardPalette.Danger, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onOpenSettings, enabled = !busy) {
                Text("Open all integration settings")
            }
        }
    }
}

private fun serviceLabel(service: DebridService): String =
    service.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

private fun kotlinx.coroutines.CoroutineScope.launchSafely(
    action: suspend () -> Unit,
    onMessage: (String) -> Unit,
    onComplete: () -> Unit,
) {
    launch {
        runCatching { action() }
            .onFailure { onMessage(it.message ?: "Cloud service operation failed") }
        onComplete()
    }
}
