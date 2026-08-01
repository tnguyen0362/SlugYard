package com.sluggyard.tv.ui.app.home

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
import org.junit.Test

class HomeDataSourceTest {

    @Test
    fun `only enabled listable catalogs become requests and configured url wins`() = runTest {
        var requestedUrl: String? = null
        val enabled = addon("enabled", enabled = true, configuredUrl = "https://configured.test/manifest.json")
        val disabled = addon("disabled", enabled = false)
        val source = HomeDataSource(
            registrySnapshot = { AddonRegistryState(listOf(enabled, disabled)) },
            gateway = fakeGateway { url ->
                requestedUrl = url
                """{"metas":[{"id":"film","type":"movie","name":"Film","poster":"https://poster"}]}"""
            },
        )

        val request = source.catalogRequests().single()
        val cards = request.load()

        assertEquals("enabled", request.key.addonId)
        assertEquals("https://configured.test/manifest.json", requestedUrl)
        assertEquals("Film", cards.single().title)
    }

    private fun addon(id: String, enabled: Boolean, configuredUrl: String? = null): ManagedAddon = ManagedAddon(
        manifestUrl = "https://$id.test/manifest.json",
        configuredManifestUrl = configuredUrl,
        enabled = enabled,
        manifest = AddonManifestContract(
            id = id,
            name = id,
            resources = setOf(AddonResource.CATALOG),
            catalogs = listOf(com.sluggyard.tv.core.addonprotocol.AddonCatalogDeclaration("home", "movie", "Home")),
        ),
    )

    private fun fakeGateway(catalog: suspend (String) -> String): StremioAddonGateway = object : StremioAddonGateway {
        override suspend fun fetchManifest(manifestUrl: String) = error("unused")
        override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>): AddonTransportResult<JsonObject> =
            AddonTransportResult.Success(Json.parseToJsonElement(catalog(manifestUrl)).jsonObject)
        override suspend fun fetchMeta(manifestUrl: String, type: String, id: String) = error("unused")
        override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
        override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
    }
}
