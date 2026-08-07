package com.sluggyard.tv.core.addon

import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.domain.model.DebridSettings

/**
 * Catalogue of addon endpoints shipped with SlugYard, plus helpers to resolve the
 * default addon set for a given debrid configuration and to classify an addon URL
 * as infrastructure (metadata/subtitles) or content source.
 */
object SlugYardBundledAddons {

    val INFRASTRUCTURE_ADDONS: List<String> = listOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io",
        "https://watchhub.strem.io",
    )

    val CONTENT_SOURCE_BASE_URLS: List<String> = listOf(
        "https://torrentio.strem.fun",
        "https://mediafusionfortheweebs.midnightignite.me",
    )

    /** @see SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL */
    const val SLUGYARD_COMMUNITY_ADDON_MANIFEST =
        SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL

    fun getDefaultAddons(debridSettings: DebridSettings? = null): List<String> =
        INFRASTRUCTURE_ADDONS.toMutableList().apply {
            if (debridSettings?.hasAnyApiKey == true) {
                addAll(buildConfiguredContentSourceUrls(debridSettings))
            }
        }

    /** Default debrid scrapers (Torrentio + MediaFusion base URLs; Meteor/AIO via provision). */
    fun buildConfiguredContentSourceUrls(debridSettings: DebridSettings): List<String> =
        SlugYardCommunitySourcePolicy.bootstrapManifestUrls
            .filter { manifestUrl ->
                CONTENT_SOURCE_BASE_URLS.any(manifestUrl::startsWith)
            }

    fun isDefaultAddon(url: String): Boolean =
        INFRASTRUCTURE_ADDONS.any { base -> normalize(url).startsWith(base.lowercase()) }

    fun isContentSourceAddon(url: String): Boolean =
        CONTENT_SOURCE_BASE_URLS.any { base -> normalize(url).startsWith(base.lowercase()) }

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()
}