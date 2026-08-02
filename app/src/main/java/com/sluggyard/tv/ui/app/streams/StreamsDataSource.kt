package com.sluggyard.tv.ui.app.streams

import android.util.Log
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder
import com.sluggyard.tv.core.aggregation.AIOSTREAMS_STREAM_TIMEOUT_MS
import com.sluggyard.tv.core.aggregation.AddonFanoutResult
import com.sluggyard.tv.core.aggregation.AddonFanoutTask
import com.sluggyard.tv.core.aggregation.DEFAULT_ADDON_CONCURRENCY
import com.sluggyard.tv.core.aggregation.DEFAULT_ADDON_TIMEOUT_MS
import com.sluggyard.tv.core.aggregation.boundedAddonFanout
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.debrid.StreamTextSizeParser
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.streamresolution.StreamCachePolicy
import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.core.streamresolution.DebridProactiveCacheChecker
import com.sluggyard.tv.core.streamresolution.resolveProactiveCacheStates
import com.sluggyard.tv.core.streamresolution.selectProactiveCacheChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.net.URI

private const val REWRITE_STREAM_SOURCE_TAG = "StreamSources"

/** First TorBox checkcached batch per addon — enough for auto-pick, not the whole dump. */
private const val MaxProactiveCacheHashesPerAddon = 40

/** Coalesce TorBox probe → Compose updates; unthrottled emits were janking the Onn mid-scrape. */
private const val CacheEmitMinIntervalMs = 350L

/** A progressive, per-addon stream result list for the rewritten Streams surface. */
class StreamsDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
    private val proactiveCacheCheckers: Set<DebridProactiveCacheChecker> = emptySet(),
) {
    fun streamGroups(
        type: String,
        id: String,
        configuredDebrid: DebridService?,
        maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
    ): Flow<List<StreamGroup>> = channelFlow {
        val addons = registrySnapshot().enabledAddons.filter { addon ->
            AddonResource.STREAM in addon.manifest.resources &&
                // WatchHub is availability metadata for Details, not a playable torrent source.
                !addon.isWatchHubSource() &&
                // AIOStreams is anime-oriented and its 20s scrape blocks movie Finding on leanback.
                !(addon.isAioStreamsSource() && type.equals("movie", ignoreCase = true))
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                REWRITE_STREAM_SOURCE_TAG,
                "fanout addons=${addons.joinToString(" | ") { addon ->
                    "${addon.manifest.id}:${addon.manifest.name}:host=${addonHost(addon)}"
                }}",
            )
        }
        val groups = addons.map { addon ->
            StreamGroup(addon.manifest.id, addon.manifest.name, StreamGroupState.Loading)
        }.toMutableList()
        send(groups.toList())
        val groupsMutex = Mutex()
        val cacheJobs = mutableListOf<Job>()
        val cacheStates = mutableMapOf<String, StreamCacheState>()
        val cacheChecksInFlight = mutableSetOf<String>()
        val cacheSemaphore = Semaphore(permits = 2)
        var lastCacheEmitElapsedMs = 0L
        var cacheEmitCoalesceJob: Job? = null
        suspend fun snapshotAndSend() {
            val snapshot = groupsMutex.withLock { groups.toList() }
            send(snapshot)
        }
        fun scheduleThrottledCacheEmit() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCacheEmitElapsedMs >= CacheEmitMinIntervalMs) {
                lastCacheEmitElapsedMs = now
                cacheEmitCoalesceJob?.cancel()
                cacheEmitCoalesceJob = null
                launch { snapshotAndSend() }
                return
            }
            if (cacheEmitCoalesceJob?.isActive == true) return
            cacheEmitCoalesceJob = launch {
                val wait = (CacheEmitMinIntervalMs - (android.os.SystemClock.elapsedRealtime() - lastCacheEmitElapsedMs))
                    .coerceIn(1L, CacheEmitMinIntervalMs)
                delay(wait)
                lastCacheEmitElapsedMs = android.os.SystemClock.elapsedRealtime()
                cacheEmitCoalesceJob = null
                snapshotAndSend()
            }
        }
        val byTask = LinkedHashMap<String, ManagedAddon>()
        val tasks = addons.map { addon ->
            val taskKey = addon.manifest.id
            byTask[taskKey] = addon
            val timeoutMs = if (addon.isAioStreamsSource()) {
                AIOSTREAMS_STREAM_TIMEOUT_MS
            } else {
                DEFAULT_ADDON_TIMEOUT_MS
            }
            AddonFanoutTask(
                key = taskKey,
                timeoutMs = timeoutMs,
                load = {
                val requestUrl = addon.configuredManifestUrl ?: addon.manifestUrl
                if (BuildConfig.DEBUG) {
                    Log.d(
                        REWRITE_STREAM_SOURCE_TAG,
                        "request addon=${addon.manifest.id} host=${addonHost(addon)} " +
                            "mode=${addonUrlMode(requestUrl)} type=$type id=$id timeoutMs=$timeoutMs",
                    )
                }
                when (val response = gateway.fetchStreams(requestUrl, type, id)) {
                    is AddonTransportResult.Success -> {
                        val streams = StremioResponseDecoder.streamItems(response.value)
                        if (BuildConfig.DEBUG) {
                            Log.i(
                                REWRITE_STREAM_SOURCE_TAG,
                                "response addon=${addon.manifest.id} mode=${addonUrlMode(requestUrl)} " +
                                    "streams=${streams.size}",
                            )
                        }
                        streams
                    }
                    else -> {
                        if (BuildConfig.DEBUG) {
                            Log.w(
                                REWRITE_STREAM_SOURCE_TAG,
                                "response addon=${addon.manifest.id} mode=${addonUrlMode(requestUrl)} " +
                                    "error=${response.userMessage()}",
                            )
                        }
                        throw StreamLoadException(addon, response)
                    }
                }
                },
            )
        }
        boundedAddonFanout(tasks, maxConcurrent).collect { result ->
            val addon = byTask[result.key] ?: return@collect
            val index = groups.indexOfFirst { it.addonId == addon.manifest.id }
            if (index < 0) return@collect
            val state = when (result) {
                is AddonFanoutResult.Success -> {
                    val streams = result.value.map { stream ->
                        val normalized = normalizeAddonStreamSource(stream.directUrl, stream.infoHash)
                        val presentationBits = listOfNotNull(
                            stream.title,
                            stream.sourceName,
                            stream.description,
                            stream.filename,
                        )
                        val initialCache = StreamCachePolicy.initialState(
                            isTorrent = normalized.isTorrentOrDebridProxy,
                            configuredService = configuredDebrid,
                        )
                        // Meteor/Torrentio often stamp Instant/⚡ before TorBox checkcached returns.
                        // Trust that hint so movie auto-pick can early-exit without waiting on probes.
                        val cacheState = if (
                            initialCache == StreamCacheState.CHECKING &&
                            presentationBits.any(::looksAddonMarkedInstant)
                        ) {
                            StreamCacheState.CACHED
                        } else {
                            initialCache
                        }
                        StreamCandidate(
                            id = stream.id,
                            title = stream.title.cleanStreamPresentation(),
                            // The group already identifies the provider. Keep addon-supplied
                            // release noise and hashes out of the TV presentation.
                            sourceLabel = addon.manifest.name,
                            detailLabel = stream.sourceName
                                ?.cleanStreamPresentation()
                                ?.takeIf { it != stream.title.cleanStreamPresentation() },
                            cacheState = cacheState,
                            directUrl = normalized.playableDirectUrl,
                            infoHash = normalized.infoHash,
                            fileIndex = stream.fileIndex,
                            metadataText = presentationBits.joinToString(" "),
                            videoSizeBytes = stream.videoSizeBytes
                                ?: StreamTextSizeParser.sizeBytesFromText(stream.description)
                                ?: StreamTextSizeParser.sizeBytesFromText(stream.title)
                                ?: StreamTextSizeParser.sizeBytesFromText(stream.sourceName)
                                ?: StreamTextSizeParser.sizeBytesFromText(stream.filename),
                            seeders = stream.seeders,
                            filename = stream.filename,
                            streamDescription = stream.description,
                            bingeGroup = stream.bingeGroup,
                            videoHash = stream.videoHash,
                            requestHeaders = stream.requestHeaders,
                            trackers = stream.trackers,
                        )
                    }
                    if (streams.isEmpty()) StreamGroupState.Empty else StreamGroupState.Content(streams)
                }
                is AddonFanoutResult.Failure -> StreamGroupState.Error(
                    (result.cause as? StreamLoadException)?.message
                        ?: "This source could not load streams",
                )
            }
            groupsMutex.withLock {
                groups[index] = groups[index].copy(state = state)
            }
            // Addon settle is always immediate — only cache probes are coalesced.
            snapshotAndSend()
            val checker = selectProactiveCacheChecker(configuredDebrid, proactiveCacheCheckers)
            val content = state as? StreamGroupState.Content ?: return@collect
            val hashes = content.streams.mapNotNull { it.infoHash?.trim()?.lowercase()?.takeIf(String::isNotBlank) }.toSet()
            if (checker != null && hashes.isNotEmpty()) {
                // Cache probes are independent of addon fetches. Keep source discovery
                // progressive instead of blocking the collector on one provider timeout.
                // Cap the first probe batch — TorBox checkcached on 200+ hashes was delaying
                // the first CACHED flags that unlock movie auto-pick (Mario ~2min Finding).
                val hashesToCheck = groupsMutex.withLock {
                    hashes
                        .filter { it !in cacheStates && cacheChecksInFlight.add(it) }
                        .take(MaxProactiveCacheHashesPerAddon)
                        .toSet()
                }
                if (hashesToCheck.isEmpty()) return@collect
                cacheJobs += launch {
                    val states = try {
                        cacheSemaphore.withPermit {
                            resolveProactiveCacheStates(checker.service, hashesToCheck, checker.check(hashesToCheck))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        resolveProactiveCacheStates(checker.service, hashesToCheck, emptyMap())
                    }
                    groupsMutex.withLock {
                        cacheStates.putAll(states)
                        cacheChecksInFlight.removeAll(hashesToCheck)
                        var changed = false
                        groups.indices.forEach { groupIndex ->
                            val current = groups[groupIndex].state as? StreamGroupState.Content
                                ?: return@forEach
                            var groupChanged = false
                            val updated = current.streams.map { candidate ->
                                val hash = candidate.infoHash?.trim()?.lowercase() ?: return@map candidate
                                val next = cacheStates[hash] ?: return@map candidate
                                if (next == candidate.cacheState) candidate
                                else {
                                    groupChanged = true
                                    candidate.copy(cacheState = next)
                                }
                            }
                            if (groupChanged) {
                                changed = true
                                groups[groupIndex] = groups[groupIndex].copy(
                                    state = StreamGroupState.Content(updated),
                                )
                            }
                        }
                        if (changed) scheduleThrottledCacheEmit()
                    }
                }
            }
        }
        cacheJobs.joinAll()
    }
}

private suspend fun <T> Semaphore.withPermit(block: suspend () -> T): T {
    acquire()
    return try {
        block()
    } finally {
        release()
    }
}

private class StreamLoadException(
    addon: ManagedAddon,
    result: AddonTransportResult<*>,
) : RuntimeException("${addon.manifest.name}: ${result.userMessage()}")

private fun AddonTransportResult<*>.userMessage(): String = when (this) {
    is AddonTransportResult.HttpFailure -> "source responded with HTTP $statusCode"
    is AddonTransportResult.NetworkFailure -> "could not be reached"
    is AddonTransportResult.MalformedResponse -> "returned invalid data"
    is AddonTransportResult.Success -> "returned an unexpected result"
}

private fun String.cleanStreamPresentation(): String =
    replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "Available stream" }

/** True when Meteor/Torrentio/Comet already labeled the row Instant/⚡/Cached. */
internal fun looksAddonMarkedInstant(text: String): Boolean {
    val value = text.lowercase()
    if (value.isBlank()) return false
    if ('⚡' in text || "⚡" in value) return true
    if (Regex("""\[\s*tb""").containsMatchIn(value)) return true
    return Regex("""\b(?:instant|cached)\b""").containsMatchIn(value)
}

private fun ManagedAddon.isWatchHubSource(): Boolean {
    val haystack = listOf(manifest.id, manifest.name, manifestUrl, configuredManifestUrl.orEmpty())
        .joinToString(" ")
        .lowercase()
    return "watchhub" in haystack
}

private fun ManagedAddon.isAioStreamsSource(): Boolean =
    listOf(manifestUrl, configuredManifestUrl.orEmpty(), manifest.id, manifest.name)
        .any { value ->
            SlugYardCommunitySourcePolicy.isAioStreamsManifest(value) ||
                "aiostreams" in value.lowercase()
        }

private fun addonHost(addon: ManagedAddon): String = runCatching {
    URI(addon.configuredManifestUrl ?: addon.manifestUrl).host
}.getOrNull()?.takeIf(String::isNotBlank) ?: "unknown"

private fun addonUrlMode(url: String): String {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty()
    return when {
        url.contains("%7C", ignoreCase = true) -> "configured-pipe-encoded"
        url.contains('|') -> "configured-pipe-raw"
        path.equals("/manifest.json", ignoreCase = true) -> "bootstrap"
        else -> "configured"
    }
}
