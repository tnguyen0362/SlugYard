package com.sluggyard.tv.ui.app.details

import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioMetadataDecoder
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder
import com.sluggyard.tv.ui.app.data.LibraryWatchState
import com.sluggyard.tv.ui.app.regularSeasonsThenSpecials
import com.sluggyard.tv.ui.util.formatEpisodeReleaseLabel
import com.sluggyard.tv.ui.util.unescapeHtmlEntities

sealed interface DetailsLoadResult {
    data class Ready(val state: DetailsState) : DetailsLoadResult
    data class Unavailable(val message: String) : DetailsLoadResult
}

/**
 * Stremio meta addons (Cinemeta, etc.) expect IMDb `tt…` ids. TMDB Home rails navigate with
 * `tmdb:{numeric}` — resolve those before the meta fan-out or every candidate fails with
 * "could not load title metadata".
 */
internal suspend fun resolveMetaContentId(
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
class DetailsDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
    private val watchStateSnapshot: suspend () -> LibraryWatchState = { LibraryWatchState() },
    private val resolveMetaContentId: suspend (type: String, id: String) -> String = { _, id -> id },
    /**
     * One-shot (season, episode) completion keys for a show. Must not do per-episode network
     * or Flow collection — that froze Details on long series ("Getting title details…").
     */
    private val completedEpisodeKeys: suspend (showId: String) -> Set<Pair<Int, Int>> = { emptySet() },
) {
    suspend fun load(addonId: String, type: String, id: String): DetailsLoadResult {
        return kotlinx.coroutines.withTimeoutOrNull(DetailsLoadBudgetMs) {
            loadWithinBudget(addonId, type, id)
        } ?: DetailsLoadResult.Unavailable(
            "Title details took too long to load. Check your connection and try again.",
        )
    }

    private suspend fun loadWithinBudget(addonId: String, type: String, id: String): DetailsLoadResult {
        val localState = watchStateSnapshot()
        val registry = registrySnapshot()
        val metaId = kotlinx.coroutines.withTimeoutOrNull(MetaIdResolveBudgetMs) {
            resolveMetaContentId(type, id)
        } ?: id
        val preferred = registry.enabledAddons.firstOrNull { it.manifest?.id == addonId }
        val cinemeta = registry.enabledAddons.filter { addon ->
            val manifest = addon.manifest ?: return@filter false
            AddonResource.META in manifest.resources &&
                (
                    manifest.id.contains("cinemeta", ignoreCase = true) ||
                        manifest.name.contains("cinemeta", ignoreCase = true) ||
                        addon.manifestUrl.contains("cinemeta", ignoreCase = true)
                    )
        }
        // Preferred origin + Cinemeta only. Walking every META addon (PlayFlix, etc.) with a
        // 60s OkHttp read timeout made Details look permanently stuck.
        val preferredId = preferred?.manifest?.id
        val candidates = buildList {
            preferred?.takeIf { AddonResource.META in (it.manifest?.resources.orEmpty()) }?.let(::add)
            addAll(cinemeta.filterNot { it.manifest?.id == preferredId })
        }.distinctBy { it.manifestUrl }
        if (candidates.isEmpty()) {
            return DetailsLoadResult.Unavailable("No metadata source is available")
        }
        if (metaId.startsWith("tmdb:", ignoreCase = true)) {
            return DetailsLoadResult.Unavailable(
                "Could not resolve this title to a playable ID. Try again or open it from a catalog shelf.",
            )
        }
        var lastFailure = "The addon could not load title metadata"
        for (addon in candidates) {
            val resources = addon.manifest?.resources.orEmpty()
            if (AddonResource.META !in resources) continue
            val response = kotlinx.coroutines.withTimeoutOrNull(MetaFetchBudgetMs) {
                gateway.fetchMeta(addon.configuredManifestUrl ?: addon.manifestUrl, type, metaId)
            } ?: run {
                lastFailure = "The addon timed out loading title metadata"
                continue
            }
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
            val completedKeys = kotlinx.coroutines.withTimeoutOrNull(WatchedLookupBudgetMs) {
                completedEpisodeKeys(metadata.id)
            }.orEmpty()
            val seasons = metadata.episodes
                .filter { it.season != null && it.episode != null }
                .groupBy { it.season!! }
                .toSortedMap()
                .map { (season, episodes) ->
                    DetailsSeason(
                        number = season,
                        episodes = episodes.sortedBy { it.episode }.map { episode ->
                            val seasonNumber = season
                            val episodeNumber = episode.episode!!
                            val episodeId = episode.id
                            val watched = episodeId in localState.watchedIds ||
                                (seasonNumber to episodeNumber) in completedKeys
                            DetailsEpisode(
                                id = episodeId,
                                number = episodeNumber,
                                // Absolute episode number from meta — not list index.
                                // Index+1 mislabeled mid-season eps (e.g. S2E8 as "Episode 20").
                                displayNumber = episodeNumber,
                                title = episode.title.unescapeHtmlEntities(),
                                releaseLabel = formatEpisodeReleaseLabel(episode.released) ?: episode.released,
                                thumbnailUrl = episode.thumbnailUrl,
                                watched = watched,
                                description = episode.description?.unescapeHtmlEntities()?.takeIf(String::isNotBlank),
                            )
                        },
                        fullyWatched = episodes.all { ep ->
                            val episodeNumber = ep.episode!!
                            ep.id in localState.watchedIds ||
                                (season to episodeNumber) in completedKeys
                        },
                    )
                }
                .regularSeasonsThenSpecials(DetailsSeason::number)
            // WatchHub "where to watch" must not gate the Details chrome — bound it tightly.
            val availability = kotlinx.coroutines.withTimeoutOrNull(WatchHubBudgetMs) {
                loadWatchHubAvailability(registry, type, metaId)
            }.orEmpty()
            return DetailsLoadResult.Ready(
                DetailsState(
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
                    availability = availability,
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
        return DetailsLoadResult.Unavailable(lastFailure)
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

    private companion object {
        const val DetailsLoadBudgetMs = 12_000L
        const val MetaIdResolveBudgetMs = 4_000L
        const val MetaFetchBudgetMs = 8_000L
        const val WatchHubBudgetMs = 2_500L
        const val WatchedLookupBudgetMs = 2_000L
    }
}
