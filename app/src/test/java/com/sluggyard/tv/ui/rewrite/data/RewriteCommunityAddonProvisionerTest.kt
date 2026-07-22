package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryAction
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteCommunityAddonProvisionerTest {
    @Test
    fun `provisions only fixed curated endpoints`() = runTest {
        val registry = FakeRegistry()
        val gateway = FakeGateway()

        val report = RewriteCommunityAddonProvisioner(gateway, registry).provision()

        assertEquals(4, gateway.requestedUrls.size)
        assertTrue(gateway.requestedUrls.none { it.contains("example.test") })
        assertEquals(4, registry.curated.single().size)
        assertEquals(4, report.installedCount)
        assertEquals(0, report.unavailableCount)
    }

    @Test
    fun `continues provisioning when a curated source is unavailable`() = runTest {
        val registry = FakeRegistry()
        val gateway = FakeGateway(failingRequestIndex = 1)

        val report = RewriteCommunityAddonProvisioner(gateway, registry).provision()

        assertEquals(3, registry.curated.single().size)
        assertEquals(3, report.installedCount)
        assertEquals(1, report.unavailableCount)
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

    private class FakeRegistry : RewriteAddonRegistry {
        override val state: Flow<AddonRegistryState> = MutableStateFlow(AddonRegistryState())
        val curated = mutableListOf<List<ManagedAddon>>()

        override suspend fun dispatch(action: AddonRegistryAction) = Unit
        override suspend fun ensureCurated(addons: List<ManagedAddon>) {
            curated += addons
        }
    }
}
