package com.sluggyard.tv.ui.rewrite.details

import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioMetadataDecoder
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchState
import com.sluggyard.tv.ui.rewrite.regularSeasonsThenSpecials
import com.sluggyard.tv.ui.util.formatEpisodeReleaseLabel
import com.sluggyard.tv.ui.util.unescapeHtmlEntities

sealed interface RewriteDetailsLoadResult {
    data class Ready(val state: RewriteDetailsState) : RewriteDetailsLoadResult
    data class Unavailable(val message: String) : RewriteDetailsLoadResult
}

/**
 * Stremio meta addons (Cinemeta, etc.) expect IMDb `tt…` ids. TMDB Home rails navigate with
 * `tmdb:{numeric}` — resolve those before the meta fan-out or every candidate fails with
 * "could not load title metadata".
 */
internal suspend fun resolveRewriteMetaContentId(
    type: String,
    id: String,
    tmdbToImdb: suspend (tmdbId: Int, mediaType: String) -> String?,
): String {
    if (!id.startsWith("tmdb:", ignoreCase = true)) return id
    val numeric = id.removePrefix("tmdb:")
        .removePrefix("TMDB:")
        .substringBefore(':')
        .toIntOrNull()
        ?: return id
    val mediaType = when (type.trim().lowercase()) {
        "series", "tv", "show", "shows", "tvshow", "tvshows" -> "tv"
        else -> "movie"
    }
    return tmdbToImdb(numeric, mediaType)
        ?.trim()
        ?.takeIf { it.startsWith("tt", ignoreCase = true) }
        ?: id
}

/** Loads Details metadata from the origin addon, falling back to a meta catalog (Cinemeta). */
class RewriteDetailsDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
    private val watchStateSnapshot: suspend () -> RewriteLibraryWatchState = { RewriteLibraryWatchState() },
    private val resolveMetaContentId: suspend (type: String, id: String) -> String = { _, id -> id },
) {
    suspend fun load(addonId: String, type: String, id: String): RewriteDetailsLoadResult {
        val localState = watchStateSnapshot()
        val registry = registrySnapshot()
        val metaId = resolveMetaContentId(type, id)
        val preferred = registry.enabledAddons.firstOrNull { it.manifest?.id == addonId }
        val fallbacks = registry.enabledAddons.filter { addon ->
            val manifest = addon.manifest ?: return@filter false
            AddonResource.META in manifest.resources &&
                (manifest.id.contains("cinemeta", ignoreCase = true) ||
                    manifest.name.contains("cinemeta", ignoreCase = true) ||
                    addon.manifestUrl.contains("cinemeta", ignoreCase = true))
        }
        val preferredId = preferred?.manifest?.id
        val candidates = buildList {
            preferred?.let(::add)
            addAll(fallbacks.filterNot { it.manifest?.id == preferredId })
            addAll(
                registry.enabledAddons.filter {
                    val mid = it.manifest?.id
                    AddonResource.META in (it.manifest?.resources.orEmpty()) &&
                        mid != preferredId &&
                        fallbacks.none { fb -> fb.manifest?.id == mid }
                },
            )
        }
        if (candidates.isEmpty()) {
            return RewriteDetailsLoadResult.Unavailable("No metadata source is available")
        }
        if (metaId.startsWith("tmdb:", ignoreCase = true)) {
            return RewriteDetailsLoadResult.Unavailable(
                "Could not resolve this title to a playable ID. Try again or open it from a catalog shelf.",
            )
        }
        var lastFailure = "The addon could not load title metadata"
        for (addon in candidates) {
            val resources = addon.manifest?.resources.orEmpty()
            if (AddonResource.META !in resources) continue
            val response = gateway.fetchMeta(addon.configuredManifestUrl ?: addon.manifestUrl, type, metaId)
            val metadata = when (response) {
                is AddonTransportResult.Success -> when (val decoded = StremioMetadataDecoder.decode(response.value)) {
                    is AddonTransportResult.Success -> decoded.value
                    else -> {
                        lastFailure = "The addon returned invalid title metadata"
                        continue
                    }
                }
                is AddonTransportResult.HttpFailure -> {
                    lastFailure = "The addon could not load title metadata (HTTP ${response.statusCode})"
                    continue
                }
                is AddonTransportResult.NetworkFailure -> {
                    lastFailure = "The addon could not load title metadata (network)"
                    continue
                }
                else -> {
                    lastFailure = "The addon could not load title metadata"
                    continue
                }
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
                                description = episode.description?.unescapeHtmlEntities()?.takeIf(String::isNotBlank),
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
                    metadata = listOfNotNull(
                        metadata.releaseInfo,
                        metadata.runtime?.takeIf(String::isNotBlank),
                        metadata.imdbRating?.takeIf(String::isNotBlank)?.let { "★ $it IMDb" },
                        metadata.type.replaceFirstChar(Char::titlecase),
                    ).joinToString("  ·  ").ifBlank {
                        metadata.type.replaceFirstChar(Char::titlecase)
                    },
                    description = metadata.description.orEmpty().unescapeHtmlEntities(),
                    genres = metadata.genres,
                    cast = metadata.cast.map { it.unescapeHtmlEntities() },
                    directors = metadata.directors.map { it.unescapeHtmlEntities() },
                    imdbRating = metadata.imdbRating?.takeIf(String::isNotBlank),
                    availability = loadWatchHubAvailability(registry, type, id),
                    inLibrary = metadata.id in localState.libraryIds,
                    isSeries = metadata.type == "series",
                    seasons = seasons,
                    selectedSeason = seasons.firstOrNull { it.number > 0 }?.number
                        ?: seasons.firstOrNull()?.number,
                    contentLanguage = metadata.language,
                    country = metadata.country,
                ),
            )
        }
        return RewriteDetailsLoadResult.Unavailable(lastFailure)
    }

    private suspend fun loadWatchHubAvailability(
        registry: AddonRegistryState,
        type: String,
        id: String,
    ): List<String> {
        val watchHub = registry.enabledAddons.firstOrNull { addon ->
            SlugYardCommunitySourcePolicy.isWatchHubManifest(addon.manifestUrl) ||
                "watchhub" in listOf(
                    addon.manifest.id,
                    addon.manifest.name,
                    addon.manifestUrl,
                ).joinToString(" ").lowercase()
        } ?: return emptyList()
        val response = gateway.fetchStreams(
            watchHub.configuredManifestUrl ?: watchHub.manifestUrl,
            type,
            id,
        )
        val streams = when (response) {
            is AddonTransportResult.Success -> StremioResponseDecoder.streamItems(response.value)
            else -> return emptyList()
        }
        return streams
            .mapNotNull { stream ->
                stream.sourceName?.takeIf(String::isNotBlank)
                    ?: stream.title.takeIf { it.isNotBlank() && it != "Available stream" }
            }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .take(8)
    }
}
