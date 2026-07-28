package com.sluggyard.tv.ui.rewrite.details

import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioMetadataDecoder
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchState
import com.sluggyard.tv.ui.rewrite.regularSeasonsThenSpecials
import com.sluggyard.tv.ui.util.formatEpisodeReleaseLabel
import com.sluggyard.tv.ui.util.unescapeHtmlEntities

sealed interface RewriteDetailsLoadResult {
    data class Ready(val state: RewriteDetailsState) : RewriteDetailsLoadResult
    data class Unavailable(val message: String) : RewriteDetailsLoadResult
}

/** Loads Details metadata only from the addon that produced the selected Home card. */
class RewriteDetailsDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
    private val watchStateSnapshot: suspend () -> RewriteLibraryWatchState = { RewriteLibraryWatchState() },
) {
    suspend fun load(addonId: String, type: String, id: String): RewriteDetailsLoadResult {
        val localState = watchStateSnapshot()
        val addon = registrySnapshot().enabledAddons.firstOrNull { it.manifest.id == addonId }
            ?: return RewriteDetailsLoadResult.Unavailable("The source addon is no longer enabled")
        if (AddonResource.META !in addon.manifest.resources) {
            return RewriteDetailsLoadResult.Unavailable("This addon does not provide title details")
        }
        val response = gateway.fetchMeta(addon.configuredManifestUrl ?: addon.manifestUrl, type, id)
        val metadata = when (response) {
            is AddonTransportResult.Success -> when (val decoded = StremioMetadataDecoder.decode(response.value)) {
                is AddonTransportResult.Success -> decoded.value
                else -> return RewriteDetailsLoadResult.Unavailable("The addon returned invalid title metadata")
            }
            else -> return RewriteDetailsLoadResult.Unavailable("The addon could not load title metadata")
        }
        val seasons = metadata.episodes
            .filter { it.season != null && it.episode != null }
            .groupBy { it.season!! }
            .toSortedMap()
            .map { (season, episodes) ->
                RewriteDetailsSeason(
                    number = season,
                    episodes = episodes.sortedBy { it.episode }.mapIndexed { index, episode ->
                        RewriteDetailsEpisode(
                            id = episode.id,
                            number = episode.episode!!,
                            displayNumber = index + 1,
                            title = episode.title.unescapeHtmlEntities(),
                            releaseLabel = formatEpisodeReleaseLabel(episode.released) ?: episode.released,
                            thumbnailUrl = episode.thumbnailUrl,
                            watched = episode.id in localState.watchedIds,
                        )
                    },
                    fullyWatched = episodes.all { it.id in localState.watchedIds },
                )
            }
            .regularSeasonsThenSpecials(RewriteDetailsSeason::number)
        return RewriteDetailsLoadResult.Ready(
            RewriteDetailsState(
                id = metadata.id,
                title = metadata.title.unescapeHtmlEntities(),
                backdropUrl = metadata.backgroundUrl,
                posterUrl = metadata.posterUrl,
                metadata = metadata.releaseInfo ?: metadata.type.replaceFirstChar(Char::titlecase),
                description = metadata.description.orEmpty().unescapeHtmlEntities(),
                genres = metadata.genres,
                cast = metadata.cast.map { it.unescapeHtmlEntities() },
                inLibrary = metadata.id in localState.libraryIds,
                isSeries = metadata.type == "series",
                seasons = seasons,
                selectedSeason = seasons.firstOrNull { it.number > 0 }?.number
                    ?: seasons.firstOrNull()?.number,
            ),
        )
    }
}
