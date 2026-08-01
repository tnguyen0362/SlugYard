package com.sluggyard.tv.core.aggregation

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVisibilityPolicyTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `future dated item is hidden only when preference is enabled`() {
        assertFalse(ReleaseVisibilityPolicy.isVisible("2027-01-01", hideUnreleased = true, clock))
        assertTrue(ReleaseVisibilityPolicy.isVisible("2027-01-01", hideUnreleased = false, clock))
    }

    @Test
    fun `past and unknown release values remain visible`() {
        assertTrue(ReleaseVisibilityPolicy.isVisible("2026-07-18", hideUnreleased = true, clock))
        assertTrue(ReleaseVisibilityPolicy.isVisible(null, hideUnreleased = true, clock))
        assertTrue(ReleaseVisibilityPolicy.isVisible("coming soon", hideUnreleased = true, clock))
    }
}
