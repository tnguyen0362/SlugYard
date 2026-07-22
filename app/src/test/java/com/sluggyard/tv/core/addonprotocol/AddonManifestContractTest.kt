package com.sluggyard.tv.core.addonprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonManifestContractTest {

    private val validManifest = AddonManifestContract(
        id = "catalogue.example",
        name = "Example catalogue",
        resources = setOf(AddonResource.CATALOG, AddonResource.META),
    )

    @Test
    fun `accepts a reachable-form http manifest with id and name`() {
        val result = AddonManifestPolicy.validate("https://addons.example/manifest.json", validManifest)

        assertTrue(result is ManifestValidation.Accepted)
    }

    @Test
    fun `rejects non web manifest locations`() {
        val result = AddonManifestPolicy.validate("file:///tmp/manifest.json", validManifest)

        assertEquals("Manifest URL must use HTTP or HTTPS", (result as ManifestValidation.Rejected).reason)
    }

    @Test
    fun `rejects malformed or incomplete manifests`() {
        val missingId = AddonManifestPolicy.validate(
            "https://addons.example/manifest.json",
            validManifest.copy(id = ""),
        )
        val missingName = AddonManifestPolicy.validate(
            "https://addons.example/manifest.json",
            validManifest.copy(name = " "),
        )

        assertEquals("Manifest is missing an id", (missingId as ManifestValidation.Rejected).reason)
        assertEquals("Manifest is missing a name", (missingName as ManifestValidation.Rejected).reason)
    }

    @Test
    fun `home only includes listable non excluded catalogs`() {
        val manifest = validManifest.copy(
            catalogs = listOf(
                AddonCatalogDeclaration("featured", "movie", "Featured"),
                AddonCatalogDeclaration(
                    "search-only",
                    "movie",
                    "Search",
                    extras = listOf(AddonCatalogExtra("search", required = true)),
                ),
                AddonCatalogDeclaration("hidden", "series", "Hidden"),
            ),
        )

        val result = AddonManifestPolicy.homeEligibleCatalogs(manifest, setOf("hidden"))

        assertEquals(listOf("featured"), result.map(AddonCatalogDeclaration::id))
    }
}
