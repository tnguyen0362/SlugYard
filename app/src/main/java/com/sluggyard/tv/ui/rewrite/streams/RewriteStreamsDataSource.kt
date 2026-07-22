package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder
import com.sluggyard.tv.core.aggregation.AddonFanoutResult
import com.sluggyard.tv.core.aggregation.AddonFanoutTask
import com.sluggyard.tv.core.aggregation.DEFAULT_ADDON_CONCURRENCY
import com.sluggyard.tv.core.aggregation.boundedAddonFanout
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.streamresolution.StreamCachePolicy
import com.sluggyard.tv.core.streamresolution.DebridProactiveCacheChecker
import com.sluggyard.tv.core.streamresolution.resolveProactiveCacheStates
import com.sluggyard.tv.core.streamresolution.selectProactiveCacheChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException

/** A progressive, per-addon stream result list for the rewritten Streams surface. */
class RewriteStreamsDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
    private val proactiveCacheCheckers: Set<DebridProactiveCacheChecker> = emptySet(),
) {
    fun streamGroups(
        type: String,
        id: String,
        configuredDebrid: DebridService?,
        maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
    ): Flow<List<RewriteStreamGroup>> = flow {
        val addons = registrySnapshot().enabledAddons.filter { AddonResource.STREAM in it.manifest.resources }
        val groups = addons.map { addon ->
            RewriteStreamGroup(addon.manifest.id, addon.manifest.name, RewriteStreamGroupState.Loading)
        }.toMutableList()
        emit(groups.toList())
        val byTask = LinkedHashMap<String, ManagedAddon>()
        val tasks = addons.map { addon ->
            val taskKey = addon.manifest.id
            byTask[taskKey] = addon
            AddonFanoutTask(taskKey) {
                when (val response = gateway.fetchStreams(addon.configuredManifestUrl ?: addon.manifestUrl, type, id)) {
                    is AddonTransportResult.Success -> StremioResponseDecoder.streamItems(response.value)
                    else -> throw RewriteStreamLoadException(addon, response)
                }
            }
        }
        boundedAddonFanout(tasks, maxConcurrent).collect { result ->
            val addon = byTask[result.key] ?: return@collect
            val index = groups.indexOfFirst { it.addonId == addon.manifest.id }
            if (index < 0) return@collect
            val state = when (result) {
                is AddonFanoutResult.Success -> {
                    val streams = result.value.map { stream ->
                        RewriteStreamCandidate(
                            id = stream.id,
                            title = stream.title.cleanStreamPresentation(),
                            // The group already identifies the provider. Keep addon-supplied
                            // release noise and hashes out of the TV presentation.
                            sourceLabel = addon.manifest.name,
                            detailLabel = stream.sourceName
                                ?.cleanStreamPresentation()
                                ?.takeIf { it != stream.title.cleanStreamPresentation() },
                            cacheState = StreamCachePolicy.initialState(
                                isTorrent = stream.infoHash != null,
                                configuredService = configuredDebrid,
                            ),
                            directUrl = stream.directUrl,
                            infoHash = stream.infoHash,
                            fileIndex = stream.fileIndex,
                        )
                    }
                    if (streams.isEmpty()) RewriteStreamGroupState.Empty else RewriteStreamGroupState.Content(streams)
                }
                is AddonFanoutResult.Failure -> RewriteStreamGroupState.Error(
                    (result.cause as? RewriteStreamLoadException)?.message
                        ?: "This source could not load streams",
                )
            }
            groups[index] = groups[index].copy(state = state)
            emit(groups.toList())
            val checker = selectProactiveCacheChecker(configuredDebrid, proactiveCacheCheckers)
            val content = state as? RewriteStreamGroupState.Content ?: return@collect
            val hashes = content.streams.mapNotNull { it.infoHash }.toSet()
            if (checker != null && hashes.isNotEmpty()) {
                val states = try {
                    resolveProactiveCacheStates(checker.service, hashes, checker.check(hashes))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    resolveProactiveCacheStates(checker.service, hashes, emptyMap())
                }
                groups[index] = groups[index].copy(
                    state = RewriteStreamGroupState.Content(content.streams.map { candidate ->
                        candidate.infoHash?.let { hash -> candidate.copy(cacheState = states[hash] ?: candidate.cacheState) } ?: candidate
                    }),
                )
                emit(groups.toList())
            }
        }
    }
}

private class RewriteStreamLoadException(
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
