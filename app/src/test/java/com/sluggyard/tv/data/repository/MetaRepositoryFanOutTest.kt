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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MetaRepositoryFanOutTest {

    @Test
    fun `getMetaFromAllAddons returns meta from the first addon that supports the type and id prefix`() = runTest {
        val addon = metaAddon("cinemeta", "https://cinemeta.example", idPrefixes = listOf("tt"))
        val api = mockk<AddonApi>()
        coEvery { api.getMeta("https://cinemeta.example/meta/series/tt1.json") } returns
            metaResponse("Cinemeta Meta")

        val repository = newRepository(api = api, addons = listOf(addon))

        val result = repository.getMetaFromAllAddons("series", "tt1", null).last()

        assertTrue(result is NetworkResult.Success)
        assertEquals("Cinemeta Meta", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `an addon whose idPrefixes do not match the id is skipped in favor of a matching addon`() = runTest {
        val kitsuOnly = metaAddon("kitsu-addon", "https://kitsu.example", idPrefixes = listOf("kitsu:"))
        val cinemeta = metaAddon("cinemeta", "https://cinemeta.example", idPrefixes = listOf("tt"))
        val api = mockk<AddonApi>()
        coEvery { api.getMeta("https://cinemeta.example/meta/series/tt1.json") } returns
            metaResponse("Cinemeta Meta")

        val repository = newRepository(api = api, addons = listOf(kitsuOnly, cinemeta))

        val result = repository.getMetaFromAllAddons("series", "tt1", null).last()

        assertTrue(result is NetworkResult.Success)
        assertEquals("Cinemeta Meta", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `when the candidate addon is the same as the source addon, catalog meta is treated as sufficient`() = runTest {
        val addon = metaAddon("cinemeta", "https://cinemeta.example", idPrefixes = listOf("tt"))
        val api = mockk<AddonApi>()

        val repository = newRepository(api = api, addons = listOf(addon))

        val result = repository.getMetaFromAllAddons(
            "series", "tt1", sourceAddonBaseUrl = "https://cinemeta.example"
        ).last()

        assertTrue(result is NetworkResult.Error)
        assertEquals(NetworkResult.SOURCE_SUFFICIENT_CODE, (result as NetworkResult.Error).code)
    }

    @Test
    fun `when no installed addon supports the type, the result is an error`() = runTest {
        val api = mockk<AddonApi>()
        val repository = newRepository(api = api, addons = emptyList())

        val result = repository.getMetaFromAllAddons("series", "tt1", null).last()

        assertTrue(result is NetworkResult.Error)
    }

    @Test
    fun `when every candidate addon returns no meta, the result is an aggregate error`() = runTest {
        val addon = metaAddon("cinemeta", "https://cinemeta.example", idPrefixes = listOf("tt"))
        val api = mockk<AddonApi>()
        coEvery { api.getMeta("https://cinemeta.example/meta/series/tt1.json") } returns
            Response.success(MetaResponseDto(meta = null))

        val repository = newRepository(api = api, addons = listOf(addon))

        val result = repository.getMetaFromAllAddons("series", "tt1", null).last()

        assertTrue(result is NetworkResult.Error)
    }

    @Test
    fun `getMetaFromPrimaryAddon uses the first installed addon that supports the type`() = runTest {
        val nonMeta = metaAddon("no-meta", "https://nometa.example", resourceName = "catalog")
        val primary = metaAddon("cinemeta", "https://cinemeta.example")
        val api = mockk<AddonApi>()
        coEvery { api.getMeta("https://cinemeta.example/meta/series/tt1.json") } returns
            metaResponse("Primary Meta")

        val repository = newRepository(api = api, addons = listOf(nonMeta, primary))

        val result = repository.getMetaFromPrimaryAddon("series", "tt1").last()

        assertTrue(result is NetworkResult.Success)
        assertEquals("Primary Meta", (result as NetworkResult.Success).data.name)
    }

    @Test
    fun `getMetaFromPrimaryAddon returns an error when no addon exposes a meta resource`() = runTest {
        val nonMeta = metaAddon("no-meta", "https://nometa.example", resourceName = "catalog")
        val api = mockk<AddonApi>()

        val repository = newRepository(api = api, addons = listOf(nonMeta))

        val result = repository.getMetaFromPrimaryAddon("series", "tt1").last()

        assertTrue(result is NetworkResult.Error)
    }

    private fun metaAddon(
        id: String,
        baseUrl: String,
        idPrefixes: List<String> = emptyList(),
        resourceName: String = "meta"
    ): Addon = Addon(
        id = id,
        name = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.SERIES),
        resources = listOf(
            AddonResource(name = resourceName, types = listOf("series"), idPrefixes = idPrefixes.ifEmpty { null })
        ),
        idPrefixes = idPrefixes
    )

    private fun newRepository(api: AddonApi, addons: List<Addon>): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "error"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons)
        }
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository
        )
    }

    private fun metaResponse(name: String): Response<MetaResponseDto> =
        Response.success(
            MetaResponseDto(
                meta = MetaDto(
                    id = "tt1",
                    type = "series",
                    name = name
                )
            )
        )
}
