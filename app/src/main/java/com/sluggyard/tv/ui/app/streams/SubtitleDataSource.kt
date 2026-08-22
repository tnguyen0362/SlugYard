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
import com.sluggyard.tv.ui.app.isSeriesType
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
    companion object {
        /** Per-addon result ceiling after language + format ranking. */
        private const val MAX_SUBTITLES_PER_ADDON = 100
    }

    fun subtitleGroups(
        type: String,
        id: String,
        langs: List<String>? = null,
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
                when (val response = gateway.fetchSubtitles(
                    addon.configuredManifestUrl ?: addon.manifestUrl,
                    type,
                    id,
                    langs?.joinToString(","),
                )) {
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
                    SubtitleGroupState.Content(rankSubtitles(result.value, langs).take(MAX_SUBTITLES_PER_ADDON))
                }
                is AddonFanoutResult.Failure -> SubtitleGroupState.Error("This addon could not load subtitles")
            }
            groups[index] = groups[index].copy(state = state)
            emit(groups.toList())
        }
    }
}

/**
 * Belts-and-braces language filter for addons that ignore the langs query, then
 * best-quality-first ordering (ASS > SRT > VTT > other) so the per-addon cap
 * keeps the useful rows.
 */
private fun rankSubtitles(tracks: List<AddonSubtitleTrack>, langs: List<String>?): List<AddonSubtitleTrack> {
    val targets = langs?.map { it.trim().lowercase() }?.toSet().orEmpty()
    val filtered = if (targets.isEmpty()) {
        tracks
    } else {
        tracks.filter { track -> track.language?.trim()?.lowercase() in targets }
    }
    return filtered.sortedWith(compareByDescending { formatPriority(it.format) })
}

private fun formatPriority(format: String?): Int = when (format?.trim()?.lowercase()) {
    "ass", "ssa" -> 5
    "srt", "subrip" -> 4
    "vtt", "webvtt" -> 3
    "mks" -> 2
    "pgs" -> 0
    else -> 1
}

/** Stremio subtitle addons (OpenSubtitles v3) expect `{imdb}:{season}:{episode}` for series. */
internal fun stremioSubtitleLookupId(
    contentType: String,
    contentId: String,
    season: Int?,
    episode: Int?,
): String {
    if (contentId.contains(':')) return contentId
    if (isSeriesType(contentType) && season != null && episode != null) {
        return "$contentId:$season:$episode"
    }
    return contentId
}

private class SubtitleLoadException(
    addon: ManagedAddon,
    result: AddonTransportResult<*>,
) : RuntimeException("${addon.manifest.name} subtitle request failed: ${result::class.simpleName}")
