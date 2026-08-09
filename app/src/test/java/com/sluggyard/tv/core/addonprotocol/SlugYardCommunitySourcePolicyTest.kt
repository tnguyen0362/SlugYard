package com.sluggyard.tv.core.addonprotocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlugYardCommunitySourcePolicyTest {
    @Test
    fun `includes vanilla catalog and watchhub alongside stream sources`() {
        val urls = SlugYardCommunitySourcePolicy.bootstrapManifestUrls
        assertTrue(urls.any { it.contains("cinemeta", ignoreCase = true) })
        assertTrue(urls.any { it.contains("watchhub", ignoreCase = true) })
        assertTrue(urls.any { it.contains("torrentio", ignoreCase = true) })
        assertTrue(urls.any { it.contains("mediafusion", ignoreCase = true) })
        assertFalse(urls.any { it.contains("comet", ignoreCase = true) })
        assertTrue(urls.any { it.contains("meteor", ignoreCase = true) })
    }

    @Test
    fun `never embeds a customer debrid credential in a backend manifest URL`() {
        assertTrue(SlugYardCommunitySourcePolicy.bootstrapManifestUrls.isNotEmpty())
        assertFalse(SlugYardCommunitySourcePolicy.bootstrapManifestUrls.any { it.contains("apikey=", ignoreCase = true) })
        assertFalse(
            SlugYardCommunitySourcePolicy.bootstrapManifestUrls.any {
                it.contains("/D-", ignoreCase = false) || it.contains("encrypted", ignoreCase = true)
            },
        )
    }

    @Test
    fun `without debrid provisions infrastructure and watchhub only`() {
        val urls = SlugYardCommunitySourcePolicy.provisionManifestUrls(debridConfigured = false)
        assertTrue(urls.any { it.contains("watchhub", ignoreCase = true) })
        assertTrue(urls.any { it.contains("cinemeta", ignoreCase = true) })
        assertFalse(urls.any { it.contains("torrentio", ignoreCase = true) })
        assertFalse(urls.any { it.contains("mediafusion", ignoreCase = true) })
        assertFalse(urls.any { it.contains("comet", ignoreCase = true) })
        assertFalse(urls.any { it.contains("meteor", ignoreCase = true) })
        assertFalse(urls.any { SlugYardCommunitySourcePolicy.isAioStreamsManifest(it) })
    }

    @Test
    fun `with debrid provisions full bootstrap including MediaFusion`() {
        val urls = SlugYardCommunitySourcePolicy.provisionManifestUrls(debridConfigured = true)
        assertEquals(SlugYardCommunitySourcePolicy.bootstrapManifestUrls, urls)
        assertTrue(urls.any { it.contains("mediafusion", ignoreCase = true) })
        assertFalse(urls.any { it.contains("comet", ignoreCase = true) })
        assertTrue(SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL in urls)
    }

    @Test
    fun `scrapers keep technical names - product pack is PlayFlix`() {
        val torrentio = ManagedAddon(
            manifestUrl = "https://torrentio.strem.fun/manifest.json",
            manifest = AddonManifestContract(
                id = "torrentio",
                name = "Torrentio",
                resources = setOf(AddonResource.STREAM),
            ),
        )
        val mediaFusion = ManagedAddon(
            manifestUrl = SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL,
            manifest = AddonManifestContract(
                id = "mediafusion",
                name = "MediaFusion",
                version = "6.1.1",
            ),
        )
        // Registry still stores upstream names; Settings never surfaces them individually.
        assertEquals("Torrentio", SlugYardCommunitySourcePolicy.addonDisplayName(torrentio))
        assertEquals("MediaFusion", SlugYardCommunitySourcePolicy.addonDisplayName(mediaFusion))
        assertEquals("PlayFlix", SlugYardCommunitySourcePolicy.PLAYFLIX_DISPLAY_NAME)
        assertTrue(SlugYardCommunitySourcePolicy.isPlayFlixPackIdentifier(SlugYardCommunitySourcePolicy.PLAYFLIX_PACK_ID))
    }

    @Test
    fun `torrentio is ready only when enabled configured and stream capable`() {
        val torrentio = ManagedAddon(
            manifestUrl = "https://torrentio.strem.fun/manifest.json",
            configuredManifestUrl = "https://torrentio.strem.fun/debridoptions=nodownloadlinks|torbox=redacted/manifest.json",
            manifest = AddonManifestContract(
                id = "com.stremio.torrentio.addon",
                name = "Torrentio",
                resources = setOf(AddonResource.STREAM),
            ),
        )

        assertTrue(SlugYardCommunitySourcePolicy.isTorrentioReady(listOf(torrentio)))
        assertFalse(SlugYardCommunitySourcePolicy.isTorrentioReady(listOf(torrentio.copy(enabled = false))))
        assertFalse(SlugYardCommunitySourcePolicy.isTorrentioReady(listOf(torrentio.copy(configuredManifestUrl = null))))
        assertFalse(
            SlugYardCommunitySourcePolicy.isTorrentioReady(
                listOf(torrentio.copy(manifest = torrentio.manifest.copy(resources = emptySet()))),
            ),
        )
    }

    @Test
    fun `settings hides individual scrapers - PlayFlix pack covers all of them`() {
        val cinemeta = ManagedAddon(
            manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
            manifest = AddonManifestContract(
                id = "cinemeta",
                name = "Cinemeta",
                resources = setOf(AddonResource.META, AddonResource.CATALOG),
            ),
        )
        val torrentio = ManagedAddon(
            manifestUrl = "https://torrentio.strem.fun/manifest.json",
            manifest = AddonManifestContract(
                id = "torrentio",
                name = "Torrentio",
                resources = setOf(AddonResource.STREAM),
            ),
        )
        val meteor = ManagedAddon(
            manifestUrl = "https://meteorfortheweebs.midnightignite.me/manifest.json",
            manifest = AddonManifestContract(
                id = "meteor",
                name = "Meteor",
                resources = setOf(AddonResource.STREAM),
            ),
        )
        val mediaFusion = ManagedAddon(
            manifestUrl = SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL,
            manifest = AddonManifestContract(
                id = "mediafusion",
                name = "MediaFusion",
                resources = setOf(AddonResource.STREAM, AddonResource.CATALOG),
            ),
        )

        assertTrue(SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(cinemeta))
        assertFalse(SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(mediaFusion))
        assertFalse(SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(torrentio))
        assertFalse(SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(meteor))
        assertTrue(SlugYardCommunitySourcePolicy.isStreamScraperManifest(torrentio.manifestUrl))
        assertTrue(SlugYardCommunitySourcePolicy.isStreamScraperManifest(mediaFusion.manifestUrl))
        assertTrue(SlugYardCommunitySourcePolicy.isStreamScraperManifest(meteor.manifestUrl))
        assertTrue(SlugYardCommunitySourcePolicy.isPlayFlixPackInstalled(listOf(torrentio, meteor, mediaFusion)))
        assertTrue(SlugYardCommunitySourcePolicy.isPlayFlixInstalled(listOf(mediaFusion)))
        assertFalse(SlugYardCommunitySourcePolicy.isPlayFlixPackInstalled(listOf(cinemeta)))
        assertTrue(SlugYardCommunitySourcePolicy.optionalCommunityManifestUrls.isEmpty())
    }

    @Test
    fun `play without debrid routes to WatchHub`() {
        assertTrue(SlugYardCommunitySourcePolicy.shouldRoutePlayToWatchHub(hasActiveDebrid = false))
        assertFalse(SlugYardCommunitySourcePolicy.shouldRoutePlayToWatchHub(hasActiveDebrid = true))
    }

    @Test
    fun `community pack installed detects allowlisted manifests`() {
        val installed = listOf(
            ManagedAddon(
                manifestUrl = SlugYardCommunitySourcePolicy.watchHubManifestUrl,
                manifest = AddonManifestContract(id = "watchhub", name = "WatchHub"),
            ),
        )
        assertTrue(SlugYardCommunitySourcePolicy.isCommunityPackInstalled(installed))
        assertTrue(SlugYardCommunitySourcePolicy.isWatchHubInstalled(installed))
        assertFalse(SlugYardCommunitySourcePolicy.isCommunityPackInstalled(emptyList()))
    }
}
