package com.sluggyard.tv.core.traktpolicy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktSyncPolicyTest {

    @Test
    fun `refresh failure doubles interval up to ten minute ceiling and success resets it`() {
        var schedule = TraktRefreshSchedule()
        repeat(4) { schedule = TraktSyncPolicy.afterRefresh(schedule, succeeded = false) }

        assertEquals(TRAKT_MAX_REFRESH_MS, schedule.intervalMs)
        assertEquals(TRAKT_BASE_REFRESH_MS, TraktSyncPolicy.afterRefresh(schedule, succeeded = true).intervalMs)
    }

    @Test
    fun `manual refresh is throttled and forces stale window when accepted`() {
        assertFalse(TraktSyncPolicy.acceptsManualRefresh(lastManualRefreshMs = 1_000, nowMs = 2_999))
        assertTrue(TraktSyncPolicy.acceptsManualRefresh(lastManualRefreshMs = 1_000, nowMs = 3_000))
        assertEquals(33_000, TraktSyncPolicy.afterManualRefresh(TraktRefreshSchedule(), 3_000).forcedStaleUntilMs)
    }

    @Test
    fun `stop after start is never deduplicated`() {
        val last = LastScrobble(
            itemId = "movie",
            action = TraktScrobbleAction.STOP,
            sentAtMs = 1_000,
            progressFraction = 0.2,
            lastSuccessfulAction = TraktScrobbleAction.START,
        )

        assertFalse(TraktSyncPolicy.shouldSuppressScrobble(last, "movie", TraktScrobbleAction.STOP, 0.2, 1_100))
    }

    @Test
    fun `identical recent scrobble is suppressed but material progress is sent`() {
        val last = LastScrobble("movie", TraktScrobbleAction.PAUSE, 1_000, 0.2, TraktScrobbleAction.PAUSE)

        assertTrue(TraktSyncPolicy.shouldSuppressScrobble(last, "movie", TraktScrobbleAction.PAUSE, 0.214, 2_000))
        assertFalse(TraktSyncPolicy.shouldSuppressScrobble(last, "movie", TraktScrobbleAction.PAUSE, 0.216, 2_000))
    }

    @Test
    fun `only stop retries with overload delay`() {
        assertEquals(1_500L, TraktSyncPolicy.retryDelayMs(TraktScrobbleAction.STOP, 1, null))
        assertEquals(5_000L, TraktSyncPolicy.retryDelayMs(TraktScrobbleAction.STOP, 2, 503))
        assertNull(TraktSyncPolicy.retryDelayMs(TraktScrobbleAction.START, 1, 503))
        assertNull(TraktSyncPolicy.retryDelayMs(TraktScrobbleAction.STOP, 3, null))
    }
}
