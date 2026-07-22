package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonSubtitleTrack
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder
import com.sluggyard.tv.core.aggregation.AddonFanoutResult
import com.sluggyard.tv.core.aggregation.AddonFanoutTask
import com.sluggyard.tv.core.aggregation.DEFAULT_ADDON_CONCURRENCY
import com.sluggyard.tv.core.aggregation.boundedAddonFanout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

sealed interface RewriteSubtitleGroupState {
    data object Loading : RewriteSubtitleGroupState
    data object Empty : RewriteSubtitleGroupState
    data class Content(val tracks: List<AddonSubtitleTrack>) : RewriteSubtitleGroupState
    data class Error(val message: String) : RewriteSubtitleGroupState
}

data class RewriteSubtitleGroup(
    val addonId: String,
    val addonName: String,
    val state: RewriteSubtitleGroupState,
)

/** Loads subtitles independently; consumers may start collecting it before or after stream groups. */
class RewriteSubtitleDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
) {
    fun subtitleGroups(
        type: String,
        id: String,
        maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
    ): Flow<List<RewriteSubtitleGroup>> = flow {
        val addons = registrySnapshot().enabledAddons.filter { AddonResource.SUBTITLES in it.manifest.resources }
        val groups = addons.map { addon ->
            RewriteSubtitleGroup(addon.manifest.id, addon.manifest.name, RewriteSubtitleGroupState.Loading)
        }.toMutableList()
        emit(groups.toList())
        val addonsById = addons.associateBy { it.manifest.id }
        val tasks = addons.map { addon ->
            AddonFanoutTask(addon.manifest.id) {
                when (val response = gateway.fetchSubtitles(addon.configuredManifestUrl ?: addon.manifestUrl, type, id)) {
                    is AddonTransportResult.Success -> StremioResponseDecoder.subtitles(response.value)
                    else -> throw RewriteSubtitleLoadException(addon, response)
                }
            }
        }
        boundedAddonFanout(tasks, maxConcurrent).collect { result ->
            val addon = addonsById[result.key] ?: return@collect
            val index = groups.indexOfFirst { it.addonId == addon.manifest.id }
            if (index < 0) return@collect
            val state = when (result) {
                is AddonFanoutResult.Success -> if (result.value.isEmpty()) {
                    RewriteSubtitleGroupState.Empty
                } else {
                    RewriteSubtitleGroupState.Content(result.value)
                }
                is AddonFanoutResult.Failure -> RewriteSubtitleGroupState.Error("This addon could not load subtitles")
            }
            groups[index] = groups[index].copy(state = state)
            emit(groups.toList())
        }
    }
}

private class RewriteSubtitleLoadException(
    addon: ManagedAddon,
    result: AddonTransportResult<*>,
) : RuntimeException("${addon.manifest.name} subtitle request failed: ${result::class.simpleName}")
