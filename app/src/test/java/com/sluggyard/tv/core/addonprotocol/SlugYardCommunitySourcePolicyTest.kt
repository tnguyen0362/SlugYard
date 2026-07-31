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
        assertTrue(urls.any { it.contains("comet", ignoreCase = true) })
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
    }

    @Test
    fun `with debrid provisions full bootstrap allowlist`() {
        val urls = SlugYardCommunitySourcePolicy.provisionManifestUrls(debridConfigured = true)
        assertEquals(SlugYardCommunitySourcePolicy.bootstrapManifestUrls, urls)
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
