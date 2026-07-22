package com.sluggyard.tv.core.player

/**
 * Pure decision rules for the external-player auto-advance state machine.
 *
 * Extracted from [ExternalPlaybackTracker] so the flag/timing logic is unit-testable
 * without Android dependencies. All functions are pure: same inputs -> same output.
 */
internal object ExternalAutoNextPolicy {

    private val SERIES_TYPES = setOf("series", "tv")

    /**
     * A launch should clear a pending chain abort unless it is an auto-next continuation
     * (an auto-launch within [continuationWindowMs] of the last emit). A manual launch is
     * always fresh, so manually starting an episode right after a Back-abort re-enables
     * auto-next.
     */
    fun shouldResetChainAbort(
        autoLaunch: Boolean,
        nowMs: Long,
        lastAutoNextEmitMs: Long,
        continuationWindowMs: Long
    ): Boolean {
        val isContinuation = autoLaunch && nowMs - lastAutoNextEmitMs < continuationWindowMs
        return !isContinuation
    }

    /**
     * A user's abort should still suppress the pending auto-launch only while inside the
     * continuation window, so a stale abort can't block a fresh auto-play of an unrelated
     * title.
     */
    fun isAbortedContinuation(
        chainAborted: Boolean,
        nowMs: Long,
        lastAutoNextEmitMs: Long,
        continuationWindowMs: Long
    ): Boolean = chainAborted && nowMs - lastAutoNextEmitMs < continuationWindowMs

    /**
     * The auto-advance loader may be raised only for a series/tv episode that hasn't been
     * aborted, released on settle, or already raised. Season may be null (absolute-numbered
     * content); only the episode number is required.
     */
    fun shouldRaiseLoader(
        episode: Int?,
        contentType: String,
        cancelled: Boolean,
        chainAborted: Boolean,
        overlaySuppressed: Boolean,
        alreadyShowing: Boolean
    ): Boolean {
        if (cancelled || chainAborted || overlaySuppressed || alreadyShowing) return false
        return isSeriesEpisode(episode, contentType)
    }

    /**
     * Auto-advance should be attempted only for a series/tv episode the user hasn't aborted.
     * Season may be null (absolute-numbered content); only the episode number is required.
     */
    fun shouldAttemptAdvance(
        episode: Int?,
        contentType: String,
        cancelled: Boolean,
        chainAborted: Boolean
    ): Boolean {
        if (cancelled || chainAborted) return false
        return isSeriesEpisode(episode, contentType)
    }

    private fun isSeriesEpisode(episode: Int?, contentType: String): Boolean =
        episode != null && contentType.lowercase() in SERIES_TYPES
}