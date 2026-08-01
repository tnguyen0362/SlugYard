package com.sluggyard.tv.ui.app.debrid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import kotlinx.coroutines.launch

@Composable
fun CloudManagerScreen(
    contentFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester? = null,
    connection: DebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
    onDisconnect: suspend (DebridService) -> Unit,
    onOpenSettings: () -> Unit,
    loadTorboxCloudFiles: (suspend () -> List<TorboxCloudItem>)? = null,
    onPlayCloudItem: (suspend (TorboxCloudItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var selectedService by remember { mutableStateOf(connection.activeService ?: DebridService.entries.first()) }
    var apiKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cloudFiles by remember { mutableStateOf<List<TorboxCloudItem>>(emptyList()) }
    var cloudFilesLoading by remember { mutableStateOf(false) }
    var cloudFilesError by remember { mutableStateOf<String?>(null) }
    var playingItemId by remember { mutableStateOf<Int?>(null) }
    val scroll = rememberScrollState()
    val showTorboxCloud =
        selectedService == DebridService.TORBOX &&
            connection.isConnected(DebridService.TORBOX) &&
            loadTorboxCloudFiles != null

    LaunchedEffect(showTorboxCloud, connection.configuredServices) {
        if (!showTorboxCloud) {
            cloudFiles = emptyList()
            cloudFilesError = null
            cloudFilesLoading = false
            return@LaunchedEffect
        }
        val loader = loadTorboxCloudFiles ?: return@LaunchedEffect
        cloudFilesLoading = true
        cloudFilesError = null
        runCatching { loader() }
            .onSuccess { cloudFiles = it }
            .onFailure { cloudFilesError = it.message ?: "Could not load TorBox cloud files" }
        cloudFilesLoading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .verticalScroll(scroll)
            .padding(SlugYardTvMetrics.ScreenHorizontalInset)
            .padding(bottom = 48.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DebridService.entries.forEachIndexed { index, service ->
                    FilterChip(
                        selected = selectedService == service,
                        onClick = { selectedService = service; message = null },
                        modifier = when (index) {
                            0 -> Modifier
                                .focusRequester(contentFocusRequester)
                                .focusProperties { headerFocusRequester?.let { up = it } }
                            else -> Modifier
                        },
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        if (showTorboxCloud) {
            TorboxCloudFilesSection(
                files = cloudFiles,
                loading = cloudFilesLoading,
                error = cloudFilesError,
                playingItemId = playingItemId,
                playEnabled = onPlayCloudItem != null && playingItemId == null && !busy,
                onPlay = { item ->
                    val play = onPlayCloudItem ?: return@TorboxCloudFilesSection
                    playingItemId = item.id
                    message = null
                    scope.launchSafely(
                        action = { play(item) },
                        onMessage = { message = it },
                        onComplete = { playingItemId = null },
                    )
                },
            )
        }
    }
}

@Composable
private fun TorboxCloudFilesSection(
    files: List<TorboxCloudItem>,
    loading: Boolean,
    error: String?,
    playingItemId: Int?,
    playEnabled: Boolean,
    onPlay: (TorboxCloudItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlugYardPalette.Surface, RoundedCornerShape(6.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Cloud files", style = MaterialTheme.typography.titleLarge)
        Text(
            "Torrents stored in your TorBox cloud. Focus a file and press OK to play.",
            color = SlugYardPalette.OnCanvasMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            loading -> Text(
                "Loading…",
                color = SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
            error != null -> Text(
                error,
                color = SlugYardPalette.Danger,
                style = MaterialTheme.typography.bodyMedium,
            )
            files.isEmpty() -> Text(
                "No cloud torrents yet.",
                color = SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> files.forEach { item ->
                CloudFileRow(
                    item = item,
                    enabled = playEnabled,
                    resolving = playingItemId == item.id,
                    onClick = { onPlay(item) },
                )
            }
        }
    }
}

@Composable
private fun CloudFileRow(
    item: TorboxCloudItem,
    enabled: Boolean,
    resolving: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)
    val status = item.downloadState?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (focused) Color.White.copy(alpha = 0.10f) else SlugYardPalette.SurfaceElevated,
                shape,
            )
            .then(
                if (focused) {
                    Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
                } else {
                    Modifier
                },
            )
            // clickable installs the TV focus target — do not stack a second .focusable().
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = buildString {
                    append(item.name)
                    append(". ")
                    append(item.sizeBytes?.let(::formatCloudFileSize) ?: "Unknown size")
                    status?.let { append(". Status $it") }
                    append(if (resolving) ". Opening…" else ". Play")
                }
                role = Role.Button
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = SlugYardPalette.OnCanvas,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (resolving) {
                Text(
                    "Opening…",
                    style = MaterialTheme.typography.labelLarge,
                    color = SlugYardPalette.OnCanvasMuted,
                )
            } else if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = SlugYardPalette.OnCanvasMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            item.sizeBytes?.let(::formatCloudFileSize) ?: "—",
            style = MaterialTheme.typography.labelLarge,
            color = SlugYardPalette.OnCanvasMuted,
        )
    }
}

private fun formatCloudFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
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
