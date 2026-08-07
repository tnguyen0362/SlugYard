package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderAddonConfigTest {
    @Test
    fun torrentioUsesTheConnectedProviderAndEncodedCredential() {
        assertEquals(
            "https://torrentio.strem.fun/torbox=key%2Fpart%3Done/manifest.json",
            buildTorrentioManifestUrl(DebridService.TORBOX, "key/part=one"),
        )
    }

    @Test
    fun allSupportedProvidersMapToTheirTorrentioConfigKey() {
        assertEquals("realdebrid", torrentioProviderKey(DebridService.REAL_DEBRID))
        assertEquals("premiumize", torrentioProviderKey(DebridService.PREMIUMIZE))
        assertEquals("torbox", torrentioProviderKey(DebridService.TORBOX))
    }

    @Test
    fun blankCredentialsAreRejectedBeforeBuildingAProviderUrl() {
        assertFailsWith<IllegalArgumentException> {
            buildTorrentioManifestUrl(DebridService.TORBOX, " ")
        }
    }

    @Test
    fun cometUsesCachedOnlyAndInjectsDebridService() {
        val url = buildCometManifestUrl(DebridService.TORBOX, "torbox-secret")
        assertTrue(url.startsWith("https://comet.elfhosted.com/"))
        assertTrue(url.endsWith("/manifest.json"))
        val encoded = url.removePrefix("https://comet.elfhosted.com/").removeSuffix("/manifest.json")
        val json = String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        assertTrue(json.contains("\"cachedOnly\":true"))
        assertTrue(json.contains("\"enableTorrent\":false"))
        assertTrue(json.contains("\"service\":\"torbox\""))
        assertTrue(json.contains("\"apiKey\":\"torbox-secret\""))
        // Bootstrap allowlist stays keyless — configured URL is separate.
        assertFalse(CometManifestUrl.contains("torbox-secret"))
    }

    @Test
    fun meteorUsesCachedOnlyAndInjectsDebridService() {
        val url = buildMeteorManifestUrl(DebridService.TORBOX, "torbox-secret")
        assertTrue(url.startsWith("https://meteorfortheweebs.midnightignite.me/"))
        assertTrue(url.endsWith("/manifest.json"))
        val encoded = url.removePrefix("https://meteorfortheweebs.midnightignite.me/").removeSuffix("/manifest.json")
        val json = String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        assertTrue(json.contains("\"cachedOnly\":true"))
        assertTrue(json.contains("\"allowP2P\":false"))
        assertTrue(json.contains("\"service\":\"torbox\""))
        assertTrue(json.contains("\"apiKey\":\"torbox-secret\""))
        assertFalse(MeteorManifestUrl.contains("torbox-secret"))
    }
}
