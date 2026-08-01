package com.sluggyard.tv.core.addonprotocol

import com.sluggyard.tv.BuildConfig

/**
 * Fixed backend manifest allowlist for the locked-down SlugYard product.
 *
 * Provider-specific manifest URLs are generated only for the two allowlisted content sources.
 * Torrent hashes returned by those sources are still resolved locally through the active
 * rewrite-owned provider transport. Torrentio stays provisioned even during a transient upstream
 * outage; endpoint availability is handled by the bounded transport, not by removing a source.
 */
object SlugYardCommunitySourcePolicy {
    /** Present only when AIOSTREAMS_BASE_URL is set; keyless stub is never used for streams. */
    val aioStreamsRegistryUrl: String? =
        BuildConfig.AIOSTREAMS_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }
            ?.let { "$it/stremio/manifest.json" }

    /**
     * Bundled PlayFlix community addon (MediaFusion host). Allowlisted for manual install from
     * Community addons, but never part of the default/auto-provisioned set.
     */
    const val PLAYFLIX_MANIFEST_URL =
        "https://mediafusionfortheweebs.midnightignite.me/manifest.json"

    /** User-visible name for the bundled community addon (formerly Purefire). */
    const val PLAYFLIX_DISPLAY_NAME = "PlayFlix"

    val bootstrapManifestUrls: List<String> = buildList {
        add("https://v3-cinemeta.strem.io/manifest.json")
        add("https://opensubtitles-v3.strem.io/manifest.json")
        // Vanilla "where to watch" links — not a torrent source; Details/WatchHub surfaces these.
        add("https://watchhub.strem.io/manifest.json")
        add("https://torrentio.strem.fun/manifest.json")
        // Keyless catalog only — never a pre-baked encrypted user path with embedded credentials.
        add(PLAYFLIX_MANIFEST_URL)
        // Comet cache-first scraper; debrid key injected into configuredManifestUrl on connect.
        add("https://comet.elfhosted.com/manifest.json")
        // Meteor cache-first scraper (MidnightIgnite); debrid key injected on connect.
        add("https://meteorfortheweebs.midnightignite.me/manifest.json")
        // AIOStreams (AnimeTosho / SeaDex / Torz) — only when an opted-in host is configured.
        aioStreamsRegistryUrl?.let(::add)
    }

    val watchHubManifestUrl: String =
        bootstrapManifestUrls.first { it.contains("watchhub", ignoreCase = true) }

    /** Catalog / subtitle / WatchHub manifests that work without a debrid key. */
    val infrastructureManifestUrls: List<String> =
        bootstrapManifestUrls.filter { url ->
            isInfrastructureManifest(url)
        }

    /** Torrent stream sources that need a configured debrid provider. */
    val contentSourceManifestUrls: List<String> =
        bootstrapManifestUrls.filter { url ->
            isContentSourceManifest(url)
        }

    /** Optional community addons shown under Community; never auto-installed. */
    val optionalCommunityManifestUrls: List<String> = listOf(PLAYFLIX_MANIFEST_URL)

    fun isWatchHubManifest(url: String): Boolean =
        "watchhub" in url.lowercase()

    fun isPlayFlixManifest(url: String): Boolean {
        val normalized = url.trim().trimEnd('/').lowercase()
        val playflix = PLAYFLIX_MANIFEST_URL.trimEnd('/').lowercase()
        return normalized == playflix ||
            normalized == playflix.removeSuffix("/manifest.json") ||
            (normalized.contains("mediafusionfortheweebs.midnightignite.me") &&
                !normalized.contains("meteor", ignoreCase = true))
    }

    fun isOptionalCommunityManifest(url: String): Boolean = isPlayFlixManifest(url)

    fun addonDisplayName(addon: ManagedAddon): String =
        if (isPlayFlixManifest(addon.manifestUrl)) PLAYFLIX_DISPLAY_NAME else addon.manifest.name

    fun isContentSourceManifest(url: String): Boolean {
        val normalized = url.lowercase()
        return "torrentio" in normalized ||
            "mediafusion" in normalized ||
            "comet" in normalized ||
            "meteor" in normalized ||
            isAioStreamsManifest(url)
    }

    fun isAioStreamsManifest(url: String): Boolean {
        val normalized = url.lowercase()
        val base = BuildConfig.AIOSTREAMS_BASE_URL.trim().trimEnd('/').lowercase()
        if (base.isNotBlank() && normalized.startsWith(base)) return true
        return "aiostreams" in normalized
    }

    fun isConfiguredEnabledStreamAddon(addon: ManagedAddon): Boolean =
        addon.enabled &&
            AddonResource.STREAM in addon.manifest.resources &&
            !addon.configuredManifestUrl.isNullOrBlank()

    fun isTorrentioReady(addons: List<ManagedAddon>): Boolean = addons.any { addon ->
        isConfiguredEnabledStreamAddon(addon) && addon.manifestUrl.contains("torrentio", ignoreCase = true)
    }

    fun isInfrastructureManifest(url: String): Boolean =
        !isContentSourceManifest(url)

    /**
     * Manifests to fetch for a provision pass.
     * Without debrid, skip torrent content sources so Play falls through to WatchHub cleanly.
     * PlayFlix (optional community) is never auto-provisioned — install from Community addons.
     */
    fun provisionManifestUrls(debridConfigured: Boolean): List<String> =
        (if (debridConfigured) bootstrapManifestUrls else infrastructureManifestUrls)
            .filterNot(::isOptionalCommunityManifest)

    fun isCommunityPackInstalled(addons: List<ManagedAddon>): Boolean =
        addons.any {
            it.manifestUrl in bootstrapManifestUrls &&
                it.enabled &&
                !isOptionalCommunityManifest(it.manifestUrl)
        }

    fun isPlayFlixInstalled(addons: List<ManagedAddon>): Boolean =
        addons.any { addon ->
            addon.enabled && isPlayFlixManifest(addon.manifestUrl)
        }

    fun isWatchHubInstalled(addons: List<ManagedAddon>): Boolean =
        addons.any { addon ->
            addon.enabled && (
                addon.manifestUrl.equals(watchHubManifestUrl, ignoreCase = true) ||
                    isWatchHubManifest(addon.manifestUrl) ||
                    "watchhub" in "${addon.manifest.id} ${addon.manifest.name}".lowercase()
                )
        }

    /**
     * Stremio-like Play routing: with no active debrid connection, do not enter the torrent
     * source auto-pick path — open WatchHub "where to watch" instead.
     */
    fun shouldRoutePlayToWatchHub(hasActiveDebrid: Boolean): Boolean = !hasActiveDebrid
}
