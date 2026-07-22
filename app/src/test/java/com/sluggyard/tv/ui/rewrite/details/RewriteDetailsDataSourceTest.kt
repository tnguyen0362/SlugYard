package com.sluggyard.tv.ui.rewrite.details

import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteDetailsDataSourceTest {
    @Test
    fun `origin addon metadata maps to sorted rewritten season state`() = runTest {
        val source = RewriteDetailsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("source"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> =
                    AddonTransportResult.Success(Json.parseToJsonElement("""{"meta":{"id":"show","type":"series","name":"Show","cast":["Actor One","Actor Two"],"videos":[{"id":"e2","season":1,"episode":2,"title":"Second"},{"id":"e1","season":1,"episode":1,"title":"First"}]}}""").jsonObject)
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
        )

        val result = source.load("source", "series", "show") as RewriteDetailsLoadResult.Ready

        assertEquals("Show", result.state.title)
        assertEquals(listOf("Actor One", "Actor Two"), result.state.cast)
        assertEquals(listOf("First", "Second"), result.state.seasons.single().episodes.map(RewriteDetailsEpisode::title))
    }

    @Test
    fun `disabled or missing origin cannot be silently replaced by another addon`() = runTest {
        val source = RewriteDetailsDataSource(
            registrySnapshot = { AddonRegistryState(emptyList()) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("gateway should not be called")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("gateway should not be called")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("gateway should not be called")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("gateway should not be called")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("gateway should not be called")
            },
        )

        assertTrue(source.load("missing", "movie", "id") is RewriteDetailsLoadResult.Unavailable)
    }

    private fun addon(id: String) = ManagedAddon(
        manifestUrl = "https://$id.test/manifest.json",
        manifest = AddonManifestContract(id = id, name = id, resources = setOf(AddonResource.META)),
    )
}
