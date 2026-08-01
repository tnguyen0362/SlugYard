package com.sluggyard.tv.data.repository

import android.content.Context
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.data.remote.api.AddonApi
import com.sluggyard.tv.data.remote.dto.CatalogResponseDto
import com.sluggyard.tv.data.remote.dto.MetaPreviewDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class CatalogRepositoryImplTest {

    @Test
    fun `skip=0 requests the base catalog path with no skip segment`() = runTest {
        val api = mockk<AddonApi>()
        val expectedUrl = "https://torrentio.strem.fun/catalog/movie/top.json"
        coEvery { api.getCatalog(expectedUrl) } returns catalogResponse("Top Movie")
        val repository = newRepository(api)

        val result = repository.getCatalog(
            addonBaseUrl = "https://torrentio.strem.fun",
            addonId = "torrentio",
            addonName = "Torrentio",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last()

        assertTrue(result is NetworkResult.Success)
        val row = (result as NetworkResult.Success).data
        assertEquals(1, row.items.size)
        assertEquals("Top Movie", row.items.first().name)
        assertEquals(0, row.currentPage)
        assertEquals(1, row.nextSkip)
    }

    @Test
    fun `skip greater than zero appends a skip segment`() = runTest {
        val api = mockk<AddonApi>()
        val expectedUrl = "https://torrentio.strem.fun/catalog/movie/top/skip=100.json"
        coEvery { api.getCatalog(expectedUrl) } returns catalogResponse("Page Two Movie")
        val repository = newRepository(api)

        val result = repository.getCatalog(
            addonBaseUrl = "https://torrentio.strem.fun",
            addonId = "torrentio",
            addonName = "Torrentio",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = 100,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last()

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, (result as NetworkResult.Success).data.currentPage)
    }

    @Test
    fun `extraArgs are encoded into a single path segment and skip is folded in when missing`() = runTest {
        val api = mockk<AddonApi>()
        val expectedUrl = "https://mediafusion.example/catalog/movie/top/genre=Sci-Fi%20%26%20Fantasy&skip=50.json"
        coEvery { api.getCatalog(expectedUrl) } returns catalogResponse("Filtered Movie")
        val repository = newRepository(api)

        val result = repository.getCatalog(
            addonBaseUrl = "https://mediafusion.example",
            addonId = "mediafusion",
            addonName = "MediaFusion",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = 50,
            skipStep = 50,
            extraArgs = mapOf("genre" to "Sci-Fi & Fantasy"),
            supportsSkip = true
        ).last()

        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun `existing query string on the base url is preserved after the catalog path`() = runTest {
        val api = mockk<AddonApi>()
        val expectedUrl = "https://addon.example/cfg/catalog/movie/top.json?token=abc"
        coEvery { api.getCatalog(expectedUrl) } returns catalogResponse("Configured Movie")
        val repository = newRepository(api)

        val result = repository.getCatalog(
            addonBaseUrl = "https://addon.example/cfg/?token=abc",
            addonId = "addon",
            addonName = "Addon",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last()

        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun `duplicate meta ids from the source are de-duplicated`() = runTest {
        val api = mockk<AddonApi>()
        val expectedUrl = "https://torrentio.strem.fun/catalog/movie/top.json"
        coEvery { api.getCatalog(expectedUrl) } returns Response.success(
            CatalogResponseDto(
                metas = listOf(
                    MetaPreviewDto(id = "tt1", type = "movie", name = "One"),
                    MetaPreviewDto(id = "tt1", type = "movie", name = "One Duplicate")
                )
            )
        )
        val repository = newRepository(api)

        val result = repository.getCatalog(
            addonBaseUrl = "https://torrentio.strem.fun",
            addonId = "torrentio",
            addonName = "Torrentio",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).last()

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, (result as NetworkResult.Success).data.items.size)
    }

    @Test
    fun `error response is passed through unchanged`() = runTest {
        val api = mockk<AddonApi>()
        val expectedUrl = "https://torrentio.strem.fun/catalog/movie/top.json"
        coEvery { api.getCatalog(expectedUrl) } returns Response.error(
            500,
            okhttp3.ResponseBody.create(null, "boom")
        )
        val repository = newRepository(api)

        val emissions = repository.getCatalog(
            addonBaseUrl = "https://torrentio.strem.fun",
            addonId = "torrentio",
            addonName = "Torrentio",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        ).toList()

        assertEquals(NetworkResult.Loading, emissions.first())
        assertTrue(emissions.last() is NetworkResult.Error)
    }

    private fun newRepository(api: AddonApi): CatalogRepositoryImpl {
        val context = mockk<Context>(relaxed = true)
        return CatalogRepositoryImpl(
            context = context,
            api = api
        )
    }

    private fun catalogResponse(name: String): Response<CatalogResponseDto> =
        Response.success(
            CatalogResponseDto(
                metas = listOf(MetaPreviewDto(id = "tt1", type = "movie", name = name))
            )
        )
}
