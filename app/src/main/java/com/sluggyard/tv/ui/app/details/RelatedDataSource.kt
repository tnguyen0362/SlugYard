package com.sluggyard.tv.ui.app.details

import com.sluggyard.tv.core.tmdb.TmdbMetadataService
import com.sluggyard.tv.core.tmdb.TmdbService
import com.sluggyard.tv.core.trakt.traktBestPosterUrl
import com.sluggyard.tv.data.local.MoreLikeThisSourcePreference
import com.sluggyard.tv.data.remote.api.TraktApi
import com.sluggyard.tv.data.remote.dto.trakt.TraktMovieDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktShowDto
import com.sluggyard.tv.data.repository.TraktAuthService
import com.sluggyard.tv.data.repository.normalizeContentId
import com.sluggyard.tv.data.repository.toTraktPathId
import com.sluggyard.tv.domain.model.ContentType
import com.sluggyard.tv.domain.model.MetaPreview
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.ui.app.preferLargePosterUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Strip episode / path suffixes so related lookups use the parent title id. */
internal fun relatedParentTitleId(contentId: String): String {
    val raw = contentId.trim()
    return when {
        raw.startsWith("tmdb:", ignoreCase = true) ->
            "tmdb:" + raw.removePrefix("tmdb:").removePrefix("TMDB:").substringBefore(':')
        raw.startsWith("trakt:", ignoreCase = true) ->
            "trakt:" + raw.removePrefix("trakt:").removePrefix("TRAKT:").substringBefore(':')
        raw.startsWith("tt") -> raw.substringBefore(':')
        else -> raw.substringBefore(':').ifBlank { raw }
    }
}

/**
 * True More Like This for rewrite Details — TMDB recommendations and/or Trakt related,
 * with navigation ids resolved toward IMDb (`tt…`) whenever possible for Cinemeta.
 */
class RelatedDataSource(
    private val tmdbMetadata: TmdbMetadataService,
    private val tmdbService: TmdbService,
    private val traktApi: TraktApi,
    private val traktAuth: TraktAuthService,
    private val tmdbSettings: suspend () -> TmdbSettings,
    private val moreLikeThisSource: suspend () -> MoreLikeThisSourcePreference,
) {
    suspend fun load(
        contentType: String,
        contentId: String,
        language: String? = null,
        maxItems: Int = 12,
    ): List<DetailsRelatedPoster> {
        val type = when (ContentType.fromString(contentType)) {
            ContentType.TV, ContentType.SERIES -> ContentType.SERIES
            else -> ContentType.MOVIE
        }
        val source = moreLikeThisSource()
        val settings = tmdbSettings()
        val lang = language?.takeIf { it.isNotBlank() } ?: settings.language.ifBlank { "en" }
        val parentId = relatedParentTitleId(contentId)

        val loaded = when (source) {
            MoreLikeThisSourcePreference.TRAKT ->
                loadTrakt(type, parentId, maxItems).ifEmpty {
                    if (settings.enabled && settings.useMoreLikeThis) {
                        loadTmdb(type, parentId, lang, maxItems)
                    } else {
                        emptyList()
                    }
                }
            MoreLikeThisSourcePreference.TMDB ->
                if (settings.enabled && settings.useMoreLikeThis) {
                    loadTmdb(type, parentId, lang, maxItems).ifEmpty {
                        loadTrakt(type, parentId, maxItems)
                    }
                } else {
                    loadTrakt(type, parentId, maxItems)
                }
        }
        // Trakt related often omits image payloads; fill gaps from TMDB so MLT isn't blank tiles.
        return enrichMissingPosters(loaded)
    }

    private suspend fun enrichMissingPosters(
        items: List<DetailsRelatedPoster>,
    ): List<DetailsRelatedPoster> = coroutineScope {
        items.map { item ->
            async {
                if (!item.imageUrl.isNullOrBlank()) return@async item
                val mediaType = item.contentType ?: "movie"
                val fromImdb = item.id.takeIf { it.startsWith("tt") }?.let { imdb ->
                    tmdbService.fetchImdbImages(imdb, mediaType)
                }
                val poster = fromImdb?.posterUrl ?: fromImdb?.backdropUrl
                if (!poster.isNullOrBlank()) {
                    return@async item.copy(imageUrl = preferLargePosterUrl(poster))
                }
                val tmdbNumeric = item.id.removePrefix("tmdb:").toIntOrNull()
                if (tmdbNumeric != null) {
                    val imdb = tmdbService.tmdbToImdb(tmdbNumeric, mediaType) ?: return@async item
                    val images = tmdbService.fetchImdbImages(imdb, mediaType) ?: return@async item
                    val url = images.posterUrl ?: images.backdropUrl ?: return@async item
                    item.copy(imageUrl = preferLargePosterUrl(url))
                } else {
                    item
                }
            }
        }.awaitAll()
    }

    private suspend fun loadTmdb(
        type: ContentType,
        contentId: String,
        language: String,
        maxItems: Int,
    ): List<DetailsRelatedPoster> {
        val tmdbId = tmdbService.ensureTmdbId(contentId, type.toApiString()) ?: return emptyList()
        val previews = tmdbMetadata.fetchMoreLikeThis(
            tmdbId = tmdbId,
            contentType = type,
            language = language,
            maxItems = maxItems,
        )
        if (previews.isEmpty()) return emptyList()
        return coroutineScope {
            previews.map { preview ->
                async { preview.toRelatedPoster(resolveImdb = true) }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun loadTrakt(
        type: ContentType,
        contentId: String,
        maxItems: Int,
    ): List<DetailsRelatedPoster> {
        val pathId = toTraktPathId(contentId)
        if (pathId.isBlank()) return emptyList()
        return when (type) {
            ContentType.SERIES, ContentType.TV -> {
                val response = traktAuth.executePublicRequest {
                    traktApi.getShowRelated(id = pathId, limit = maxItems)
                } ?: return emptyList()
                if (!response.isSuccessful) return emptyList()
                response.body().orEmpty().mapNotNull { it.toRelatedPoster() }.take(maxItems)
            }
            else -> {
                val response = traktAuth.executePublicRequest {
                    traktApi.getMovieRelated(id = pathId, limit = maxItems)
                } ?: return emptyList()
                if (!response.isSuccessful) return emptyList()
                response.body().orEmpty().mapNotNull { it.toRelatedPoster() }.take(maxItems)
            }
        }
    }

    private suspend fun MetaPreview.toRelatedPoster(resolveImdb: Boolean): DetailsRelatedPoster? {
        val title = name.takeIf { it.isNotBlank() } ?: return null
        val typeLabel = type.toApiString("movie")
        var navId = imdbId?.takeIf { it.startsWith("tt") } ?: id
        if (resolveImdb && !navId.startsWith("tt")) {
            val tmdbNumeric = id.removePrefix("tmdb:").toIntOrNull()
                ?: navId.removePrefix("tmdb:").toIntOrNull()
            if (tmdbNumeric != null) {
                tmdbService.tmdbToImdb(tmdbNumeric, typeLabel)?.let { navId = it }
            }
        }
        if (navId.isBlank()) return null
        return DetailsRelatedPoster(
            id = navId,
            title = title,
            imageUrl = preferLargePosterUrl(rawPosterUrl ?: poster),
            contentType = typeLabel,
            addonId = null,
        )
    }

    private fun TraktMovieDto.toRelatedPoster(): DetailsRelatedPoster? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val navId = normalizeContentId(ids, ids?.imdb)
        if (navId.isBlank()) return null
        return DetailsRelatedPoster(
            id = navId,
            title = title,
            imageUrl = preferLargePosterUrl(images.traktBestPosterUrl()),
            contentType = "movie",
            addonId = null,
        )
    }

    private fun TraktShowDto.toRelatedPoster(): DetailsRelatedPoster? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val navId = normalizeContentId(ids, ids?.imdb)
        if (navId.isBlank()) return null
        return DetailsRelatedPoster(
            id = navId,
            title = title,
            imageUrl = preferLargePosterUrl(images.traktBestPosterUrl()),
            contentType = "series",
            addonId = null,
        )
    }
}
