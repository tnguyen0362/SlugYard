package com.sluggyard.tv.core.addonprotocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioMetadataDecoderTest {

    @Test
    fun `decodes wrapped title metadata and valid episodes`() {
        val payload = Json.parseToJsonElement(
            """{"meta":{"id":"show","type":"series","name":"Show","genres":["Drama"],"cast":["Actor"],"videos":[{"id":"show:1:1","season":1,"episode":1,"title":"Pilot","released":"2026-01-01"},{"id":"broken","title":"Missing numbering"}]}}""",
        ).jsonObject

        val result = StremioMetadataDecoder.decode(payload) as AddonTransportResult.Success

        assertEquals("Show", result.value.title)
        assertEquals(listOf("Drama"), result.value.genres)
        assertEquals(2, result.value.episodes.size)
        assertEquals(1, result.value.episodes.first().season)
    }

    @Test
    fun `rejects metadata without a complete identity`() {
        val payload = Json.parseToJsonElement("""{"meta":{"id":"x","name":"Broken"}}""").jsonObject

        assertTrue(StremioMetadataDecoder.decode(payload) is AddonTransportResult.MalformedResponse)
    }
}
