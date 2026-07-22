package com.sluggyard.tv.ui.rewrite.home

import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteHomeCatalogControllerTest {

    @Test
    fun `keeps saved order while rendering completed rows progressively`() = runTest {
        val first = request("one", "featured", delayMs = 100)
        val second = request("two", "trending", delayMs = 10)

        val emissions = loadRewriteHomeCatalogs(
            requestsInDefaultOrder = listOf(first, second),
            savedOrder = listOf(second.key),
            maxConcurrent = 2,
        ).toList()

        assertEquals(listOf(second.key, first.key), emissions.first().rows.map(RewriteCatalogRowState::key))
        assertTrue(emissions[1].rows.first().loadState is RewriteCatalogLoadState.Content)
        assertTrue(emissions[1].rows.last().loadState is RewriteCatalogLoadState.Loading)
        assertTrue(emissions.last().rows.first().loadState is RewriteCatalogLoadState.Content)
        assertTrue(emissions.last().rows.last().loadState is RewriteCatalogLoadState.Content)
    }

    @Test
    fun `contains an addon error in its own row`() = runTest {
        val failed = RewriteCatalogRequest(
            key = HomeCatalogKey("failed", "row"),
            title = "Failed row",
            load = { error("bad addon") },
        )
        val healthy = request("healthy", "row", delayMs = 0)

        val final = loadRewriteHomeCatalogs(listOf(failed, healthy), savedOrder = emptyList()).toList().last()

        assertTrue(final.rows[0].loadState is RewriteCatalogLoadState.Error)
        assertTrue(final.rows[1].loadState is RewriteCatalogLoadState.Content)
    }

    private fun request(addonId: String, catalogId: String, delayMs: Long): RewriteCatalogRequest {
        val key = HomeCatalogKey(addonId, catalogId)
        return RewriteCatalogRequest(
            key = key,
            title = catalogId,
            load = {
                delay(delayMs)
                listOf(RewriteHomePoster(id = "$addonId:$catalogId", title = catalogId, imageUrl = null))
            },
        )
    }
}
