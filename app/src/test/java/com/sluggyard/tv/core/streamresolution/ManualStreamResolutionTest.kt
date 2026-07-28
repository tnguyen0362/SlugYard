package com.sluggyard.tv.core.streamresolution

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualStreamResolutionTest {
    @Test
    fun `direct url bypasses debrid resolution`() = runTest {
        val resolver = RecordingResolver()
        val coordinator = ManualStreamResolutionCoordinator(setOf(resolver))

        val result = coordinator.prepare(
            ManualStreamSelection("direct", "https://play", "hash", null),
            DebridService.TORBOX,
        )

        assertEquals("https://play", (result as ManualResolutionResult.Ready).source.url)
        assertEquals(0, resolver.calls)
    }

    @Test
    fun `torrent uses configured provider and preserves file index`() = runTest {
        val resolver = RecordingResolver()
        val coordinator = ManualStreamResolutionCoordinator(setOf(resolver))

        val result = coordinator.prepare(
            ManualStreamSelection("torrent", null, "abcdef", 4),
            DebridService.TORBOX,
        )

        assertTrue(result is ManualResolutionResult.Ready)
        assertEquals("abcdef" to 4, resolver.lastRequest)
        assertEquals("torrent", (result as ManualResolutionResult.Ready).source.sourceId)
    }

    @Test
    fun `torrent without configured resolver is unavailable rather than handed to player`() = runTest {
        val result = ManualStreamResolutionCoordinator(emptySet()).prepare(
            ManualStreamSelection("torrent", null, "abcdef", null),
            null,
        )

        assertTrue(result is ManualResolutionResult.Unavailable)
    }

    private class RecordingResolver : DebridManualResolver {
        override val service = DebridService.TORBOX
        var calls = 0
        var lastRequest: Pair<String, Int?>? = null

        override suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource {
            calls++
            lastRequest = infoHash to fileIndex
            return ResolvedPlaybackSource("https://resolved", infoHash)
        }
    }
}
