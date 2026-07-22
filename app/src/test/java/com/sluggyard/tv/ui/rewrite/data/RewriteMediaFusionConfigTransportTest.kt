package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RewriteMediaFusionConfigTransportTest {
    @Test
    fun configuredManifestUsesTheReturnedEncryptedPath() {
        val payload = buildJsonObject { put("encrypted_str", "encrypted-config") }

        assertEquals(
            "https://mediafusionfortheweebs.midnightignite.me/encrypted-config/manifest.json",
            mediaFusionManifestUrl(decodeMediaFusionEncryptedPath(payload)),
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
}
