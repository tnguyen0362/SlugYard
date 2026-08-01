package com.sluggyard.tv.core.addonprotocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioAddonTransportTest {

    @Test
    fun `endpoint builder derives catalog path deterministically and orders extras`() {
        val url = StremioEndpointBuilder.catalog(
            manifestUrl = "https://catalog.example/addon/manifest.json",
            type = "series",
            catalogId = "popular",
            extras = mapOf("skip" to "20", "genre" to "sci fi"),
        )

        assertEquals(
            "https://catalog.example/addon/catalog/series/popular/genre=sci%20fi,skip=20.json",
            url,
        )
    }

    @Test
    fun `endpoint builder preserves manifest configuration and encodes utf8 ids`() {
        val url = StremioEndpointBuilder.meta(
            manifestUrl = "https://catalog.example/addon/manifest.json?config=na%C3%AFve",
            type = "series",
            id = "tt-你好/1",
        )

        assertEquals(
            "https://catalog.example/addon/meta/series/tt-%E4%BD%A0%E5%A5%BD%2F1.json?config=na%C3%AFve",
            url,
        )
    }

    @Test
    fun `decoder accepts supported resource forms and optional fields`() {
        val payload = Json.parseToJsonElement(
            """{"id":"example","name":"Example","resources":["catalog",{"name":"stream"}],"types":["movie"],"catalogs":[{"id":"top","type":"movie","name":"Top","extra":[{"name":"search","isRequired":true}]}],"behaviorHints":{"configurable":true}}""",
        ).jsonObject

        val result = StremioManifestDecoder.decode(payload) as AddonTransportResult.Success

        assertEquals(setOf(AddonResource.CATALOG, AddonResource.STREAM), result.value.resources)
        assertTrue(result.value.behaviorHints.configurable)
        assertTrue(result.value.catalogs.single().extras.single().required)
    }

    @Test
    fun `decoder rejects a manifest without the required identity`() {
        val result = StremioManifestDecoder.decode(Json.parseToJsonElement("""{"name":"No id"}""").jsonObject)

        assertTrue(result is AddonTransportResult.MalformedResponse)
    }
}
