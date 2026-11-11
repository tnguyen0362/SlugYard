package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioEndpointBuilder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for "OpenSubtitles are always terrible": the addon answers with ISO 639-2 codes
 * ("eng") that the strict "en" filter discarded, and the file hash/size/filename never reached
 * the addon, so rows were generic for the IMDB id instead of matched to this release.
 */
class SubtitleMatchingTest {
    private val twoLetterAndThreeLetter = """
        {"subtitles":[
          {"id":"1","lang":"eng","url":"https://s/1"},
          {"id":"2","lang":"spa","url":"https://s/2"},
          {"id":"3","lang":"en","url":"https://s/3"}
        ]}
    """.trimIndent()

    @Test
    fun `preferred language en keeps eng rows and drops other languages`() = runTest {
        val source = dataSource(twoLetterAndThreeLetter) { _, _, _, _, _ -> }
        val final = source.subtitleGroups("movie", "tt1", langs = listOf("en")).toList().last()
        val tracks = (final.single().state as SubtitleGroupState.Content).tracks
        assertEquals(setOf("1", "3"), tracks.map { it.id }.toSet())
    }

    @Test
    fun `video hash size and filename are sent to the addon as extras`() = runTest {
        var seen: Map<String, String>? = null
        val source = dataSource(twoLetterAndThreeLetter) { _, _, _, _, extras -> seen = extras }
        source.subtitleGroups(
            "movie", "tt1",
            video = SubtitleVideoInfo(hash = "8e245d9679d31e12", sizeBytes = 3_966_480_091, filename = "Twilight 1 (2008).mkv"),
        ).toList()
        assertEquals("8e245d9679d31e12", seen?.get("videoHash"))
        assertEquals("3966480091", seen?.get("videoSize"))
        assertEquals("Twilight 1 (2008).mkv", seen?.get("filename"))
    }

    @Test
    fun `no video info sends no extras`() = runTest {
        var seen: Map<String, String>? = null
        val source = dataSource(twoLetterAndThreeLetter) { _, _, _, _, extras -> seen = extras }
        source.subtitleGroups("movie", "tt1").toList()
        assertEquals(emptyMap<String, String>(), seen)
    }

    @Test
    fun `subtitle url carries extras in the path segment before json`() {
        val url = StremioEndpointBuilder.subtitles(
            manifestUrl = "https://os.test/manifest.json",
            type = "movie",
            id = "tt1099212",
            langs = null,
            extras = linkedMapOf(
                "videoHash" to "8e245d9679d31e12",
                "videoSize" to "3966480091",
                "filename" to "Twilight 1 & (2008)/x.mkv",
            ),
        )
        assertEquals(
            "https://os.test/subtitles/movie/tt1099212/videoHash=8e245d9679d31e12&videoSize=3966480091&" +
                "filename=Twilight%201%20%20%20(2008)%20x.mkv.json",
            url.replace("%28", "(").replace("%29", ")"),
        )
    }

    @Test
    fun `subtitle url without extras is unchanged`() {
        assertEquals(
            "https://os.test/subtitles/movie/tt1099212.json",
            StremioEndpointBuilder.subtitles("https://os.test/manifest.json", "movie", "tt1099212"),
        )
        assertTrue(
            StremioEndpointBuilder.subtitles("https://os.test/manifest.json", "movie", "tt1", "en")
                .endsWith("?langs=en"),
        )
    }

    private fun dataSource(
        body: String,
        onFetch: (String, String, String, String?, Map<String, String>) -> Unit,
    ) = SubtitleDataSource(
        registrySnapshot = {
            AddonRegistryState(
                listOf(
                    ManagedAddon(
                        manifestUrl = "https://os.test/manifest.json",
                        manifest = AddonManifestContract(id = "os", name = "os", resources = setOf(AddonResource.SUBTITLES)),
                    ),
                ),
            )
        },
        gateway = object : StremioAddonGateway {
            override suspend fun fetchManifest(manifestUrl: String) = error("unused")
            override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
            override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("unused")
            override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
            override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String, langs: String?): AddonTransportResult<JsonObject> =
                error("extras overload should be used")

            override suspend fun fetchSubtitles(
                manifestUrl: String,
                type: String,
                id: String,
                langs: String?,
                extras: Map<String, String>,
            ): AddonTransportResult<JsonObject> {
                onFetch(manifestUrl, type, id, langs, extras)
                return AddonTransportResult.Success(Json.parseToJsonElement(body).jsonObject)
            }
        },
    )
}
