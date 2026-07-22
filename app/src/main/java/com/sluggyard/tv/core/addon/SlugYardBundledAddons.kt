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
    )

    val CONTENT_SOURCE_BASE_URLS: List<String> = listOf(
        "https://torrentio.strem.fun",
        "https://mediafusionfortheweebs.midnightignite.me",
    )

    const val SLUGYARD_COMMUNITY_ADDON_MANIFEST =
        "https://mediafusionfortheweebs.midnightignite.me/D-JPpZFd2i8K8NFjTEDOFoYYZKfxv0RyIaBXdhVYa9m-ekkXhqkWB4WsahDS15AFA86fjwu7H2tB1wkkRpE-e17SJIhlmj_HX2-sPV0lC_2hg8XDANf_u4AHq1O-VS-C9ViZJWdm8dTGd4nsrFAVm0IjkAC2-M6YGZ2GPbG7nvklg/manifest.json"

    fun getDefaultAddons(debridSettings: DebridSettings? = null): List<String> =
        INFRASTRUCTURE_ADDONS.toMutableList().apply {
            if (debridSettings?.hasAnyApiKey == true) {
                addAll(buildConfiguredContentSourceUrls(debridSettings))
            }
        }

    fun buildConfiguredContentSourceUrls(debridSettings: DebridSettings): List<String> =
        SlugYardCommunitySourcePolicy.bootstrapManifestUrls
            .filter { manifestUrl -> CONTENT_SOURCE_BASE_URLS.any(manifestUrl::startsWith) }

    fun isDefaultAddon(url: String): Boolean =
        INFRASTRUCTURE_ADDONS.any { base -> normalize(url).startsWith(base.lowercase()) }

    fun isContentSourceAddon(url: String): Boolean =
        CONTENT_SOURCE_BASE_URLS.any { base -> normalize(url).startsWith(base.lowercase()) }

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()
}