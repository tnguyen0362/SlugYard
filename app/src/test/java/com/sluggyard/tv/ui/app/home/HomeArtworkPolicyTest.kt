package com.sluggyard.tv.ui.app.home

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtworkPolicyTest {
    @Test
    fun `focused landscape cache miss keeps portrait fail-open`() {
        val state = homeArtworkRenderState(
            focused = true,
            wantsLandscape = true,
            landscapePainted = false,
        )

        assertTrue(state.expandLandscapeFrame)
        assertTrue(state.showPortrait)
        assertFalse(state.showLandscape)
        assertFalse(state.showLandscapeChrome)
    }

    @Test
    fun `focused painted landscape never exposes portrait`() {
        val state = homeArtworkRenderState(
            focused = true,
            wantsLandscape = true,
            landscapePainted = true,
        )

        assertTrue(state.expandLandscapeFrame)
        assertFalse(state.showPortrait)
        assertTrue(state.showLandscape)
        assertTrue(state.showLandscapeChrome)
    }

    @Test
    fun `focused card without landscape artwork remains portrait sized`() {
        val state = homeArtworkRenderState(
            focused = true,
            wantsLandscape = false,
            landscapePainted = false,
        )

        assertFalse(state.expandLandscapeFrame)
        assertTrue(state.showPortrait)
        assertFalse(state.showLandscape)
        assertFalse(state.showLandscapeChrome)
    }

    @Test
    fun `unfocused card remains portrait even when landscape is cached`() {
        val state = homeArtworkRenderState(
            focused = false,
            wantsLandscape = true,
            landscapePainted = true,
        )

        assertFalse(state.expandLandscapeFrame)
        assertTrue(state.showPortrait)
        assertFalse(state.showLandscape)
        assertFalse(state.showLandscapeChrome)
    }

    @Test
    fun `fractional density uses one rounded landscape size and cache key`() {
        val size = homeLandscapeArtworkSize(density = 1.33125f)

        assertEquals(HomeArtworkPixelSize(width = 625, height = 351), size)
        assertEquals(
            "card-backdrop:item:https://image:625x351",
            homeBackdropMemoryCacheKey("item", "https://image", size),
        )
    }

    @Test
    fun `high-density landscape decode uses the card display size`() {
        assertEquals(
            HomeArtworkPixelSize(width = 1408, height = 792),
            homeLandscapeArtworkSize(density = 3f),
        )
    }

    @Test
    fun `portrait size rounds instead of truncating`() {
        assertEquals(
            HomeArtworkPixelSize(width = 234, height = 351),
            homePortraitArtworkSize(density = 1.33125f),
        )
    }

    @Test
    fun `continue watching never participates in landscape warming`() {
        assertFalse(homeRowSupportsLandscape("continue_watching"))
        assertTrue(homeRowSupportsLandscape("cinemeta:popular"))
    }

    @Test
    fun `remaining splash budget is monotonic and bounded at zero`() {
        assertEquals(15_000L, remainingHomeSplashBudgetMs(1_000L, 4_000L, 18_000L))
        assertEquals(0L, remainingHomeSplashBudgetMs(1_000L, 20_000L, 18_000L))
    }

    @Test
    fun `splash work is cancelled at the remaining deadline`() = runTest {
        var cancelledRequests = 0
        val result = withRemainingHomeSplashBudget(
            startedAtElapsedMs = 1_000L,
            nowElapsedMs = 1_990L,
            maximumDurationMs = 1_000L,
        ) {
            runHomeArtworkWarmBatch(listOf("portrait", "landscape")) {
                try {
                    delay(100L)
                    true
                } finally {
                    cancelledRequests++
                }
            }
        }

        assertNull(result)
        assertEquals(2, cancelledRequests)
    }

    @Test
    fun `warm batch isolates ordinary request failures`() = runTest {
        val successes = runHomeArtworkWarmBatch(listOf("good", "bad")) { target ->
            if (target == "bad") error("decode failed")
            true
        }

        assertEquals(1, successes)
    }
}
