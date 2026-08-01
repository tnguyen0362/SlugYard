package com.sluggyard.tv.ui.app.watchhub

import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder

data class WatchHubPlatform(
    val label: String,
    val detail: String? = null,
)

/**
 * Loads WatchHub "where to watch" rows for the dedicated WatchHub window.
 * Reuses the same addon stream decoding Details uses for availability chips.
 */
class WatchHubDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
) {
    suspend fun loadPlatforms(type: String, id: String): List<WatchHubPlatform> {
        val registry = registrySnapshot()
        val watchHub = registry.enabledAddons.firstOrNull { addon ->
            SlugYardCommunitySourcePolicy.isWatchHubManifest(addon.manifestUrl) ||
                "watchhub" in listOf(
                    addon.manifest.id,
                    addon.manifest.name,
                    addon.manifestUrl,
                ).joinToString(" ").lowercase()
        } ?: return emptyList()
        val response = gateway.fetchStreams(
            watchHub.configuredManifestUrl ?: watchHub.manifestUrl,
            type,
            id,
        )
        val streams = when (response) {
            is AddonTransportResult.Success -> StremioResponseDecoder.streamItems(response.value)
            else -> return emptyList()
        }
        return streams
            .mapNotNull { stream ->
                val label = stream.sourceName?.takeIf(String::isNotBlank)
                    ?: stream.title.takeIf { it.isNotBlank() && it != "Available stream" }
                    ?: return@mapNotNull null
                val cleaned = label.replace(Regex("\\s+"), " ").trim()
                val detail = stream.description?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
                WatchHubPlatform(label = cleaned, detail = detail)
            }
            .distinctBy { it.label.lowercase() }
            .take(16)
    }
}
