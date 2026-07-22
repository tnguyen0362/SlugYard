package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.addonprotocol.AddonCatalogDeclaration
import com.sluggyard.tv.core.addonprotocol.AddonCatalogExtra
import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteAddonRegistryCodecTest {

    @Test
    fun `curated registry drops non allowlisted and configured entries`() {
        val state = AddonRegistryState(
            listOf(
                ManagedAddon(
                    manifestUrl = "https://example.test/manifest.json",
                    configuredManifestUrl = "https://attacker.test/manifest.json",
                    manifest = AddonManifestContract(id = "external", name = "External"),
                ),
            ),
        )

        assertTrue(sanitizeCuratedRegistry(state).addons.isEmpty())
    }

    @Test
    fun `curated registry keeps provider configured manifest on the same allowlisted host`() {
        val state = AddonRegistryState(
            listOf(
                ManagedAddon(
                    manifestUrl = "https://torrentio.strem.fun/manifest.json",
                    configuredManifestUrl = "https://torrentio.strem.fun/torbox=key/manifest.json",
                    manifest = AddonManifestContract(id = "torrentio", name = "Torrentio"),
                ),
            ),
        )

        assertEquals(
            "https://torrentio.strem.fun/torbox=key/manifest.json",
            sanitizeCuratedRegistry(state).addons.single().configuredManifestUrl,
        )
    }
    @Test
    fun `registry round trip preserves enabled order configured url and catalog capabilities`() {
        val state = AddonRegistryState(
            listOf(
                ManagedAddon(
                    manifestUrl = "https://original.test/manifest.json",
                    configuredManifestUrl = "https://configured.test/manifest.json",
                    enabled = false,
                    manifest = AddonManifestContract(
                        id = "addon",
                        name = "Addon",
                        resources = setOf(AddonResource.CATALOG, AddonResource.STREAM),
                        catalogs = listOf(
                            AddonCatalogDeclaration("home", "movie", "Home", listOf(AddonCatalogExtra("genre"))),
                        ),
                    ),
                ),
            ),
        )

        val restored = RewriteAddonRegistryCodec.decode(RewriteAddonRegistryCodec.encode(state))

        assertEquals(state.addons.single().manifestUrl, restored.addons.single().manifestUrl)
        assertEquals("https://configured.test/manifest.json", restored.addons.single().configuredManifestUrl)
        assertTrue(!restored.addons.single().enabled)
        assertEquals(setOf(AddonResource.CATALOG, AddonResource.STREAM), restored.addons.single().manifest.resources)
    }

    @Test
    fun `corrupted persistence safely becomes an empty registry`() {
        assertTrue(RewriteAddonRegistryCodec.decode("not json").addons.isEmpty())
    }
}
