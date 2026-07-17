package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.supportsExtra
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.lifecycle.viewModelScope

/**
 * Curated, first-party programming rails for the modern home.  These are
 * deliberately discover queries rather than relabelled addon catalogs: each
 * row is a distinct, paginated TMDB result set and is still useful when the
 * metadata addon exposes only Popular/New.
 */
internal data class HomeProgrammingRail(
    val catalogId: String,
    val title: String,
    val source: TmdbCollectionSource,
    val fallbackGenre: String
)

internal const val HOME_PROGRAMMING_ADDON_ID = "slugyard-programming"

internal val homeProgrammingRails = listOf(
    HomeProgrammingRail(
        catalogId = "trending_movies",
        title = "Trending Movies",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Trending Movies",
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(voteCountGte = 80)
        ),
        fallbackGenre = "Action"
    ),
    HomeProgrammingRail(
        catalogId = "trending_series",
        title = "Trending Series",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Trending Series",
            mediaType = TmdbCollectionMediaType.TV,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(voteCountGte = 60)
        ),
        fallbackGenre = "Drama"
    ),
    HomeProgrammingRail(
        catalogId = "action_movies",
        title = "Action Movies",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Action Movies",
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "28", voteCountGte = 80)
        ),
        fallbackGenre = "Action"
    ),
    HomeProgrammingRail(
        catalogId = "crime_thrillers",
        title = "Crime & Thrillers",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Crime & Thrillers",
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "80,53", voteCountGte = 60)
        ),
        fallbackGenre = "Crime"
    ),
    HomeProgrammingRail(
        catalogId = "comedy_movies",
        title = "Comedy Movies",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Comedy Movies",
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "35", voteCountGte = 50)
        ),
        fallbackGenre = "Comedy"
    ),
    HomeProgrammingRail(
        catalogId = "animation_anime",
        title = "Animation & Anime",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Animation & Anime",
            mediaType = TmdbCollectionMediaType.TV,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "16", voteCountGte = 30)
        ),
        fallbackGenre = "Animation"
    ),
    HomeProgrammingRail(
        catalogId = "drama_series",
        title = "Drama Series",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Drama Series",
            mediaType = TmdbCollectionMediaType.TV,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "18", voteCountGte = 80)
        ),
        fallbackGenre = "Drama"
    ),
    HomeProgrammingRail(
        catalogId = "sci_fi_fantasy",
        title = "Sci-Fi & Fantasy",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Sci-Fi & Fantasy",
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "878,14", voteCountGte = 50)
        ),
        fallbackGenre = "Science Fiction"
    ),
    HomeProgrammingRail(
        catalogId = "horror_movies",
        title = "Horror Movies",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Horror Movies",
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "27", voteCountGte = 30)
        ),
        fallbackGenre = "Horror"
    ),
    HomeProgrammingRail(
        catalogId = "family_movies",
        title = "Family Night",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Family Night",
            mediaType = TmdbCollectionMediaType.MOVIE,
            sortBy = TmdbCollectionSort.POPULAR_DESC.value,
            filters = TmdbCollectionFilters(withGenres = "10751", voteCountGte = 30)
        ),
        fallbackGenre = "Family"
    ),
    HomeProgrammingRail(
        catalogId = "top_rated_series",
        title = "Top Rated Series",
        source = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Top Rated Series",
            mediaType = TmdbCollectionMediaType.TV,
            sortBy = TmdbCollectionSort.VOTE_AVERAGE_DESC.value,
            filters = TmdbCollectionFilters(voteAverageGte = 7.2, voteCountGte = 150)
        ),
        fallbackGenre = "Drama"
    )
)

internal fun findHomeProgrammingRail(catalogId: String): HomeProgrammingRail? =
    homeProgrammingRails.firstOrNull { it.catalogId == catalogId }

/** Loads first pages without delaying the addon-backed hero and initial rails. */
internal fun HomeViewModel.loadHomeProgrammingRails(
    force: Boolean = false
) {
    programmingRailsJob?.cancel()
    programmingRailsJob = viewModelScope.launch {
        val settings = tmdbSettingsDataStore.settings.first()
        if (!settings.modernHomeEnabled) {
            removeHomeProgrammingRails()
            return@launch
        }

        // A commercial build can use TMDB Discover for fully curated rows. The
        // debug build, however, intentionally has no bundled key. Falling back
        // to a metadata addon's native genre filter keeps the Netflix-style
        // programming visible instead of silently falling back to generic
        // Popular/New rows only.
        if (!settings.enabled || !settings.useCollections || tmdbService.apiKey().isBlank()) {
            loadAddonGenreFallbackRails(force)
            return@launch
        }

        val signature = "${catalogLoadGeneration}:${settings.language}"
        if (!force && signature == programmingRailsSignature &&
            homeProgrammingRails.all { readCatalogRow(catalogKey(HOME_PROGRAMMING_ADDON_ID, it.source.mediaType.value, it.catalogId)) != null }
        ) {
            return@launch
        }
        programmingRailsSignature = signature
        val semaphore = Semaphore(2)
        homeProgrammingRails.forEach { rail ->
            launch {
                semaphore.withPermit {
                    tmdbCollectionSourceResolver.resolve(rail.source).collect { result ->
                        if (result !is NetworkResult.Success) return@collect
                        val key = catalogKey(HOME_PROGRAMMING_ADDON_ID, rail.source.mediaType.value, rail.catalogId)
                        replaceCatalogRow(key, result.data.asHomeProgrammingRow(rail))
                        scheduleUpdateCatalogRows()
                    }
                }
            }
        }
    }
}

private suspend fun HomeViewModel.loadAddonGenreFallbackRails(force: Boolean) {
    val genreCatalogs = homeProgrammingRails.mapNotNull { rail ->
        findGenreCatalog(addonsCache, rail.source.mediaType.value)?.let { (addon, catalog) ->
            rail to (addon to catalog)
        }
    }
    if (genreCatalogs.isEmpty()) {
        removeHomeProgrammingRails()
        return
    }

    val signature = buildString {
        append("addon-genre:").append(catalogLoadGeneration)
        genreCatalogs.forEach { (rail, provider) ->
            append('|').append(rail.catalogId)
            append(':').append(provider.first.id)
            append(':').append(provider.second.id)
        }
    }
    if (!force && signature == programmingRailsSignature &&
        homeProgrammingRails.all { rail ->
            readCatalogRow(catalogKey(HOME_PROGRAMMING_ADDON_ID, rail.source.mediaType.value, rail.catalogId)) != null
        }
    ) {
        return
    }
    programmingRailsSignature = signature

    val semaphore = Semaphore(2)
    coroutineScope {
        genreCatalogs.forEach { (rail, provider) ->
            val (addon, catalog) = provider
            launch {
            semaphore.withPermit {
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = rail.title,
                    type = catalog.apiType,
                    extraArgs = mapOf("genre" to rail.fallbackGenre),
                    supportsSkip = false
                ).collect { result ->
                    if (result !is NetworkResult.Success) return@collect
                    val key = catalogKey(HOME_PROGRAMMING_ADDON_ID, rail.source.mediaType.value, rail.catalogId)
                    replaceCatalogRow(key, result.data.asHomeProgrammingRow(rail))
                    scheduleUpdateCatalogRows()
                }
            }
        }
        }
    }
}

private fun findGenreCatalog(
    addons: List<Addon>,
    apiType: String
): Pair<Addon, CatalogDescriptor>? {
    val addonApiType = when (apiType.lowercase()) {
        "tv", "show" -> "series"
        else -> apiType
    }
    return addons.asSequence()
        .flatMap { addon ->
            addon.catalogs.asSequence()
                .filter { catalog ->
                    catalog.apiType.equals(addonApiType, ignoreCase = true) && catalog.supportsExtra("genre")
                }
                .map { catalog -> addon to catalog }
        }
        // Prefer Cinemeta when available because its genre vocabulary is
        // stable and it backs the default home catalog already.
        .sortedBy { (addon, _) -> if (addon.id.contains("cinemeta", ignoreCase = true)) 0 else 1 }
        .firstOrNull()
}

internal fun HomeViewModel.removeHomeProgrammingRails() {
    synchronized(catalogStateLock) {
        homeProgrammingRails.forEach { rail ->
            removeCatalogRow(catalogKey(HOME_PROGRAMMING_ADDON_ID, rail.source.mediaType.value, rail.catalogId))
        }
    }
    programmingRailsSignature = null
    scheduleUpdateCatalogRows()
}

internal fun HomeViewModel.loadMoreHomeProgrammingRail(
    rail: HomeProgrammingRail,
    currentRow: CatalogRow
) {
    val key = catalogKey(HOME_PROGRAMMING_ADDON_ID, rail.source.mediaType.value, rail.catalogId)
    updateCatalogRow(key) { it.copy(isLoading = true) }
    _loadingCatalogs.update { it + key }
    viewModelScope.launch {
        tmdbCollectionSourceResolver.resolve(rail.source, page = currentRow.currentPage + 1).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    updateCatalogRow(key) { existing ->
                        existing.mergeCatalogPage(result.data.asHomeProgrammingRow(rail))
                    }
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                is NetworkResult.Error -> {
                    updateCatalogRow(key) { it.copy(isLoading = false) }
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

private fun CatalogRow.asHomeProgrammingRow(rail: HomeProgrammingRail): CatalogRow = copy(
    addonId = HOME_PROGRAMMING_ADDON_ID,
    addonName = "SlugYard",
    addonBaseUrl = "",
    catalogId = rail.catalogId,
    catalogName = rail.title,
    supportsSkip = true,
    skipStep = 20
)
