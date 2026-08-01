package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaFusionConfigTransportTest {
    @Test
    fun configuredManifestUsesTheReturnedEncryptedPath() {
        val payload = buildJsonObject { put("encrypted_str", "encrypted-config") }

        assertEquals(
            "https://mediafusion.example/encrypted-config/manifest.json",
            mediaFusionManifestUrl("https://mediafusion.example", decodeMediaFusionEncryptedPath(payload)),
        )
    }

    @Test
    fun providerPayloadUsesMediaFusionProviderNamesAndToken() {
        val payload = mediaFusionProviderPayload(DebridService.PREMIUMIZE, "premiumize-key")

        assertEquals("premiumize", payload["service"]?.toString()?.trim('"'))
        assertEquals("premiumize-key", payload["token"]?.toString()?.trim('"'))
    }

    @Test
    fun malformedMediaFusionResponseIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            decodeMediaFusionEncryptedPath(Json.parseToJsonElement("{}").jsonObject)
        }
    }

    @Test
    fun encryptWithoutConfiguredBaseUrlIsRejected() {
        val transport = MediaFusionConfigTransport(encryptBaseUrl = "")
        assertFailsWith<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking {
                transport.createManifestUrl(DebridService.TORBOX, "torbox-key")
            }
        }
    }
}
