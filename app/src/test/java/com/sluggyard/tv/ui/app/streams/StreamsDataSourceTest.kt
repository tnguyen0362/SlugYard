package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.streamresolution.CacheCheckResult
import com.sluggyard.tv.core.streamresolution.DebridProactiveCacheChecker
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StreamsDataSourceTest {

    @Test
    fun `one failing addon does not erase direct or torrent streams from a sibling`() = runTest {
        val source = StreamsDataSource(
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

        val good = final.first { it.addonId == "good" }.state as StreamGroupState.Content
        assertTrue(good.streams.any { it.directUrl == "https://play" })
        assertTrue(good.streams.any { it.infoHash == "abcdef" })
        assertTrue(final.first { it.addonId == "bad" }.state is StreamGroupState.Error)
    }

    @Test
    fun `failure state keeps an actionable transport category`() = runTest {
        val source = StreamsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("bad"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = AddonTransportResult.HttpFailure(400, "bad request")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
        )

        val state = source.streamGroups("series", "id", null).toList().last().single().state as StreamGroupState.Error

        assertEquals("bad: source responded with HTTP 400", state.message)
    }

    @Test
    fun `duplicate torrent hashes across addons share one cache probe`() = runTest {
        val checks = AtomicInteger(0)
        val checker = object : DebridProactiveCacheChecker {
            override val service = DebridService.TORBOX
            override suspend fun check(infoHashes: Set<String>): Map<String, CacheCheckResult> {
                checks.incrementAndGet()
                return infoHashes.associateWith { CacheCheckResult.Definitive(true) }
            }
        }
        val source = StreamsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("one"), addon("two"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = AddonTransportResult.Success(
                    Json.parseToJsonElement("""{"streams":[{"name":"Torrent","infoHash":"abcdef"}]}""").jsonObject,
                )
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
            proactiveCacheCheckers = setOf(checker),
        )

        source.streamGroups("movie", "id", DebridService.TORBOX).toList()

        assertEquals(1, checks.get())
    }

    private fun addon(id: String) = ManagedAddon(
        manifestUrl = "https://$id.test/manifest.json",
        manifest = AddonManifestContract(id = id, name = id, resources = setOf(AddonResource.STREAM)),
    )
}
