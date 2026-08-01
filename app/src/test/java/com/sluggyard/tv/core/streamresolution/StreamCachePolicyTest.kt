package com.sluggyard.tv.core.streamresolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCachePolicyTest {

    @Test
    fun `real debrid torrent never enters a proactive checking state`() {
        assertEquals(
            StreamCacheState.NOT_APPLICABLE,
            StreamCachePolicy.initialState(isTorrent = true, configuredService = DebridService.REAL_DEBRID),
        )
    }

    @Test
    fun `torbox and premiumize begin asynchronous availability checks`() {
        assertEquals(
            StreamCacheState.CHECKING,
            StreamCachePolicy.initialState(isTorrent = true, configuredService = DebridService.TORBOX),
        )
        assertEquals(
            StreamCacheState.CHECKING,
            StreamCachePolicy.initialState(isTorrent = true, configuredService = DebridService.PREMIUMIZE),
        )
    }

    @Test
    fun `provider error remains unknown and playable rather than becoming uncached`() {
        val state = StreamCachePolicy.applyProactiveCheck(DebridService.TORBOX, CacheCheckResult.Failed)

        assertEquals(StreamCacheState.UNKNOWN, state)
        assertTrue(StreamCachePolicy.isPlayableByDefault(state))
        assertFalse(StreamCachePolicy.isPlayableByDefault(StreamCacheState.NOT_CACHED))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `real debrid cannot be given a proactive answer`() {
        StreamCachePolicy.applyProactiveCheck(DebridService.REAL_DEBRID, CacheCheckResult.Definitive(cached = true))
    }
}
