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
        assertFalse(urls.any(SlugYardCommunitySourcePolicy::isOptionalCommunityManifest))
    }

    @Test
    fun `with debrid provisions bootstrap allowlist except optional PlayFlix`() {
        val urls = SlugYardCommunitySourcePolicy.provisionManifestUrls(debridConfigured = true)
        assertEquals(
            SlugYardCommunitySourcePolicy.bootstrapManifestUrls.filterNot(
                SlugYardCommunitySourcePolicy::isOptionalCommunityManifest,
            ),
            urls,
        )
        assertFalse(urls.any(SlugYardCommunitySourcePolicy::isPlayFlixManifest))
        assertFalse(urls.any { it.contains("comet", ignoreCase = true) })
        assertTrue(SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL in SlugYardCommunitySourcePolicy.bootstrapManifestUrls)
    }

    @Test
    fun `playflix display name overrides mediafusion branding`() {
        val addon = ManagedAddon(
            manifestUrl = SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL,
            manifest = AddonManifestContract(
                id = "mediafusion",
                name = "MediaFusion",
                version = "6.1.1",
                description = "MediaFusion — universal torrent addon",
            ),
        )
        assertEquals(SlugYardCommunitySourcePolicy.PLAYFLIX_DISPLAY_NAME, SlugYardCommunitySourcePolicy.addonDisplayName(addon))
        assertEquals(SlugYardCommunitySourcePolicy.PLAYFLIX_DISPLAY_VERSION, (SlugYardCommunitySourcePolicy.addonDisplayVersion(addon)))
        assertEquals(SlugYardCommunitySourcePolicy.PLAYFLIX_DISPLAY_DESCRIPTION, SlugYardCommunitySourcePolicy.addonDisplayDescription(addon))
        assertEquals("1.1.0", SlugYardCommunitySourcePolicy.PLAYFLIX_DISPLAY_VERSION)
        assertEquals("Third-party scrape", SlugYardCommunitySourcePolicy.PLAYFLIX_DISPLAY_DESCRIPTION)
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
    fun `settings addon list hides scrapers but keeps playflix and infrastructure`() {
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
        val playflix = ManagedAddon(
            manifestUrl = SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL,
            manifest = AddonManifestContract(
                id = "mediafusion",
                name = "MediaFusion",
                resources = setOf(AddonResource.STREAM, AddonResource.CATALOG),
            ),
        )

        assertTrue(SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(cinemeta))
        assertTrue(SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(playflix))
        assertFalse(SlugYardCommunitySourcePolicy.isUserFacingSettingsAddon(torrentio))
        assertTrue(SlugYardCommunitySourcePolicy.isStreamScraperManifest(torrentio.manifestUrl))
        assertFalse(SlugYardCommunitySourcePolicy.isStreamScraperManifest(playflix.manifestUrl))
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
