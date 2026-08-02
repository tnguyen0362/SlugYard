package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryAction
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityAddonProvisionerTest {
    @Test
    fun `provisions only fixed curated endpoints with debrid`() = runTest {
        val registry = FakeRegistry()
        val gateway = FakeGateway()

        val report = CommunityAddonProvisioner(gateway, registry).provision(
            configured = ConfiguredAddonUrls(
                torrentioManifestUrl = "https://torrentio.strem.fun/debridoptions=nodownloadlinks%7Ctorbox=key/manifest.json",
                mediaFusionManifestUrl = "https://mediafusion.example/configured/manifest.json",
                cometManifestUrl = "https://comet.elfhosted.com/configured/manifest.json",
                meteorManifestUrl = "https://meteorfortheweebs.midnightignite.me/configured/manifest.json",
                aioStreamsManifestUrl = SlugYardCommunitySourcePolicy.aioStreamsRegistryUrl?.let {
                    it.removeSuffix("/manifest.json") + "/uuid/enc/manifest.json"
                },
            ),
            debridConfigured = true,
        )

        assertEquals(SlugYardCommunitySourcePolicy.provisionManifestUrls(true).size, gateway.requestedUrls.size)
        assertTrue(gateway.requestedUrls.none { it.contains("example.test") })
        assertTrue(gateway.requestedUrls.contains("https://torrentio.strem.fun/debridoptions=nodownloadlinks%7Ctorbox=key/manifest.json"))
        assertTrue(gateway.requestedUrls.contains("https://meteorfortheweebs.midnightignite.me/configured/manifest.json"))
        assertTrue(gateway.requestedUrls.none { it.contains("comet", ignoreCase = true) })
        assertTrue(gateway.requestedUrls.none { SlugYardCommunitySourcePolicy.isPlayFlixManifest(it) })
        if (SlugYardCommunitySourcePolicy.aioStreamsRegistryUrl != null) {
            assertTrue(gateway.requestedUrls.any { it.contains("/uuid/enc/manifest.json") })
        }
        assertEquals(SlugYardCommunitySourcePolicy.provisionManifestUrls(true).size, registry.curated.single().size)
        assertEquals(SlugYardCommunitySourcePolicy.provisionManifestUrls(true).size, report.installedCount)
        assertEquals(0, report.unavailableCount)
    }

    @Test
    fun `stale debrid selection without generated urls skips torrent sources`() = runTest {
        val registry = FakeRegistry()
        val gateway = FakeGateway()

        val report = CommunityAddonProvisioner(gateway, registry).provision(
            configured = null,
            debridConfigured = true,
        )

        assertEquals(SlugYardCommunitySourcePolicy.infrastructureManifestUrls.size, gateway.requestedUrls.size)
        assertTrue(gateway.requestedUrls.none { it.contains("torrentio", ignoreCase = true) })
        assertEquals(SlugYardCommunitySourcePolicy.infrastructureManifestUrls.size, report.installedCount)
    }

    @Test
    fun `without debrid skips torrent content sources`() = runTest {
        val registry = FakeRegistry()
        val gateway = FakeGateway()

        val report = CommunityAddonProvisioner(gateway, registry).provision(
            configured = null,
            debridConfigured = false,
        )

        assertEquals(SlugYardCommunitySourcePolicy.infrastructureManifestUrls.size, gateway.requestedUrls.size)
        assertTrue(gateway.requestedUrls.none { it.contains("torrentio", ignoreCase = true) })
        assertTrue(gateway.requestedUrls.any { it.contains("watchhub", ignoreCase = true) })
        assertEquals(SlugYardCommunitySourcePolicy.infrastructureManifestUrls.size, report.installedCount)
        assertEquals(
            SlugYardCommunitySourcePolicy.infrastructureManifestUrls.toSet(),
            registry.lastRetainUrls,
        )
    }

    @Test
    fun `continues provisioning when a curated source is unavailable`() = runTest {
        val registry = FakeRegistry()
        val gateway = FakeGateway(failingRequestIndex = 1)

        val report = CommunityAddonProvisioner(gateway, registry).provision(
            configured = ConfiguredAddonUrls(
                torrentioManifestUrl = "https://torrentio.strem.fun/debridoptions=nodownloadlinks%7Ctorbox=key/manifest.json",
                mediaFusionManifestUrl = "https://mediafusion.example/configured/manifest.json",
                cometManifestUrl = "https://comet.elfhosted.com/configured/manifest.json",
                meteorManifestUrl = "https://meteorfortheweebs.midnightignite.me/configured/manifest.json",
                aioStreamsManifestUrl = SlugYardCommunitySourcePolicy.aioStreamsRegistryUrl?.let {
                    it.removeSuffix("/manifest.json") + "/uuid/enc/manifest.json"
                },
            ),
            debridConfigured = true,
        )

        assertEquals(SlugYardCommunitySourcePolicy.provisionManifestUrls(true).size - 1, registry.curated.single().size)
        assertEquals(SlugYardCommunitySourcePolicy.provisionManifestUrls(true).size - 1, report.installedCount)
        assertEquals(1, report.unavailableCount)
        assertTrue(report.unavailableReasons.any { "HTTP 503" in it })
    }

    @Test
    fun `uninstall clears curated registry`() = runTest {
        val registry = FakeRegistry()
        val gateway = FakeGateway()
        val provisioner = CommunityAddonProvisioner(gateway, registry)
        provisioner.provision(debridConfigured = true)

        val report = provisioner.uninstall()

        assertEquals(0, report.installedCount)
        assertTrue(registry.cleared)
    }

    private class FakeGateway(
        private val failingRequestIndex: Int? = null,
    ) : StremioAddonGateway {
        val requestedUrls = mutableListOf<String>()

        override suspend fun fetchManifest(manifestUrl: String): AddonTransportResult<AddonManifestContract> {
            requestedUrls += manifestUrl
            return if (requestedUrls.lastIndex == failingRequestIndex) {
                AddonTransportResult.HttpFailure(503, "unavailable")
            } else {
                AddonTransportResult.Success(
                    AddonManifestContract(id = "addon-${requestedUrls.lastIndex}", name = "Curated"),
                )
            }
        }

        override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>): AddonTransportResult<JsonObject> = error("unused")
        override suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> = error("unused")
        override suspend fun fetchStreams(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> = error("unused")
        override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> = error("unused")
    }

    private class FakeRegistry : AddonRegistry {
        override val state: Flow<AddonRegistryState> = MutableStateFlow(AddonRegistryState())
        val curated = mutableListOf<List<ManagedAddon>>()
        var cleared = false
        var lastRetainUrls: Set<String> = emptySet()
        var playFlixDefaultOffEnsured = false

        override suspend fun dispatch(action: AddonRegistryAction) = Unit
        override suspend fun ensureCurated(
            addons: List<ManagedAddon>,
            retainManifestUrls: Set<String>,
        ) {
            curated += addons
            lastRetainUrls = retainManifestUrls
        }

        override suspend fun clearCurated() {
            cleared = true
            curated.clear()
        }

        override suspend fun ensurePlayFlixNotDefaultInstalled() {
            playFlixDefaultOffEnsured = true
        }

        override suspend fun bindConfiguredManifestUrl(
            manifestUrl: String,
            configuredManifestUrl: String?,
        ) = Unit
    }
}
