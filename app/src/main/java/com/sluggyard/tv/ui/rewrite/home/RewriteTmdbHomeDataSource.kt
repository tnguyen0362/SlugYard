package com.sluggyard.tv.ui.rewrite.home

import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import com.sluggyard.tv.data.remote.api.TmdbApi
import com.sluggyard.tv.data.remote.api.TmdbDiscoverResult
import com.sluggyard.tv.domain.model.TmdbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Curated TMDB genre/theme rails for rewrite Home.
 *
 * Gated by [TmdbSettings.enabled] and [TmdbSettings.modernHomeEnabled]. Catalog keys use
 * addonId `"tmdb"` so they order independently of Stremio manifests.
 */
class RewriteTmdbHomeDataSource(
    private val tmdbApi: TmdbApi,
    private val tmdbSettings: suspend () -> TmdbSettings,
    private val metadataAddonId: suspend () -> String?,
) {
    suspend fun catalogRequests(): List<RewriteCatalogRequest> {
        val settings = tmdbSettings()
        if (!settings.enabled || !settings.modernHomeEnabled) return emptyList()
        if (BuildConfig.TMDB_API_KEY.isBlank()) return emptyList()
        val language = settings.language.ifBlank { "en" }
        val addonId = metadataAddonId()
        return CuratedGenreRails.map { rail ->
            genreRail(
                catalogId = rail.catalogId,
                title = rail.title,
                genreId = rail.genreId,
                contentType = rail.contentType,
                language = language,
                addonId = addonId,
            )
        }
    }

    private fun genreRail(
        catalogId: String,
        title: String,
        genreId: Int,
        contentType: String,
        language: String,
        addonId: String?,
    ): RewriteCatalogRequest = RewriteCatalogRequest(
        key = HomeCatalogKey(addonId = TmdbHomeAddonId, catalogId = catalogId),
        title = title,
        load = {
            withContext(Dispatchers.IO) {
                val results = runCatching {
                    when (contentType) {
                        "series" -> tmdbApi.discoverTv(
                            apiKey = BuildConfig.TMDB_API_KEY,
                            language = language,
                            page = 1,
                            sortBy = "popularity.desc",
                            withGenres = genreId.toString(),
                        ).body()?.results
                        else -> tmdbApi.discoverMovies(
                            apiKey = BuildConfig.TMDB_API_KEY,
                            language = language,
                            page = 1,
                            sortBy = "popularity.desc",
                            withGenres = genreId.toString(),
                        ).body()?.results
                    }
                }.getOrNull().orEmpty()
                results.mapNotNull { it.toHomePoster(contentType, addonId) }.take(MaxTmdbRailItems)
            }
        },
    )
}

/** TMDB movie genre ids — ten curated Home shelves. */
private data class TmdbGenreRail(
    val catalogId: String,
    val title: String,
    val genreId: Int,
    val contentType: String = "movie",
)

private val CuratedGenreRails = listOf(
    TmdbGenreRail("genre_action", "Action", 28),
    TmdbGenreRail("genre_comedy", "Comedy", 35),
    TmdbGenreRail("genre_horror", "Horror", 27),
    TmdbGenreRail("genre_scifi", "Sci-Fi", 878),
    TmdbGenreRail("genre_animation", "Animation", 16),
    TmdbGenreRail("genre_documentary", "Documentary", 99),
    TmdbGenreRail("genre_thriller", "Thriller", 53),
    TmdbGenreRail("genre_romance", "Romance", 10749),
    TmdbGenreRail("genre_family", "Family", 10751),
    TmdbGenreRail("genre_crime", "Crime", 80),
)

internal const val TmdbHomeAddonId = "tmdb"
private const val MaxTmdbRailItems = 24
private const val ImageBase = "https://image.tmdb.org/t/p/"

/**
 * Pins Popular / Featured first, then curated TMDB rails, then remaining addon catalogs
 * (including Your Pick) so TMDB sits alongside pick-style shelves without displacing heroes.
 */
fun mergeHomeCatalogRequests(
    addonRequests: List<RewriteCatalogRequest>,
    tmdbRequests: List<RewriteCatalogRequest>,
): List<RewriteCatalogRequest> {
    if (addonRequests.isEmpty() && tmdbRequests.isEmpty()) return emptyList()
    val popular = mutableListOf<RewriteCatalogRequest>()
    val featured = mutableListOf<RewriteCatalogRequest>()
    val rest = mutableListOf<RewriteCatalogRequest>()
    addonRequests.forEach { request ->
        when {
            request.title.contains("popular", ignoreCase = true) -> popular += request
            request.title.contains("featured", ignoreCase = true) -> featured += request
            else -> rest += request
        }
    }
    return buildList {
        addAll(popular)
        addAll(featured)
        addAll(tmdbRequests)
        addAll(rest)
    }
}

/**
 * Re-applies Popular → Featured → TMDB → rest after [orderedHomeCatalogs] so a saved preference
 * order cannot bury pinned shelves or curated genre rails.
 */
fun pinHomeCatalogDisplayOrder(
    orderedKeys: List<HomeCatalogKey>,
    titleFor: (HomeCatalogKey) -> String?,
): List<HomeCatalogKey> {
    if (orderedKeys.isEmpty()) return emptyList()
    val popular = mutableListOf<HomeCatalogKey>()
    val featured = mutableListOf<HomeCatalogKey>()
    val tmdb = mutableListOf<HomeCatalogKey>()
    val rest = mutableListOf<HomeCatalogKey>()
    orderedKeys.forEach { key ->
        val title = titleFor(key).orEmpty()
        when {
            key.addonId == TmdbHomeAddonId -> tmdb += key
            title.contains("popular", ignoreCase = true) -> popular += key
            title.contains("featured", ignoreCase = true) -> featured += key
            else -> rest += key
        }
    }
    return buildList {
        addAll(popular)
        addAll(featured)
        addAll(tmdb)
        addAll(rest)
    }
}

private fun TmdbDiscoverResult.toHomePoster(
    defaultType: String,
    metadataAddonId: String?,
): RewriteHomePoster? {
    if (id <= 0) return null
    val resolvedTitle = title?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: originalTitle?.takeIf { it.isNotBlank() }
        ?: originalName?.takeIf { it.isNotBlank() }
        ?: return null
    val poster = posterPath?.takeIf { it.isNotBlank() }?.let { "${ImageBase}w500$it" }
        ?: return null
    val backdrop = backdropPath?.takeIf { it.isNotBlank() }?.let { "${ImageBase}w780$it" }
    val type = when (mediaType?.lowercase()) {
        "tv" -> "series"
        "movie" -> "movie"
        else -> defaultType
    }
    return RewriteHomePoster(
        id = "tmdb:$id",
        title = resolvedTitle,
        imageUrl = poster,
        addonId = metadataAddonId,
        contentType = type,
        summary = overview?.takeIf { it.isNotBlank() },
        backdropUrl = backdrop,
    )
}
