package com.sluggyard.tv.ui.app.home

import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogControllerTest {

    @Test
    fun `keeps saved order while rendering completed rows progressively`() = runTest {
        // Avoid "featured"/"popular" titles so pinHomeCatalogDisplayOrder does not override savedOrder.
        val first = request("one", "shelf_a", delayMs = 100)
        val second = request("two", "shelf_b", delayMs = 10)

        val emissions = loadHomeCatalogs(
            requestsInDefaultOrder = listOf(first, second),
            savedOrder = listOf(second.key),
            maxConcurrent = 2,
        ).toList()

        assertEquals(listOf(second.key, first.key), emissions.first().rows.map(CatalogRowState::key))
        assertTrue(emissions[1].rows.first().loadState is CatalogLoadState.Content)
        assertTrue(emissions[1].rows.last().loadState is CatalogLoadState.Loading)
        assertTrue(emissions.last().rows.first().loadState is CatalogLoadState.Content)
        assertTrue(emissions.last().rows.last().loadState is CatalogLoadState.Content)
    }

    @Test
    fun `contains an addon error in its own row`() = runTest {
        val failed = CatalogRequest(
            key = HomeCatalogKey("failed", "row"),
            title = "Failed row",
            load = { error("bad addon") },
        )
        val healthy = request("healthy", "row", delayMs = 0)

        val final = loadHomeCatalogs(listOf(failed, healthy), savedOrder = emptyList()).toList().last()

        assertTrue(final.rows[0].loadState is CatalogLoadState.Error)
        assertTrue(final.rows[1].loadState is CatalogLoadState.Content)
    }

    private fun request(addonId: String, catalogId: String, delayMs: Long): CatalogRequest {
        val key = HomeCatalogKey(addonId, catalogId)
        return CatalogRequest(
            key = key,
            title = catalogId,
            load = {
                delay(delayMs)
                listOf(HomePoster(id = "$addonId:$catalogId", title = catalogId, imageUrl = null))
            },
        )
    }
}
