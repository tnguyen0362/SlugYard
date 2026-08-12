package com.sluggyard.tv.ui.app.details

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

class DetailsDataSourceTest {
    @Test
    fun `origin addon metadata maps to sorted rewritten season state`() = runTest {
        val source = DetailsDataSource(
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

        val result = source.load("source", "series", "show") as DetailsLoadResult.Ready

        assertEquals("Show", result.state.title)
        assertEquals(listOf("Actor One", "Actor Two"), result.state.cast)
        assertEquals(listOf("First", "Second"), result.state.seasons.single().episodes.map(DetailsEpisode::title))
    }

    @Test
    fun `season two absolute meta episode numbers show season-local display badges`() = runTest {
        // Anime metas often continue episode 13, 14…; UI must show 1..N within the season.
        // When video id carries :2:9, prefer that over both absolute meta and list index.
        val source = DetailsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("source"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> =
                    AddonTransportResult.Success(
                        Json.parseToJsonElement(
                            """
                            {"meta":{"id":"tt1","type":"series","name":"Show","videos":[
                              {"id":"tt1:1:1","season":1,"episode":1,"title":"S1E1"},
                              {"id":"tt1:1:12","season":1,"episode":12,"title":"S1E12"},
                              {"id":"tt1:2:1","season":2,"episode":13,"title":"S2 opener"},
                              {"id":"tt1:2:9","season":2,"episode":21,"title":"The Angel's Request"}
                            ]}}
                            """.trimIndent(),
                        ).jsonObject,
                    )
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
        )

        val result = source.load("source", "series", "tt1") as DetailsLoadResult.Ready
        val s2 = result.state.seasons.first { it.number == 2 }.episodes
        assertEquals(listOf(13, 21), s2.map { it.number })
        // ids :2:1 and :2:9 win over absolute 13/21
        assertEquals(listOf(1, 9), s2.map { it.displayNumber })
        val ep9 = s2.first { it.id == "tt1:2:9" }
        assertEquals(21, ep9.number)
        assertEquals(9, ep9.displayNumber)
        assertEquals(9, ep9.playbackEpisodeNumber(forSeason = 2))
    }

    @Test
    fun `seasonLocalDisplayNumber prefers id then renumbers absolute packs`() {
        assertEquals(9, seasonLocalDisplayNumber("tt1:2:9", 2, 21, 1, looksSeriesAbsolute = true))
        assertEquals(2, seasonLocalDisplayNumber("loose-id", 2, 21, 1, looksSeriesAbsolute = true))
        assertEquals(4, seasonLocalDisplayNumber("tt1:1:4", 1, 4, 3, looksSeriesAbsolute = false))
        assertTrue(seasonEpisodeLooksSeriesAbsolute(listOf(13, 14, 21)))
        assertTrue(!seasonEpisodeLooksSeriesAbsolute(listOf(1, 2, 9)))
        assertTrue(!seasonEpisodeLooksSeriesAbsolute(listOf(2)))
        assertTrue(!seasonEpisodeLooksSeriesAbsolute(emptyList()))
    }

    @Test
    fun `disabled or missing origin cannot be silently replaced by another addon`() = runTest {
        val source = DetailsDataSource(
            registrySnapshot = { AddonRegistryState(emptyList()) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("gateway should not be called")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("gateway should not be called")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("gateway should not be called")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("gateway should not be called")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("gateway should not be called")
            },
        )

        assertTrue(source.load("missing", "movie", "id") is DetailsLoadResult.Unavailable)
    }

    @Test
    fun `tmdb movie ids resolve to imdb`() = runTest {
        val resolved = resolveMetaContentId(type = "movie", id = "tmdb:1311031") { _, _ ->
            "tt32820897"
        }
        assertEquals("tt32820897", resolved)
    }

    @Test
    fun `tmdb series episode suffix is preserved on imdb id`() = runTest {
        val resolved = resolveMetaContentId(type = "series", id = "tmdb:1396:2:6") { tmdbId, mediaType ->
            assertEquals(1396, tmdbId)
            assertEquals("tv", mediaType)
            "tt0903747"
        }
        assertEquals("tt0903747:2:6", resolved)
    }

    @Test
    fun `tmdb ids are resolved to imdb before meta fetch`() = runTest {
        var requestedId: String? = null
        val source = DetailsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("community.stremio.cinemeta"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> {
                    requestedId = id
                    return AddonTransportResult.Success(
                        Json.parseToJsonElement("""{"meta":{"id":"tt0111161","type":"movie","name":"Shawshank"}}""").jsonObject,
                    )
                }
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
            resolveMetaContentId = { _, id ->
                resolveMetaContentId(type = "movie", id = id) { _, _ -> "tt0111161" }
            },
        )

        val result = source.load("community.stremio.cinemeta", "movie", "tmdb:278") as DetailsLoadResult.Ready

        assertEquals("tt0111161", requestedId)
        assertEquals("Shawshank", result.state.title)
    }

    @Test
    fun `unresolved tmdb ids fail with a clear message`() = runTest {
        val source = DetailsDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("cinemeta"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("should not fetch")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
            resolveMetaContentId = { _, id -> id },
        )

        val result = source.load("cinemeta", "movie", "tmdb:278") as DetailsLoadResult.Unavailable
        assertTrue(result.message.contains("resolve", ignoreCase = true))
    }

    private fun addon(id: String) = ManagedAddon(
        manifestUrl = "https://$id.test/manifest.json",
        manifest = AddonManifestContract(id = id, name = id, resources = setOf(AddonResource.META)),
    )
}
