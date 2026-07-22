package com.sluggyard.tv.ui.rewrite.home

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
import org.junit.Assert.assertNull
import org.junit.Test

class RewriteHeroEnrichmentDataSourceTest {
    @Test
    fun `metadata upgrades hero without changing its navigation identity`() = runTest {
        val source = RewriteHeroEnrichmentDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon())) },
            gateway = gateway("""{"meta":{"id":"movie","type":"movie","name":"Movie","background":"https://backdrop","description":"A proper summary","releaseInfo":"2026","genres":["Drama","Mystery"]}}"""),
        )
        val hero = RewriteHero("movie", "Movie", "https://poster", "Base", "Movie", "source", "movie")

        val state = RewriteHomeState(hero = hero).withHeroEnrichment(source.enrich(hero))

        assertEquals("movie", state.hero?.id)
        assertEquals("source", state.hero?.addonId)
        assertEquals("https://backdrop", state.hero?.backdropUrl)
        assertEquals("A proper summary", state.hero?.summary)
        assertEquals("2026  ·  Drama · Mystery", state.hero?.metadata)
    }

    @Test
    fun `failed enrichment leaves base hero intact`() = runTest {
        val source = RewriteHeroEnrichmentDataSource(
            registrySnapshot = { AddonRegistryState(listOf(addon())) },
            gateway = object : StremioAddonGateway {
                override suspend fun fetchManifest(manifestUrl: String) = error("unused")
                override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
                override suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> = AddonTransportResult.NetworkFailure(IllegalStateException("offline"))
                override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
                override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
            },
        )
        val hero = RewriteHero("movie", "Movie", "https://poster", "Base", "Movie", "source", "movie")

        assertNull(source.enrich(hero))
        assertEquals(hero, RewriteHomeState(hero = hero).withHeroEnrichment(null).hero)
    }

    private fun addon() = ManagedAddon(
        manifestUrl = "https://source.test/manifest.json",
        manifest = AddonManifestContract(id = "source", name = "source", resources = setOf(AddonResource.META)),
    )

    private fun gateway(meta: String): StremioAddonGateway = object : StremioAddonGateway {
        override suspend fun fetchManifest(manifestUrl: String) = error("unused")
        override suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, extras: Map<String, String>) = error("unused")
        override suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> = AddonTransportResult.Success(Json.parseToJsonElement(meta).jsonObject)
        override suspend fun fetchStreams(manifestUrl: String, type: String, id: String) = error("unused")
        override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String) = error("unused")
    }
}
