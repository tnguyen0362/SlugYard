package com.sluggyard.tv.core.traktpolicy

const val TRAKT_BASE_REFRESH_MS = 60_000L
const val TRAKT_MAX_REFRESH_MS = 10 * 60_000L
const val TRAKT_MANUAL_REFRESH_THROTTLE_MS = 2_000L
const val TRAKT_FORCED_STALE_WINDOW_MS = 30_000L
const val TRAKT_COLD_START_INVALIDATION_AFTER_MS = 5_000L
const val TRAKT_SCROBBLE_DEDUP_WINDOW_MS = 8_000L
const val TRAKT_SCROBBLE_PROGRESS_DELTA = 0.015

enum class TraktScrobbleAction { START, PAUSE, STOP }

data class TraktRefreshSchedule(
    val consecutiveFailures: Int = 0,
    val forcedStaleUntilMs: Long = 0,
) {
    val intervalMs: Long
        get() = (TRAKT_BASE_REFRESH_MS * (1L shl consecutiveFailures.coerceAtMost(10)))
            .coerceAtMost(TRAKT_MAX_REFRESH_MS)
}

data class LastScrobble(
    val itemId: String,
    val action: TraktScrobbleAction,
    val sentAtMs: Long,
    val progressFraction: Double,
    val lastSuccessfulAction: TraktScrobbleAction?,
)

/** Pure timing and de-duplication rules for the replacement Trakt service. */
object TraktSyncPolicy {
    fun afterRefresh(schedule: TraktRefreshSchedule, succeeded: Boolean): TraktRefreshSchedule =
        if (succeeded) schedule.copy(consecutiveFailures = 0)
        else schedule.copy(consecutiveFailures = schedule.consecutiveFailures + 1)

    fun acceptsManualRefresh(lastManualRefreshMs: Long?, nowMs: Long): Boolean =
        lastManualRefreshMs == null || nowMs - lastManualRefreshMs >= TRAKT_MANUAL_REFRESH_THROTTLE_MS

    fun afterManualRefresh(schedule: TraktRefreshSchedule, nowMs: Long): TraktRefreshSchedule =
        schedule.copy(forcedStaleUntilMs = nowMs + TRAKT_FORCED_STALE_WINDOW_MS)

    fun shouldInvalidateColdStartCache(serviceRunningMs: Long): Boolean =
        serviceRunningMs > TRAKT_COLD_START_INVALIDATION_AFTER_MS

    fun shouldSuppressScrobble(
        last: LastScrobble?,
        itemId: String,
        action: TraktScrobbleAction,
        progressFraction: Double,
        nowMs: Long,
    ): Boolean {
        if (last == null || last.itemId != itemId || last.action != action) return false
        if (nowMs - last.sentAtMs > TRAKT_SCROBBLE_DEDUP_WINDOW_MS) return false
        if (kotlin.math.abs(progressFraction - last.progressFraction) > TRAKT_SCROBBLE_PROGRESS_DELTA) return false
        return !(action in setOf(TraktScrobbleAction.STOP, TraktScrobbleAction.PAUSE) &&
            last.lastSuccessfulAction == TraktScrobbleAction.START)
    }

    /** Null means no retry. Only failed STOP requests are retried, at most twice. */
    fun retryDelayMs(
        action: TraktScrobbleAction,
        failuresAlreadyObserved: Int,
        httpStatus: Int?,
    ): Long? {
        if (action != TraktScrobbleAction.STOP || failuresAlreadyObserved !in 1..2) return null
        return if (httpStatus in setOf(502, 503, 504)) 5_000L else 1_500L * failuresAlreadyObserved
    }
}
