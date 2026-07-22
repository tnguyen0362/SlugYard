package com.sluggyard.tv.core.streamresolution

/** Implemented only by providers with a documented bulk hash cache endpoint. */
interface DebridProactiveCacheChecker {
    val service: DebridService
    suspend fun check(infoHashes: Set<String>): Map<String, CacheCheckResult>
}

/** Real-Debrid is intentionally absent: it is resolved lazily only after selection. */
fun selectProactiveCacheChecker(
    configuredService: DebridService?,
    checkers: Set<DebridProactiveCacheChecker>,
): DebridProactiveCacheChecker? = configuredService
    ?.takeIf { it != DebridService.REAL_DEBRID }
    ?.let { service -> checkers.firstOrNull { it.service == service } }

fun resolveProactiveCacheStates(
    service: DebridService,
    infoHashes: Set<String>,
    results: Map<String, CacheCheckResult>,
): Map<String, StreamCacheState> {
    require(service != DebridService.REAL_DEBRID)
    return infoHashes.associateWith { hash ->
        StreamCachePolicy.applyProactiveCheck(service, results[hash] ?: CacheCheckResult.Failed)
    }
}
