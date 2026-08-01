package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDataSourceTest {
    @Test
    fun `subtitle failure remains isolated from a successful sibling`() = runTest {
        val source = SubtitleDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon("good"), addon("bad"))) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> =
                    if (manifestUrl.contains("bad")) AddonTransportResult.NetworkFailure(IllegalStateException()) else {
                        AddonTransportResult.Success(Json.parseToJsonElement("""{"subtitles":[{"lang":"en","url":"https://sub"}]}""").jsonObject)
                    }
            },
        )

        val final = source.subtitleGroups("movie", "id").toList().last()

        assertTrue(final.first { it.addonId == "good" }.state is SubtitleGroupState.Content)
        assertTrue(final.first { it.addonId == "bad" }.state is SubtitleGroupState.Error)
    }

    private fun addon(id: String) = ManagedAddon(
        manifestUrl = "https://$id.test/manifest.json",
        manifest = AddonManifestContract(id = id, name = id, resources = setOf(AddonResource.SUBTITLES)),
    )
}
