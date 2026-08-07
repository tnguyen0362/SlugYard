package com.sluggyard.tv.core.addonprotocol

import com.sluggyard.tv.BuildConfig

/**
 * Fixed backend manifest allowlist for the locked-down SlugYard product.
 *
 * Provider-specific manifest URLs are generated only for the allowlisted content sources.
 * Torrent hashes returned by those sources are still resolved locally through the active
 * rewrite-owned provider transport. Torrentio stays provisioned even during a transient upstream
 * outage; endpoint availability is handled by the bounded transport, not by removing a source.
 *
 * PlayFlix is the **app** product name — not a separate Stremio stream pack. Content scrapers are
 * Torrentio, MediaFusion, Meteor, and optionally AIOStreams (real upstream names/logos).
 */
object SlugYardCommunitySourcePolicy {
    /** Present only when AIOSTREAMS_BASE_URL is set; keyless stub is never used for streams. */
    val aioStreamsRegistryUrl: String? =
        BuildConfig.AIOSTREAMS_BASE_URL.trim().trimEnd('/').takeIf { it.isNotBlank() }
            ?.let { "$it/stremio/manifest.json" }

    /**
     * Bundled MediaFusion host (MidnightIgnite). Same default pack as Torrentio/Meteor; keep
     * the historical constant name for callers/migrations.
     */
    const val PLAYFLIX_MANIFEST_URL =
        "https://mediafusionfortheweebs.midnightignite.me/manifest.json"

    /** @deprecated Prefer real upstream [ManagedAddon.manifest] branding (MediaFusion). */
    @Deprecated("PlayFlix is the app, not a stream pack name")
    const val PLAYFLIX_DISPLAY_NAME = "MediaFusion"

    /** @deprecated Use upstream manifest.version. */
    @Deprecated("PlayFlix is the app, not a stream pack name")
    const val PLAYFLIX_DISPLAY_VERSION = "1.1.0"

    /** @deprecated Use upstream manifest.description. */
    @Deprecated("PlayFlix is the app, not a stream pack name")
    const val PLAYFLIX_DISPLAY_DESCRIPTION = "Third-party scrape"

    val bootstrapManifestUrls: List<String> = buildList {
        add("https://v3-cinemeta.strem.io/manifest.json")
        add("https://opensubtitles-v3.strem.io/manifest.json")
        // Vanilla "where to watch" links — not a torrent source; Details/WatchHub surfaces these.
        add("https://watchhub.strem.io/manifest.json")
        add("https://torrentio.strem.fun/manifest.json")
        // Keyless catalog only — never a pre-baked encrypted user path with embedded credentials.
        add(PLAYFLIX_MANIFEST_URL)
        // Comet removed from default pack — duplicate cache scrape vs Meteor/Torrentio was
        // burning leanback CPU during Finding and after auto-play (Onn frame drops).
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

    /**
     * Optional Community catalog entries (manual install only). Empty — MediaFusion is a default
     * pack scraper, not a separate "PlayFlix" community addon.
     */
    val optionalCommunityManifestUrls: List<String> = emptyList()

    fun isWatchHubManifest(url: String): Boolean =
        "watchhub" in url.lowercase()

    /** True for the bundled MediaFusion host URL (legacy name: PlayFlix manifest). */
    fun isPlayFlixManifest(url: String): Boolean {
        val normalized = url.trim().trimEnd('/').lowercase()
        val mediaFusion = PLAYFLIX_MANIFEST_URL.trimEnd('/').lowercase()
        return normalized == mediaFusion ||
            normalized == mediaFusion.removeSuffix("/manifest.json") ||
            (normalized.contains("mediafusionfortheweebs.midnightignite.me") &&
                !normalized.contains("meteor", ignoreCase = true))
    }

    /**
     * Formerly used to strip MediaFusion from auto-pick. Always false — it is a real pack
     * scraper like Torrentio/Meteor, not a separate "PlayFlix" source.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isPlayFlixStreamAddon(addonId: String, addonName: String): Boolean = false

    /** No optional community packs in the locked-down default set. */
    @Suppress("UNUSED_PARAMETER")
    fun isOptionalCommunityManifest(url: String): Boolean = false

    fun addonDisplayName(addon: ManagedAddon): String = addon.manifest.name

    fun addonDisplayVersion(addon: ManagedAddon): String? =
        addon.manifest.version?.takeIf { it.isNotBlank() }

    fun addonDisplayDescription(addon: ManagedAddon): String =
        addon.manifest.description?.takeIf { it.isNotBlank() } ?: "Stremio-compatible addon"

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
     * Stream scrapers / torrent providers that should stay out of Addons Settings lists
     * (same bucket as Torrentio / Meteor / MediaFusion / AIO).
     */
    fun isStreamScraperManifest(url: String): Boolean {
        if (isWatchHubManifest(url)) return false
        val normalized = url.lowercase()
        return "torrentio" in normalized ||
            "comet" in normalized ||
            "meteor" in normalized ||
            isAioStreamsManifest(url) ||
            "mediafusion" in normalized ||
            isPlayFlixManifest(url)
    }

    /** Catalog / meta / subtitle infrastructure; scrapers are hidden. */
    fun isUserFacingSettingsAddon(addon: ManagedAddon): Boolean {
        if (isStreamScraperManifest(addon.manifestUrl)) return false
        if (isInfrastructureManifest(addon.manifestUrl)) return true
        val resources = addon.manifest.resources
        return AddonResource.CATALOG in resources ||
            AddonResource.META in resources ||
            AddonResource.SUBTITLES in resources
    }

    /**
     * Manifests to fetch for a provision pass.
     * Without debrid, skip torrent content sources so Play falls through to WatchHub cleanly.
     */
    fun provisionManifestUrls(debridConfigured: Boolean): List<String> =
        if (debridConfigured) bootstrapManifestUrls else infrastructureManifestUrls

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
