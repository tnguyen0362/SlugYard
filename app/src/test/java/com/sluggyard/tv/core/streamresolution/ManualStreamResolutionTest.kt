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
    fun `torrent without debrid hands torrent sentinel to player`() = runTest {
        val result = ManualStreamResolutionCoordinator(emptySet()).prepare(
            ManualStreamSelection(
                id = "torrent",
                directUrl = null,
                infoHash = "abcdef",
                fileIndex = 2,
                filename = "show.mkv",
                streamName = "1080p",
            ),
            null,
        )

        val ready = result as ManualResolutionResult.Ready
        assertEquals("torrent://abcdef", ready.source.url)
        assertEquals("abcdef", ready.source.infoHash)
        assertEquals(2, ready.source.fileIndex)
        assertEquals("show.mkv", ready.source.filename)
        assertEquals("1080p", ready.source.streamName)
    }

    @Test
    fun `direct resolution preserves proxy headers and binge group`() = runTest {
        val result = ManualStreamResolutionCoordinator(emptySet()).prepare(
            ManualStreamSelection(
                id = "direct",
                directUrl = "https://cdn/play",
                infoHash = null,
                fileIndex = null,
                requestHeaders = mapOf("User-Agent" to "SlugYard"),
                bingeGroup = "group-1",
                videoHash = "abc",
            ),
            null,
        )
        val source = (result as ManualResolutionResult.Ready).source
        assertEquals(mapOf("User-Agent" to "SlugYard"), source.requestHeaders)
        assertEquals("group-1", source.bingeGroup)
        assertEquals("abc", source.videoHash)
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
