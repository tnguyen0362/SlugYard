package com.sluggyard.tv.core.streamresolution

enum class DebridService { REAL_DEBRID, TORBOX, PREMIUMIZE }

enum class StreamCacheState {
    /** Direct URLs and streams without a configured debrid service need no cache indication. */
    NOT_APPLICABLE,
    /** A provider that supports proactive checks has not answered yet. */
    CHECKING,
    CACHED,
    NOT_CACHED,
    /** A proactive provider failed to give a definitive answer; this is not a negative result. */
    UNKNOWN,
}

/** Provider-neutral result returned by a proactive hash availability query. */
sealed interface CacheCheckResult {
    data class Definitive(val cached: Boolean) : CacheCheckResult
    data object Failed : CacheCheckResult
}

/**
 * Shared cache-state policy for stream rows and the eventual resolver pipeline.
 *
 * Real-Debrid intentionally has no proactive check in this product integration. Returning
 * [StreamCacheState.NOT_APPLICABLE] prevents the UI from showing a permanent spinner or claiming
 * an unavailable torrent before a selected stream is actually resolved.
 */
object StreamCachePolicy {
    fun initialState(
        isTorrent: Boolean,
        configuredService: DebridService?,
    ): StreamCacheState = when {
        !isTorrent || configuredService == null -> StreamCacheState.NOT_APPLICABLE
        configuredService == DebridService.REAL_DEBRID -> StreamCacheState.NOT_APPLICABLE
        else -> StreamCacheState.CHECKING
    }

    fun applyProactiveCheck(
        configuredService: DebridService,
        result: CacheCheckResult,
    ): StreamCacheState {
        require(configuredService != DebridService.REAL_DEBRID) {
            "Real-Debrid cache availability is discovered only during lazy resolution"
        }
        return when (result) {
            is CacheCheckResult.Definitive -> if (result.cached) StreamCacheState.CACHED else StreamCacheState.NOT_CACHED
            CacheCheckResult.Failed -> StreamCacheState.UNKNOWN
        }
    }

    /** Unknown remains playable; an outage must not silently hide every torrent stream. */
    fun isPlayableByDefault(cacheState: StreamCacheState): Boolean =
        cacheState != StreamCacheState.NOT_CACHED
}
