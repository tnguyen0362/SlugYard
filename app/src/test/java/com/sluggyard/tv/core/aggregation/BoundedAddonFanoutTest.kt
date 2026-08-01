package com.sluggyard.tv.core.aggregation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAddonFanoutTest {

    @Test
    fun `emits completed work before a slower sibling and keeps its result`() = runTest {
        val results = boundedAddonFanout(
            tasks = listOf(
                AddonFanoutTask("slow") { delay(100); "slow-value" },
                AddonFanoutTask("fast") { delay(10); "fast-value" },
            ),
            maxConcurrent = 2,
        ).toList()

        assertEquals(listOf("fast", "slow"), results.map { it.key })
        assertEquals(
            listOf("fast-value", "slow-value"),
            results.map { (it as AddonFanoutResult.Success).value },
        )
    }

    @Test
    fun `isolates one addon failure without dropping successful siblings`() = runTest {
        val results = boundedAddonFanout(
            tasks = listOf(
                AddonFanoutTask("healthy") { "catalog" },
                AddonFanoutTask<String>("broken") { error("bad response") },
            ),
        ).toList()

        assertEquals(2, results.size)
        assertTrue(results.any { it is AddonFanoutResult.Success && it.key == "healthy" })
        assertTrue(results.any { it is AddonFanoutResult.Failure && it.key == "broken" })
    }

    @Test
    fun `never exceeds the configured in flight limit`() = runTest {
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)
        val tasks = (1..8).map { index ->
            AddonFanoutTask(index.toString()) {
                val current = inFlight.incrementAndGet()
                peakInFlight.updateAndGet { previous -> maxOf(previous, current) }
                delay(20)
                inFlight.decrementAndGet()
                index
            }
        }

        val results = boundedAddonFanout(tasks, maxConcurrent = 3).toList()

        assertEquals(8, results.size)
        assertTrue("peak was ${peakInFlight.get()}", peakInFlight.get() <= 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non positive concurrency limit`() = runTest {
        boundedAddonFanout(emptyList<AddonFanoutTask<Unit>>(), maxConcurrent = 0).toList()
    }
}
