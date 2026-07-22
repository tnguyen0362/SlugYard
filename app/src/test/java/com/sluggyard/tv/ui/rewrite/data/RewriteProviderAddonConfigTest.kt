package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.streamresolution.DebridService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RewriteProviderAddonConfigTest {
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
}
