package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AioStreamsConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun injectsTorboxApiKeyAndDisablesOtherManagedServices() {
        val template = json.parseToJsonElement(
            """
            {
              "metadata": { "id": "slugyard" },
              "config": {
                "excludeUncached": true,
                "services": [
                  { "id": "torbox", "enabled": true, "credentials": {} },
                  { "id": "realdebrid", "enabled": false, "credentials": {} },
                  { "id": "premiumize", "enabled": true, "credentials": { "apiKey": "stale" } },
                  { "id": "easynews", "enabled": false, "credentials": {} }
                ]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val config = injectAioStreamsCredentials(
            aioStreamsConfigFromTemplate(template),
            DebridService.TORBOX,
            "torbox-secret",
        )
        val text = config.toString()
        assertTrue(text.contains("\"apiKey\":\"torbox-secret\""))
        assertTrue(text.contains("\"id\":\"torbox\""))
        assertFalse(text.contains("\"apiKey\":\"stale\""))
        assertTrue(text.contains("\"id\":\"easynews\""))
        assertTrue(text.contains("torbox-secret"))
    }

    @Test
    fun buildsAuthenticatedManifestUrl() {
        assertEquals(
            "https://aiostreams.example/stremio/user-uuid/enc-token/manifest.json",
            aioStreamsManifestUrl("https://aiostreams.example/", "user-uuid", "enc-token"),
        )
    }

    @Test
    fun bootstrapUrlRequiresConfiguredHost() {
        assertNull(aioStreamsBootstrapManifestUrl(""))
        assertEquals(
            "https://aiostreams.example/stremio/manifest.json",
            aioStreamsBootstrapManifestUrl("https://aiostreams.example/"),
        )
    }

    @Test
    fun createResponseRequiresUuidAndEncryptedPassword() {
        val payload = json.parseToJsonElement(
            """{"success":true,"data":{"uuid":"abc","encryptedPassword":"enc"}}""",
        ).jsonObject
        assertEquals("abc" to "enc", decodeAioStreamsCreateResponse(payload))
        assertFailsWith<IllegalArgumentException> {
            decodeAioStreamsCreateResponse(json.parseToJsonElement("""{"success":true,"data":{}}""").jsonObject)
        }
    }

    @Test
    fun injectsOptionalConfigAccessKey() {
        val config = injectAioStreamsCredentials(
            json.parseToJsonElement("""{"services":[]}""").jsonObject,
            DebridService.TORBOX,
            "torbox-secret",
            accessKey = "host-gate-key",
        )
        assertTrue(config.toString().contains("\"accessKey\":\"host-gate-key\""))
    }

    @Test
    fun blankApiKeyIsRejected() {
        val config = json.parseToJsonElement("""{"services":[]}""").jsonObject
        assertFailsWith<IllegalArgumentException> {
            injectAioStreamsCredentials(config, DebridService.TORBOX, " ")
        }
    }
}
