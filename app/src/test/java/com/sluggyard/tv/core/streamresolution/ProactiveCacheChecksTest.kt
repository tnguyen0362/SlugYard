package com.sluggyard.tv.core.streamresolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProactiveCacheChecksTest {
    @Test
    fun `real debrid is never assigned a proactive checker`() {
        assertNull(selectProactiveCacheChecker(DebridService.REAL_DEBRID, emptySet()))
    }

    @Test
    fun `missing bulk response is unknown rather than not cached`() {
        val states = resolveProactiveCacheStates(DebridService.TORBOX, setOf("a", "b"), mapOf("a" to CacheCheckResult.Definitive(true)))
        assertEquals(StreamCacheState.CACHED, states["a"])
        assertEquals(StreamCacheState.UNKNOWN, states["b"])
    }
}
