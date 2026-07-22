package com.sluggyard.tv.data.repository

import android.content.Context
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.data.remote.api.AddonApi
import com.sluggyard.tv.data.remote.dto.MetaDto
import com.sluggyard.tv.data.remote.dto.MetaResponseDto
import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.AddonResource
import com.sluggyard.tv.domain.model.ContentType
import com.sluggyard.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Characterization tests for [MetaRepositoryImpl]'s actual fan-out/caching/error-aggregation
 * behavior, ahead of any Phase 2 rewrite (see rewrite plan). These pin down current behavior
 * as a regression oracle: candidate selection itself is already covered by
 * [MetaCandidateSelectorTest], this file covers the flow orchestration around it (caching,
 * "source addon already sufficient" short-circuit, and error aggregation across addons).
 */
class MetaRepositoryImplTest {

    @Test
    fun `getMeta returns cached meta without calling the api again`() = runTest {
        val api = mockk<AddonApi>()
        val url = "https://torrentio.strem.fun/meta/movie/tt1.json"
        coEvery { api.getMeta(url) } returns metaResponse("tt1", "movie", "First Fetch")
        val repository = newRepository(api)

        repository.getMeta(addonBaseUrl = "https://torrentio.strem.fun", type = "movie", id = "tt1").last()
        val second = repository.getMeta(addonBaseUrl = "https://torrentio.strem.fun", type = "movie", id = "tt1").last()

        assertTrue(second is NetworkResult.Success)
        assertEquals("First Fetch", (second as NetworkResult.Success).data.name)
        coVerify(exactly = 1) { api.getMeta(url) }
    }

    @Test
    fun `getMeta emits Loading then Error when the addon has no meta`() = runTest {
        val api = mockk<AddonApi>()
        val url = "https://torrentio.strem.fun/meta/movie/tt1.json"
        coEvery { api.getMeta(url) } returns Response.success(MetaResponseDto(meta = null))
        val repository = newRepository(api)

        val emissions = repository.getMeta(addonBaseUrl = "https://torrentio.strem.fun", type = "movie", id = "tt1").toList()

        assertEquals(NetworkResult.Loading, emissions.first())
        assertTrue(emissions.last() is NetworkResult.Error)
    }

    @Test
    fun `getMetaFromAllAddons fetches from the first addon whose resource declares the requested type`() = runTest {
        val api = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns flowOf(
                listOf(
                    metaAddon(id = "cinemeta", baseUrl = "https://cinemeta.example", types = listOf("movie"))
                )
            )
        }
        val url = "https://cinemeta.example/meta/movie/tt1.json"
        coEvery { api.getMeta(url) } returns metaResponse("tt1", "movie", "Cinemeta Result")
        val repository = newRepository(api, addonRepository)

        val result = repository.getMetaFromAllAddons(type = "movie", id = "tt1").last()

        assertTrue(result is NetworkResult.Success)
        assertEquals("Cinemeta Result", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `getMetaFromAllAddons short-circuits when the candidate is the same addon that served the catalog`() = runTest {
        val api = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns flowOf(
                listOf(metaAddon(id = "cinemeta", baseUrl = "https://cinemeta.example", types = listOf("movie")))
            )
        }
        val repository = newRepository(api, addonRepository)

        val result = repository.getMetaFromAllAddons(
            type = "movie",
            id = "tt1",
            sourceAddonBaseUrl = "https://cinemeta.example/"
        ).last()

        assertTrue(result is NetworkResult.Error)
        assertEquals(NetworkResult.SOURCE_SUFFICIENT_CODE, (result as NetworkResult.Error).code)
        coVerify(exactly = 0) { api.getMeta(any()) }
    }

    @Test
    fun `getMetaFromAllAddons falls through to the next addon when the first returns no meta`() = runTest {
        val api = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns flowOf(
                listOf(
                    metaAddon(id = "flaky", baseUrl = "https://flaky.example", types = listOf("movie")),
                    metaAddon(id = "reliable", baseUrl = "https://reliable.example", types = listOf("movie"))
                )
            )
        }
        coEvery { api.getMeta("https://flaky.example/meta/movie/tt1.json") } returns
            Response.success(MetaResponseDto(meta = null))
        coEvery { api.getMeta("https://reliable.example/meta/movie/tt1.json") } returns
            metaResponse("tt1", "movie", "Reliable Result")
        val repository = newRepository(api, addonRepository)

        val result = repository.getMetaFromAllAddons(type = "movie", id = "tt1").last()

        assertTrue(result is NetworkResult.Success)
        assertEquals("Reliable Result", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `getMetaFromAllAddons falls back to the top installed addon when none declares the requested type`() = runTest {
        // MetaCandidateSelector's tier 3 always includes the top addon exposing a meta
        // resource (regardless of declared type) as a last-resort candidate, so this addon
        // is still queried even though it only declares "series" support.
        val api = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns flowOf(
                listOf(metaAddon(id = "series-only", baseUrl = "https://series-only.example", types = listOf("series")))
            )
        }
        coEvery { api.getMeta("https://series-only.example/meta/movie/tt1.json") } returns
            Response.success(MetaResponseDto(meta = null))
        val repository = newRepository(api, addonRepository)

        val result = repository.getMetaFromAllAddons(type = "movie", id = "tt1").last()

        assertTrue(result is NetworkResult.Error)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `getMetaFromPrimaryAddon uses the first addon supporting the requested type`() = runTest {
        val api = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository> {
            every { getInstalledAddons() } returns flowOf(
                listOf(
                    metaAddon(id = "series-only", baseUrl = "https://series-only.example", types = listOf("series")),
                    metaAddon(id = "movie-addon", baseUrl = "https://movie-addon.example", types = listOf("movie"))
                )
            )
        }
        coEvery { api.getMeta("https://movie-addon.example/meta/movie/tt1.json") } returns
            metaResponse("tt1", "movie", "Primary Result")
        val repository = newRepository(api, addonRepository)

        val result = repository.getMetaFromPrimaryAddon(type = "movie", id = "tt1").last()

        assertTrue(result is NetworkResult.Success)
        assertEquals("Primary Result", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `clearCache forces a fresh api call on the next lookup`() = runTest {
        val api = mockk<AddonApi>()
        val url = "https://torrentio.strem.fun/meta/movie/tt1.json"
        coEvery { api.getMeta(url) } returns metaResponse("tt1", "movie", "First Fetch")
        val repository = newRepository(api)

        repository.getMeta(addonBaseUrl = "https://torrentio.strem.fun", type = "movie", id = "tt1").last()
        repository.clearCache()
        repository.getMeta(addonBaseUrl = "https://torrentio.strem.fun", type = "movie", id = "tt1").last()

        coVerify(exactly = 2) { api.getMeta(url) }
    }

    private fun newRepository(api: AddonApi, addonRepository: AddonRepository = mockk(relaxed = true)): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true)
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository
        )
    }

    private fun metaAddon(id: String, baseUrl: String, types: List<String>): Addon = Addon(
        id = id,
        name = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = types.map { ContentType.fromString(it) },
        resources = listOf(AddonResource(name = "meta", types = types, idPrefixes = null)),
        enabled = true
    )

    private fun metaResponse(id: String, type: String, name: String): Response<MetaResponseDto> =
        Response.success(
            MetaResponseDto(
                meta = MetaDto(id = id, type = type, name = name)
            )
        )
}
