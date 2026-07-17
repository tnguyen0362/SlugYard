package com.nuvio.tv.core.addon

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.core.debrid.DebridProviders

/**
 * PlayFlix default addons - safe Stremio infrastructure only.
 *
 * We ship with official Stremio addons for metadata and subtitles.
 * Content sources are provisioned silently as part of connecting a debrid account.
 */
object PureFireBundledAddons {

    /**
     * Infrastructure addons - always included.
     */
    val INFRASTRUCTURE_ADDONS: List<String> = listOf(
        "https://v3-cinemeta.strem.io",      // Metadata (posters, descriptions, cast)
        "https://opensubtitles-v3.strem.io"  // Subtitle lookup
    )

    /**
     * Content source base URLs (without configuration).
     * These need debrid credentials to be useful.
     */
    val CONTENT_SOURCE_BASE_URLS: List<String> = listOf(
        "https://torrentio.strem.fun",
        "https://mediafusionfortheweebs.midnightignite.me"
    )

    /**
     * A public, no-account MediaFusion configuration. It returns torrent hashes;
     * PlayFlix resolves those hashes using the customer's connected debrid account.
     */
    private const val PLAYFLIX_MEDIAFUSION_MANIFEST =
        "https://mediafusionfortheweebs.midnightignite.me/D-JPpZFd2i8K8NFjTEDOFoYYZKfxv0RyIaBXdhVYa9m-ekkXhqkWB4WsahDS15AFA86fjwu7H2tB1wkkRpE-e17SJIhlmj_HX2-sPV0lC_2hg8XDANf_u4AHq1O-VS-C9ViZJWdm8dTGd4nsrFAVm0IjkAC2-M6YGZ2GPbG7nvklg/manifest.json"

    /**
     * Get all default addons including content sources if debrid is configured.
     */
    fun getDefaultAddons(debridSettings: DebridSettings? = null): List<String> {
        val addons = INFRASTRUCTURE_ADDONS.toMutableList()
        
        // Add content sources if debrid is configured
        if (debridSettings?.hasAnyApiKey == true) {
            addons.addAll(buildConfiguredContentSourceUrls(debridSettings))
        }
        
        return addons
    }

    /**
     * Build configured content source URLs with debrid credentials.
     */
    fun buildConfiguredContentSourceUrls(debridSettings: DebridSettings): List<String> {
        val configuredUrls = mutableListOf<String>()
        
        // Source configuration must use the same service selected by the local
        // resolver; otherwise a multi-service account can search with Torbox
        // and attempt to resolve with Real-Debrid.
        val credential = debridSettings.activeResolverCredential ?: return configuredUrls
        val providerName = when (credential.provider.id) {
            DebridProviders.REAL_DEBRID_ID -> "realdebrid"
            else -> credential.provider.id
        }
        val apiKey = credential.apiKey
        
        // Build Torrentio URL with debrid configuration
        // Format: https://torrentio.strem.fun/debrid={provider}&apiKey={key}/manifest.json
        val torrentioConfig = "debrid=$providerName&apiKey=$apiKey"
        configuredUrls.add("https://torrentio.strem.fun/$torrentioConfig/manifest.json")
        
        // MediaFusion is a static hash source. It carries no customer debrid key;
        // the playback resolver uses the key saved in PlayFlix instead.
        configuredUrls.add(PLAYFLIX_MEDIAFUSION_MANIFEST)
        
        return configuredUrls
    }

    /**
     * Check if a URL matches a default addon.
     * Used to prevent accidental removal of required infrastructure.
     */
    fun isDefaultAddon(url: String): Boolean {
        val normalized = url.trim().trimEnd('/').lowercase()
        return INFRASTRUCTURE_ADDONS.any { default ->
            normalized.startsWith(default.lowercase())
        }
    }

    /**
     * Check if a URL is a content source addon.
     */
    fun isContentSourceAddon(url: String): Boolean {
        val normalized = url.trim().trimEnd('/').lowercase()
        return CONTENT_SOURCE_BASE_URLS.any { base ->
            normalized.startsWith(base.lowercase())
        }
    }
}
