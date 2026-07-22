package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteStreamsDataSourceTest {

    @Test
    fun `one failing addon does not erase direct or torrent streams from a sibling`() = runTest {
        val source = RewriteStreamsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("good"), addon("bad"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> =
                    if (manifestUrl.contains("bad")) AddonTransportResult.HttpFailure(500, "failure") else {
                        AddonTransportResult.Success(
                            Json.parseToJsonElement("""{"streams":[{"name":"Direct","url":"https://play"},{"name":"Torrent","infoHash":"abcdef"}]}""").jsonObject,
                        )
                    }
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
        )

        val final = source.streamGroups("movie", "id", DebridService.TORBOX).toList().last()

        val good = final.first { it.addonId == "good" }.state as RewriteStreamGroupState.Content
        assertTrue(good.streams.any { it.directUrl == "https://play" })
        assertTrue(good.streams.any { it.infoHash == "abcdef" })
        assertTrue(final.first { it.addonId == "bad" }.state is RewriteStreamGroupState.Error)
    }

    @Test
    fun `failure state keeps an actionable transport category`() = runTest {
        val source = RewriteStreamsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("bad"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = AddonTransportResult.HttpFailure(400, "bad request")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
        )

        val state = source.streamGroups("series", "id", null).toList().last().single().state as RewriteStreamGroupState.Error

        assertEquals("bad: source responded with HTTP 400", state.message)
    }

    private fun addon(id: String) = ManagedAddon(
        manifestUrl = "https://$id.test/manifest.json",
        manifest = AddonManifestContract(id = id, name = id, resources = setOf(AddonResource.STREAM)),
    )
}
