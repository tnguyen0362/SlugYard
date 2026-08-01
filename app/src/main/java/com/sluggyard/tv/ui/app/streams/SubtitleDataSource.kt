package com.sluggyard.tv.ui.app.streams

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

sealed interface SubtitleGroupState {
    data object Loading : SubtitleGroupState
    data object Empty : SubtitleGroupState
    data class Content(val tracks: List<AddonSubtitleTrack>) : SubtitleGroupState
    data class Error(val message: String) : SubtitleGroupState
}

data class SubtitleGroup(
    val addonId: String,
    val addonName: String,
    val state: SubtitleGroupState,
)

/** Loads subtitles independently; consumers may start collecting it before or after stream groups. */
class SubtitleDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
) {
    fun subtitleGroups(
        type: String,
        id: String,
        maxConcurrent: Int = DEFAULT_ADDON_CONCURRENCY,
    ): Flow<List<SubtitleGroup>> = flow {
        val addons = registrySnapshot().enabledAddons.filter { AddonResource.SUBTITLES in it.manifest.resources }
        val groups = addons.map { addon ->
            SubtitleGroup(addon.manifest.id, addon.manifest.name, SubtitleGroupState.Loading)
        }.toMutableList()
        emit(groups.toList())
        val addonsById = addons.associateBy { it.manifest.id }
        val tasks = addons.map { addon ->
            AddonFanoutTask(addon.manifest.id) {
                when (val response = gateway.fetchSubtitles(addon.configuredManifestUrl ?: addon.manifestUrl, type, id)) {
                    is AddonTransportResult.Success -> StremioResponseDecoder.subtitles(response.value)
                    else -> throw SubtitleLoadException(addon, response)
                }
            }
        }
        boundedAddonFanout(tasks, maxConcurrent).collect { result ->
            val addon = addonsById[result.key] ?: return@collect
            val index = groups.indexOfFirst { it.addonId == addon.manifest.id }
            if (index < 0) return@collect
            val state = when (result) {
                is AddonFanoutResult.Success -> if (result.value.isEmpty()) {
                    SubtitleGroupState.Empty
                } else {
                    SubtitleGroupState.Content(result.value)
                }
                is AddonFanoutResult.Failure -> SubtitleGroupState.Error("This addon could not load subtitles")
            }
            groups[index] = groups[index].copy(state = state)
            emit(groups.toList())
        }
    }
}

private class SubtitleLoadException(
    addon: ManagedAddon,
    result: AddonTransportResult<*>,
) : RuntimeException("${addon.manifest.name} subtitle request failed: ${result::class.simpleName}")
