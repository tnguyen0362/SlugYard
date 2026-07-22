package com.sluggyard.tv.core.tmdb

import android.util.Log
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.data.remote.api.TmdbApi
import com.sluggyard.tv.data.remote.api.TmdbAggregateCreditsResponse
import com.sluggyard.tv.data.remote.api.TmdbCastMember
import com.sluggyard.tv.data.remote.api.TmdbCreditsResponse
import com.sluggyard.tv.data.remote.api.TmdbCrewMember
import com.sluggyard.tv.data.remote.api.TmdbDiscoverResult
import com.sluggyard.tv.data.remote.api.TmdbEpisode
import com.sluggyard.tv.data.remote.api.TmdbImage
import com.sluggyard.tv.data.remote.api.TmdbMovieReleaseDateCountry
import com.sluggyard.tv.data.remote.api.TmdbPersonCreditCast
import com.sluggyard.tv.data.remote.api.TmdbPersonCreditCrew
import com.sluggyard.tv.data.remote.api.TmdbRecommendationResult
import com.sluggyard.tv.data.remote.api.TmdbTvContentRatingItem
import com.sluggyard.tv.data.remote.api.TmdbVideoResult
import com.sluggyard.tv.domain.model.ContentType
import com.sluggyard.tv.domain.model.MetaCastMember
import com.sluggyard.tv.domain.model.MetaCompany
import com.sluggyard.tv.domain.model.MetaPreview
import com.sluggyard.tv.domain.model.MetaTrailer
import com.sluggyard.tv.domain.model.PersonDetail
import com.sluggyard.tv.domain.model.PosterShape
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val TAG = "TmdbMetadataService"

/** Test hook for overriding the TMDB api key without touching BuildConfig. */
internal var tmdbApiKeyOverride: String? = null

private fun tmdbApiKey(): String = tmdbApiKeyOverride ?: BuildConfig.TMDB_API_KEY

private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"
private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"
private const val ENTITY_RAIL_MAX_ITEMS = 20
private const val TOP_RATED_VOTE_COUNT_FLOOR = 200

/**
 * Enriches Stremio addon metadata with TMDB data (posters, backdrops, cast, ratings,
 * trailers, etc.). All network calls are dispatched off the main thread and deduplicated
 * through in-flight [CompletableDeferred] registries so concurrent callers share a single
 * request. Results are memoized in [ConcurrentHashMap]s keyed by id + language.
 */
@Singleton
class TmdbMetadataService(
    private val tmdbApi: TmdbApi,
    private val ioDispatcher: CoroutineDispatcher
) {
    @Inject
    constructor(tmdbApi: TmdbApi) : this(tmdbApi, Dispatchers.IO)

    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()
    private val episodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
    private val enrichmentInFlight = ConcurrentHashMap<String, CompletableDeferred<TmdbEnrichment?>>()
    private val episodeInFlight = ConcurrentHashMap<String, CompletableDeferred<Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>>()
    private val personCache = ConcurrentHashMap<String, PersonDetail>()
    private val moreLikeThisCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val collectionCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val entityHeaderCache = ConcurrentHashMap<String, TmdbEntityHeader>()
    private val entityRailCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val entityBrowseCache = ConcurrentHashMap<String, TmdbEntityBrowseData>()

    // ---------------------------------------------------------------------
    // Enrichment
    // ---------------------------------------------------------------------

    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbEnrichment? = withContext(ioDispatcher) {
        if (tmdbApiKey().isBlank()) return@withContext null
        val lang = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.name}:$lang"

        enrichmentCache[cacheKey]?.let { return@withContext it }
        enrichmentInFlight[cacheKey]?.let { return@withContext it.await() }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext null
        val pending = CompletableDeferred<TmdbEnrichment?>()
        enrichmentInFlight.putIfAbsent(cacheKey, pending)?.let { existing ->
            return@withContext existing.await()
        }

        val tmdbType = contentType.toTmdbType()

        try {
            val imageLang = buildImageLanguageParam(lang)

            val bundle = coroutineScope {
                val detailsAsync = async {
                    tmdbApi.fetchDetails(numericId, tmdbType, lang)
                }
                val creditsAsync = async {
                    tmdbApi.fetchCredits(numericId, tmdbType, lang)
                }
                val imagesAsync = async {
                    when (tmdbType) {
                        "tv" -> tmdbApi.getTvImages(numericId, tmdbApiKey(), imageLang).body()
                        else -> tmdbApi.getMovieImages(numericId, tmdbApiKey(), imageLang).body()
                    }
                }
                val ageRatingAsync = async {
                    when (tmdbType) {
                        "tv" -> {
                            val ratings = tmdbApi.getTvContentRatings(numericId, tmdbApiKey()).body()?.results.orEmpty()
                            selectTvAgeRating(ratings, lang)
                        }
                        else -> {
                            val releases = tmdbApi.getMovieReleaseDates(numericId, tmdbApiKey()).body()?.results.orEmpty()
                            selectMovieAgeRating(releases, lang)
                        }
                    }
                }
                val altTitlesAsync = async {
                    runCatching {
                        val resp = when (tmdbType) {
                            "tv" -> tmdbApi.getTvAlternativeTitles(numericId, tmdbApiKey()).body()
                            else -> tmdbApi.getMovieAlternativeTitles(numericId, tmdbApiKey()).body()
                        }
                        (resp?.movieTitles ?: resp?.tvTitles).orEmpty()
                            .mapNotNull { it.title?.trim()?.takeIf(String::isNotBlank) }
                    }.getOrDefault(emptyList())
                }
                EnrichmentBundle(
                    detailsAsync.await(),
                    creditsAsync.await(),
                    imagesAsync.await(),
                    ageRatingAsync.await(),
                    altTitlesAsync.await()
                )
            }
            val details = bundle.details
            val credits = bundle.credits
            val images = bundle.images
            val ageRating = bundle.ageRating
            val altTitles = bundle.altTitles

            val genres = details?.genres
                ?.mapNotNull { it.name.trim().takeIf(String::isNotBlank) }
                ?: emptyList()
            val trailers = fetchTmdbTrailers(numericId, tmdbType, lang)
            val description = details?.overview?.takeIf { it.isNotBlank() }
            val status = details?.status?.trim()?.takeIf { it.isNotBlank() }
            val releaseInfo = if (tmdbType == "tv") {
                details?.firstAirDate.yearPart()?.let { start ->
                    buildShowYearRange(start, details?.lastAirDate.yearPart(), status)
                }
            } else {
                details?.releaseDate.yearPart()
            }
            val rating = details?.voteAverage
            val runtime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
            val countries = details?.productionCountries
                ?.mapNotNull { it.iso31661?.trim()?.uppercase()?.takeIf(String::isNotBlank) }
                ?.takeIf { it.isNotEmpty() }
                ?: details?.originCountry?.takeIf { it.isNotEmpty() }
            val originalLanguage = details?.originalLanguage?.takeIf { it.isNotBlank() }
            val rawLocalizedTitle = (details?.title ?: details?.name)?.takeIf { it.isNotBlank() }
            val originalTitle = (details?.originalTitle ?: details?.originalName)
                ?.trim()?.takeIf { it.isNotBlank() }

            // If TMDB echoes the original title back (no translation exists for the
            // requested language), drop it so the caller keeps the addon-provided title.
            val localizedTitle = if (
                rawLocalizedTitle != null &&
                originalTitle != null &&
                rawLocalizedTitle == originalTitle &&
                !lang.startsWith("en") &&
                originalLanguage != null &&
                !lang.startsWith(originalLanguage)
            ) null else rawLocalizedTitle

            val productionCompanies = details?.productionCompanies.orEmpty().mapNotNull { company ->
                val name = company.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCompany(
                    name = name,
                    logo = buildImageUrl(company.logoPath, "w300"),
                    tmdbId = company.id
                )
            }
            val networks = details?.networks.orEmpty().mapNotNull { network ->
                val name = network.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCompany(
                    name = name,
                    logo = buildImageUrl(network.logoPath, "w300"),
                    tmdbId = network.id
                )
            }
            val poster = buildImageUrl(details?.posterPath, "w500")
            val backdrop = buildImageUrl(details?.backdropPath, "w1280")
            val collectionId = details?.belongsToCollection?.id
            val collectionName = details?.belongsToCollection?.name
            val logoPath = images?.logos?.let { selectBestLocalizedImagePath(it, lang) }
            val logo = buildImageUrl(logoPath, "w500")

            val castMembers = credits?.cast.orEmpty().mapNotNull { member ->
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCastMember(
                    name = name,
                    character = member.character?.takeIf { it.isNotBlank() },
                    photo = buildImageUrl(member.profilePath, "w500"),
                    tmdbId = member.id
                )
            }

            val creatorMembers = if (tmdbType == "tv") {
                details?.createdBy.orEmpty().mapNotNull { creator ->
                    val tmdbPersonId = creator.id ?: return@mapNotNull null
                    val name = creator.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MetaCastMember(
                        name = name,
                        character = "Creator",
                        photo = buildImageUrl(creator.profilePath, "w500"),
                        tmdbId = tmdbPersonId
                    )
                }.distinctBy { it.tmdbId ?: it.name.lowercase() }
            } else emptyList()

            val creatorNames = if (tmdbType == "tv") {
                details?.createdBy.orEmpty()
                    .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
            } else emptyList()

            val directorCrew = credits?.crew.orEmpty().filter {
                it.job.equals("Director", ignoreCase = true)
            }
            val directorMembers = directorCrew.mapNotNull { member ->
                val tmdbPersonId = member.id ?: return@mapNotNull null
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCastMember(
                    name = name,
                    character = "Director",
                    photo = buildImageUrl(member.profilePath, "w500"),
                    tmdbId = tmdbPersonId
                )
            }.distinctBy { it.tmdbId ?: it.name.lowercase() }
            val directorNames = directorCrew.mapNotNull {
                it.name?.trim()?.takeIf(String::isNotBlank)
            }

            val writerCrew = credits?.crew.orEmpty().filter { crew ->
                val job = crew.job?.lowercase() ?: ""
                job.contains("writer") || job.contains("screenplay")
            }
            val writerMembers = writerCrew.mapNotNull { member ->
                val tmdbPersonId = member.id ?: return@mapNotNull null
                val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MetaCastMember(
                    name = name,
                    character = "Writer",
                    photo = buildImageUrl(member.profilePath, "w500"),
                    tmdbId = tmdbPersonId
                )
            }.distinctBy { it.tmdbId ?: it.name.lowercase() }
            val writerNames = writerCrew.mapNotNull {
                it.name?.trim()?.takeIf(String::isNotBlank)
            }

            // Only expose either Director or Writer people (prefer Director; for TV prefer Creator).
            val hasCreator = creatorMembers.isNotEmpty() || creatorNames.isNotEmpty()
            val hasDirector = directorMembers.isNotEmpty() || directorNames.isNotEmpty()

            val exposedDirectorMembers = when {
                tmdbType == "tv" && hasCreator -> creatorMembers
                tmdbType != "tv" && hasDirector -> directorMembers
                else -> emptyList()
            }
            val exposedWriterMembers = when {
                tmdbType == "tv" && hasCreator -> emptyList()
                tmdbType != "tv" && hasDirector -> emptyList()
                else -> writerMembers
            }
            val exposedDirectorNames = when {
                tmdbType == "tv" && hasCreator -> creatorNames
                tmdbType != "tv" && hasDirector -> directorNames
                else -> emptyList()
            }
            val exposedWriterNames = when {
                tmdbType == "tv" && hasCreator -> emptyList()
                tmdbType != "tv" && hasDirector -> emptyList()
                else -> writerNames
            }

            val isEmpty = genres.isEmpty() &&
                description == null &&
                backdrop == null &&
                logo == null &&
                poster == null &&
                castMembers.isEmpty() &&
                directorNames.isEmpty() &&
                writerNames.isEmpty() &&
                releaseInfo == null &&
                rating == null &&
                runtime == null &&
                countries.isNullOrEmpty() &&
                originalLanguage == null &&
                productionCompanies.isEmpty() &&
                networks.isEmpty() &&
                ageRating == null &&
                status == null &&
                trailers.isEmpty()
            if (isEmpty) return@withContext null

            val enrichment = TmdbEnrichment(
                localizedTitle = localizedTitle,
                description = description,
                genres = genres,
                backdrop = backdrop,
                logo = logo,
                poster = poster,
                directorMembers = exposedDirectorMembers,
                writerMembers = exposedWriterMembers,
                castMembers = castMembers,
                releaseInfo = releaseInfo,
                rating = rating,
                runtimeMinutes = runtime,
                director = exposedDirectorNames,
                writer = exposedWriterNames,
                productionCompanies = productionCompanies,
                networks = networks,
                ageRating = ageRating,
                status = status,
                countries = countries,
                language = originalLanguage,
                collectionId = collectionId,
                collectionName = collectionName,
                originalTitle = originalTitle,
                alternativeTitles = altTitles,
                trailers = trailers
            )
            enrichmentCache[cacheKey] = enrichment
            pending.complete(enrichment)
            enrichment
        } catch (e: CancellationException) {
            pending.cancel(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch TMDB enrichment: ${e.message}", e)
            pending.complete(null)
            null
        } finally {
            if (!pending.isCompleted) pending.complete(null)
            enrichmentInFlight.remove(cacheKey, pending)
        }
    }

    private suspend fun TmdbApi.fetchDetails(id: Int, type: String, lang: String) =
        if (type == "tv") getTvDetails(id, tmdbApiKey(), lang).body()
        else getMovieDetails(id, tmdbApiKey(), lang).body()

    private suspend fun TmdbApi.fetchCredits(id: Int, type: String, lang: String): TmdbCreditsResponse? =
        if (type == "tv") {
            val aggregate = getTvAggregateCredits(id, tmdbApiKey(), lang).body()
            aggregate?.let { agg ->
                TmdbCreditsResponse(
                    cast = agg.cast?.map { member ->
                        TmdbCastMember(
                            id = member.id,
                            name = member.name,
                            character = member.roles?.firstOrNull()?.character,
                            profilePath = member.profilePath
                        )
                    },
                    crew = agg.crew?.flatMap { member ->
                        member.jobs?.map { job ->
                            TmdbCrewMember(
                                id = member.id,
                                name = member.name,
                                job = job.job,
                                department = member.department,
                                profilePath = member.profilePath
                            )
                        } ?: emptyList()
                    }
                )
            }
        } else {
            getMovieCredits(id, tmdbApiKey(), lang).body()
        }

    // ---------------------------------------------------------------------
    // Trailers
    // ---------------------------------------------------------------------

    private suspend fun fetchTmdbTrailers(
        tmdbId: Int,
        tmdbType: String,
        preferredLanguage: String
    ): List<MetaTrailer> {
        val localized = fetchVideoResults(tmdbId, tmdbType, preferredLanguage)
        val merged = if (
            localized.isNotEmpty() ||
            preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)
        ) {
            localized
        } else {
            localized + fetchVideoResults(tmdbId, tmdbType, TMDB_TRAILER_FALLBACK_LANGUAGE)
        }

        return rankTmdbTrailers(merged)
            .mapNotNull { video ->
                val ytId = video.key?.trim()?.takeIf { YOUTUBE_VIDEO_ID_REGEX.matches(it) }
                    ?: return@mapNotNull null
                MetaTrailer(
                    source = "TMDB",
                    type = video.type?.takeIf(String::isNotBlank),
                    name = video.name?.takeIf(String::isNotBlank),
                    ytId = ytId,
                    lang = video.iso6391?.takeIf(String::isNotBlank)
                )
            }
            .distinctBy { it.ytId }
    }

    private suspend fun fetchVideoResults(
        tmdbId: Int,
        tmdbType: String,
        language: String
    ): List<TmdbVideoResult> = runCatching {
        when (tmdbType) {
            "tv" -> tmdbApi.getTvVideos(tmdbId, tmdbApiKey(), language).body()?.results.orEmpty()
            else -> tmdbApi.getMovieVideos(tmdbId, tmdbApiKey(), language).body()?.results.orEmpty()
        }
    }.getOrElse {
        Log.w(TAG, "Failed to fetch $tmdbType trailers for $tmdbId ($language): ${it.message}")
        emptyList()
    }

    private fun rankTmdbTrailers(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
        fun typePriority(type: String?): Int = when (type?.trim()?.lowercase(Locale.US)) {
            "trailer" -> 0
            "teaser" -> 1
            "clip" -> 2
            "featurette" -> 3
            else -> 4
        }
        return results.asSequence()
            .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
            .sortedWith(
                compareBy<TmdbVideoResult> { typePriority(it.type) }
                    .thenByDescending { it.official == true }
                    .thenByDescending { it.publishedAt.orEmpty() }
            )
            .toList()
    }

    // ---------------------------------------------------------------------
    // Episodes
    // ---------------------------------------------------------------------

    suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String = "en"
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(ioDispatcher) {
        if (tmdbApiKey().isBlank()) return@withContext emptyMap()
        val lang = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}:$lang"
        episodeCache[cacheKey]?.let { return@withContext it }
        episodeInFlight[cacheKey]?.let { return@withContext it.await() }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val pending = CompletableDeferred<Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
        episodeInFlight.putIfAbsent(cacheKey, pending)?.let { existing ->
            return@withContext existing.await()
        }

        val collected = mutableMapOf<Pair<Int, Int>, TmdbEpisodeEnrichment>()
        try {
            seasonNumbers.distinct().forEach { season ->
                try {
                    val episodes = tmdbApi.getTvSeasonDetails(numericId, season, tmdbApiKey(), lang)
                        .body()?.episodes.orEmpty()
                    episodes.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        collected[season to epNum] = ep.toEnrichment()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch TMDB season $season: ${e.message}")
                }
            }
            val finalResult = collected.toMap()
            if (finalResult.isNotEmpty()) episodeCache[cacheKey] = finalResult
            pending.complete(finalResult)
            finalResult
        } catch (e: CancellationException) {
            pending.cancel(e)
            throw e
        } finally {
            if (!pending.isCompleted) pending.complete(emptyMap())
            episodeInFlight.remove(cacheKey, pending)
        }
    }

    // ---------------------------------------------------------------------
    // More like this
    // ---------------------------------------------------------------------

    suspend fun fetchMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en",
        maxItems: Int = 12
    ): List<MetaPreview> = withContext(ioDispatcher) {
        if (tmdbApiKey().isBlank()) return@withContext emptyList()
        val lang = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.name}:$lang:more_like"
        moreLikeThisCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()
        val tmdbType = contentType.toTmdbType()
        val imageLang = buildImageLanguageParam(lang)

        try {
            val recommendations = when (tmdbType) {
                "tv" -> tmdbApi.getTvRecommendations(numericId, tmdbApiKey(), lang).body()
                else -> tmdbApi.getMovieRecommendations(numericId, tmdbApiKey(), lang).body()
            }
            val raw = recommendations?.results.orEmpty().filter { it.id > 0 }
            val languageCode = lang.substringBefore("-")
            val sorted = raw.sortedWith(
                compareByDescending<TmdbRecommendationResult> {
                    it.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                }
                    .thenByDescending { it.voteCount ?: 0 }
                    .thenByDescending { it.voteAverage ?: 0.0 }
            )
            val qualityFiltered = sorted.filter { rec ->
                val voteCount = rec.voteCount ?: 0
                val voteAverage = rec.voteAverage ?: 0.0
                val localized = rec.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                localized || voteCount >= 20 || voteAverage >= 6.0
            }
            val selected = (if (qualityFiltered.isNotEmpty()) qualityFiltered else sorted)
                .take(maxItems.coerceAtLeast(1))

            val items = coroutineScope {
                selected.map { rec ->
                    async {
                        val recType = when (rec.mediaType?.trim()?.lowercase()) {
                            "tv" -> "tv"
                            "movie" -> "movie"
                            else -> tmdbType
                        }
                        val recContentType = if (recType == "tv") ContentType.SERIES else ContentType.MOVIE
                        val title = rec.title?.takeIf { it.isNotBlank() }
                            ?: rec.name?.takeIf { it.isNotBlank() }
                            ?: rec.originalTitle?.takeIf { it.isNotBlank() }
                            ?: rec.originalName?.takeIf { it.isNotBlank() }
                            ?: return@async null

                        val localizedBackdrop = runCatching {
                            when (recType) {
                                "tv" -> tmdbApi.getTvImages(rec.id, tmdbApiKey(), imageLang).body()
                                else -> tmdbApi.getMovieImages(rec.id, tmdbApiKey(), imageLang).body()
                            }
                        }.getOrNull()?.let { images ->
                            selectBestLocalizedImagePath(images.backdrops.orEmpty(), lang)
                        }

                        val backdrop = buildImageUrl(localizedBackdrop ?: rec.backdropPath, "w1280")
                        val fallbackPoster = buildImageUrl(rec.posterPath, "w780")

                        val releaseInfo = if (recType == "tv") {
                            val startYear = rec.firstAirDate.yearPart()
                            if (startYear != null) {
                                val tvDetails = runCatching {
                                    tmdbApi.getTvDetails(rec.id, tmdbApiKey(), lang).body()
                                }.getOrNull()
                                buildShowYearRange(startYear, tvDetails?.lastAirDate.yearPart(), tvDetails?.status)
                            } else null
                        } else {
                            rec.releaseDate.yearPart()
                        }

                        MetaPreview(
                            id = "tmdb:${rec.id}",
                            type = recContentType,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = rec.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = rec.voteAverage?.toFloat(),
                            genres = emptyList(),
                            landscapePoster = backdrop,
                            rawPosterUrl = fallbackPoster
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            moreLikeThisCache[cacheKey] = items
            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch recommendations for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------------
    // Collections
    // ---------------------------------------------------------------------

    suspend fun fetchMovieCollection(
        collectionId: Int,
        language: String = "en"
    ): List<MetaPreview> = withContext(ioDispatcher) {
        if (tmdbApiKey().isBlank()) return@withContext emptyList()
        val lang = normalizeTmdbLanguage(language)
        val cacheKey = "$collectionId:$lang:collection"
        collectionCache[cacheKey]?.let { return@withContext it }

        try {
            val parts = tmdbApi.getCollectionDetails(collectionId, tmdbApiKey(), lang).body()?.parts.orEmpty()
            val sortedParts = parts.sortedBy { it.releaseDate ?: "9999" }
            val imageLang = buildImageLanguageParam(lang)

            val items = coroutineScope {
                sortedParts.map { part ->
                    async {
                        val title = part.title ?: return@async null
                        val localizedBackdrop = runCatching {
                            tmdbApi.getMovieImages(part.id, tmdbApiKey(), imageLang).body()
                        }.getOrNull()?.let { images ->
                            selectBestLocalizedImagePath(images.backdrops.orEmpty(), lang)
                        }
                        val backdrop = buildImageUrl(localizedBackdrop ?: part.backdropPath, "w1280")
                        val fallbackPoster = buildImageUrl(part.posterPath, "w780")
                        MetaPreview(
                            id = "tmdb:${part.id}",
                            type = ContentType.MOVIE,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = part.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = part.releaseDate?.take(4),
                            imdbRating = part.voteAverage?.toFloat(),
                            genres = emptyList(),
                            landscapePoster = backdrop,
                            rawPosterUrl = fallbackPoster
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            collectionCache[cacheKey] = items
            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch collection for $collectionId: ${e.message}")
            emptyList()
        }
    }

    // ---------------------------------------------------------------------
    // Entity browse (company / network)
    // ---------------------------------------------------------------------

    suspend fun fetchEntityBrowse(
        entityKind: TmdbEntityKind,
        entityId: Int,
        sourceType: String,
        fallbackName: String? = null,
        language: String = "en"
    ): TmdbEntityBrowseData? = withContext(ioDispatcher) {
        if (tmdbApiKey().isBlank()) return@withContext null
        val lang = normalizeTmdbLanguage(language)
        val normalizedSource = normalizeEntitySourceType(sourceType)
        val cacheKey = "${entityKind.routeValue}:$entityId:$normalizedSource:$lang"
        entityBrowseCache[cacheKey]?.let { return@withContext it }

        val header = fetchEntityHeader(entityKind, entityId, fallbackName, lang)
        val rails = buildEntityMediaOrder(entityKind, normalizedSource).flatMap { mediaType ->
            TmdbEntityRailType.values().mapNotNull { railType ->
                val page = fetchEntityRailPage(
                    entityKind = entityKind,
                    entityId = entityId,
                    mediaType = mediaType,
                    railType = railType,
                    language = lang,
                    page = 1
                )
                if (page.items.isEmpty()) null else TmdbEntityRail(
                    mediaType = mediaType,
                    railType = railType,
                    items = page.items,
                    currentPage = 1,
                    hasMore = page.hasMore,
                    isLoading = false
                )
            }
        }

        if (header == null && rails.isEmpty()) return@withContext null

        val data = TmdbEntityBrowseData(
            header = header ?: TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = fallbackName?.takeIf { it.isNotBlank() } ?: "Unknown",
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null
            ),
            rails = rails
        )
        entityBrowseCache[cacheKey] = data
        data
    }

    private suspend fun fetchEntityHeader(
        entityKind: TmdbEntityKind,
        entityId: Int,
        fallbackName: String?,
        language: String
    ): TmdbEntityHeader? {
        val cacheKey = "${entityKind.routeValue}:$entityId:$language:header"
        entityHeaderCache[cacheKey]?.let { return it }

        val header = runCatching {
            when (entityKind) {
                TmdbEntityKind.COMPANY -> tmdbApi.getCompanyDetails(entityId, tmdbApiKey()).body()?.let { body ->
                    TmdbEntityHeader(
                        id = body.id,
                        kind = entityKind,
                        name = body.name?.takeIf { it.isNotBlank() }
                            ?: fallbackName?.takeIf { it.isNotBlank() }
                            ?: "Unknown",
                        logo = buildImageUrl(body.logoPath, "w500"),
                        originCountry = body.originCountry?.takeIf { it.isNotBlank() },
                        secondaryLabel = body.headquarters?.takeIf { it.isNotBlank() },
                        description = body.description?.takeIf { it.isNotBlank() }
                    )
                }
                TmdbEntityKind.NETWORK -> tmdbApi.getNetworkDetails(entityId, tmdbApiKey()).body()?.let { body ->
                    TmdbEntityHeader(
                        id = body.id,
                        kind = entityKind,
                        name = body.name?.takeIf { it.isNotBlank() }
                            ?: fallbackName?.takeIf { it.isNotBlank() }
                            ?: "Unknown",
                        logo = buildImageUrl(body.logoPath, "w500"),
                        originCountry = body.originCountry?.takeIf { it.isNotBlank() },
                        secondaryLabel = body.headquarters?.takeIf { it.isNotBlank() },
                        description = null
                    )
                }
            }
        }.getOrElse {
            Log.w(TAG, "Failed to fetch ${entityKind.routeValue} header for $entityId: ${it.message}")
            null
        } ?: fallbackName?.takeIf { it.isNotBlank() }?.let {
            TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = it,
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null
            )
        }

        if (header != null) entityHeaderCache[cacheKey] = header
        return header
    }

    suspend fun fetchEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int
    ): TmdbEntityRailPageResult {
        if (tmdbApiKey().isBlank()) return TmdbEntityRailPageResult(emptyList(), false)
        if (entityKind == TmdbEntityKind.NETWORK && mediaType == TmdbEntityMediaType.MOVIE) {
            return TmdbEntityRailPageResult(emptyList(), false)
        }

        val cacheKey = "${entityKind.routeValue}:$entityId:${mediaType.value}:${railType.value}:$language:page:$page"
        entityRailCache[cacheKey]?.let { cached ->
            return TmdbEntityRailPageResult(items = cached, hasMore = cached.isNotEmpty())
        }

        val today = LocalDate.now().toString()
        val voteCountFloor = if (railType == TmdbEntityRailType.TOP_RATED) TOP_RATED_VOTE_COUNT_FLOOR else null

        val result = try {
            val response = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> tmdbApi.discoverMovies(
                    apiKey = tmdbApiKey(),
                    language = language,
                    page = page,
                    sortBy = movieSortBy(railType),
                    withCompanies = entityId.toString(),
                    releaseDateLte = if (railType == TmdbEntityRailType.RECENT) today else null,
                    voteCountGte = voteCountFloor
                ).body()
                TmdbEntityMediaType.TV -> tmdbApi.discoverTv(
                    apiKey = tmdbApiKey(),
                    language = language,
                    page = page,
                    sortBy = tvSortBy(railType),
                    withCompanies = if (entityKind == TmdbEntityKind.COMPANY) entityId.toString() else null,
                    withNetworks = if (entityKind == TmdbEntityKind.NETWORK) entityId.toString() else null,
                    firstAirDateLte = if (railType == TmdbEntityRailType.RECENT || entityKind == TmdbEntityKind.NETWORK) today else null,
                    voteCountGte = voteCountFloor,
                    withStatus = if (entityKind == TmdbEntityKind.NETWORK) "0|3|4" else null
                ).body()
            }
            val results = response?.results.orEmpty()
            val totalPages = response?.totalPages ?: page
            val items = results
                .filter { it.id > 0 }
                .mapNotNull { mapEntityDiscoverResult(it, mediaType) }
                .take(ENTITY_RAIL_MAX_ITEMS)
            TmdbEntityRailPageResult(items = items, hasMore = page < totalPages && items.isNotEmpty())
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to fetch ${entityKind.routeValue} rail ${railType.value}/${mediaType.value} for $entityId: ${e.message}"
            )
            TmdbEntityRailPageResult(emptyList(), false)
        }

        if (result.items.isNotEmpty()) entityRailCache[cacheKey] = result.items
        return result
    }

    private fun mapEntityDiscoverResult(
        result: TmdbDiscoverResult,
        mediaType: TmdbEntityMediaType
    ): MetaPreview? {
        val title = result.title?.takeIf { it.isNotBlank() }
            ?: result.name?.takeIf { it.isNotBlank() }
            ?: result.originalTitle?.takeIf { it.isNotBlank() }
            ?: result.originalName?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = buildImageUrl(result.posterPath, "w500")
            ?: buildImageUrl(result.backdropPath, "w780")
            ?: return null
        val background = buildImageUrl(result.backdropPath, "w1280")
        val releaseInfo = when (mediaType) {
            TmdbEntityMediaType.MOVIE -> result.releaseDate?.take(4)
            TmdbEntityMediaType.TV -> result.firstAirDate?.take(4)
        }
        return MetaPreview(
            id = "tmdb:${result.id}",
            type = if (mediaType == TmdbEntityMediaType.TV) ContentType.SERIES else ContentType.MOVIE,
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = result.overview?.takeIf { it.isNotBlank() },
            releaseInfo = releaseInfo,
            imdbRating = result.voteAverage?.toFloat(),
            genres = emptyList()
        )
    }

    internal fun buildEntityMediaOrder(
        entityKind: TmdbEntityKind,
        sourceType: String
    ): List<TmdbEntityMediaType> {
        if (entityKind == TmdbEntityKind.NETWORK) return listOf(TmdbEntityMediaType.TV)
        return when (normalizeEntitySourceType(sourceType)) {
            "movie" -> listOf(TmdbEntityMediaType.MOVIE, TmdbEntityMediaType.TV)
            else -> listOf(TmdbEntityMediaType.TV, TmdbEntityMediaType.MOVIE)
        }
    }

    private fun normalizeEntitySourceType(sourceType: String): String = when (sourceType.trim().lowercase(Locale.US)) {
        "movie" -> "movie"
        "tv", "series", "show" -> "tv"
        else -> "tv"
    }

    private fun movieSortBy(railType: TmdbEntityRailType): String = when (railType) {
        TmdbEntityRailType.POPULAR -> "popularity.desc"
        TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
        TmdbEntityRailType.RECENT -> "primary_release_date.desc"
    }

    private fun tvSortBy(railType: TmdbEntityRailType): String = when (railType) {
        TmdbEntityRailType.POPULAR -> "popularity.desc"
        TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
        TmdbEntityRailType.RECENT -> "first_air_date.desc"
    }

    // ---------------------------------------------------------------------
    // Person detail
    // ---------------------------------------------------------------------

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null,
        language: String = "en"
    ): PersonDetail? = withContext(ioDispatcher) {
        if (tmdbApiKey().isBlank()) return@withContext null
        val lang = normalizeTmdbLanguage(language)
        val cacheKey = "$personId:${preferCrewCredits?.toString() ?: "auto"}:$lang"
        personCache[cacheKey]?.let { return@withContext it }

        try {
            val (person, credits) = coroutineScope {
                val personAsync = async {
                    tmdbApi.getPersonDetails(personId, tmdbApiKey(), lang).body()
                }
                val creditsAsync = async {
                    tmdbApi.getPersonCombinedCredits(personId, tmdbApiKey(), lang).body()
                }
                personAsync.await() to creditsAsync.await()
            }
            if (person == null) return@withContext null

            val biography = if (person.biography.isNullOrBlank() && lang != "en") {
                runCatching {
                    tmdbApi.getPersonDetails(personId, tmdbApiKey(), "en").body()?.biography
                }.getOrNull()
            } else {
                person.biography
            }?.takeIf { it.isNotBlank() }

            val preferCrew = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

            val castMovies = mapMovieCreditsFromCast(credits?.cast.orEmpty())
            val crewMovies = mapMovieCreditsFromCrew(credits?.crew.orEmpty())
            val movieCredits = when {
                preferCrew && crewMovies.isNotEmpty() -> crewMovies
                castMovies.isNotEmpty() -> castMovies
                else -> crewMovies
            }

            val castTv = mapTvCreditsFromCast(credits?.cast.orEmpty())
            val crewTv = mapTvCreditsFromCrew(credits?.crew.orEmpty())
            val tvCredits = when {
                preferCrew && crewTv.isNotEmpty() -> crewTv
                castTv.isNotEmpty() -> castTv
                else -> crewTv
            }

            val detail = PersonDetail(
                tmdbId = person.id,
                name = person.name ?: "Unknown",
                biography = biography,
                birthday = person.birthday?.takeIf { it.isNotBlank() },
                deathday = person.deathday?.takeIf { it.isNotBlank() },
                placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                profilePhoto = buildImageUrl(person.profilePath, "w500"),
                knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                movieCredits = movieCredits,
                tvCredits = tvCredits
            )
            personCache[cacheKey] = detail
            detail
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch person detail: ${e.message}", e)
            null
        }
    }

    private fun shouldPreferCrewCredits(knownForDepartment: String?): Boolean {
        val department = knownForDepartment?.trim()?.lowercase() ?: return false
        if (department.isBlank()) return false
        return department != "acting" && department != "actors"
    }

    private fun mapMovieCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapMovieCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun buildShowYearRange(startYear: String, endYear: String?, status: String?): String {
        val isEnded = status != null && status != "Returning Series" && status != "In Production"
        return when {
            isEnded && endYear != null && endYear != startYear -> "$startYear-$endYear"
            isEnded -> startYear
            else -> "$startYear-"
        }
    }

    private fun String?.yearPart(): String? {
        val value = this?.trim()?.takeIf { it.length >= 4 }?.take(4) ?: return null
        return value.takeIf { it.all(Char::isDigit) }
    }

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "$IMAGE_BASE_URL$size$clean"
    }

    private fun normalizeTmdbLanguage(language: String?): String {
        val raw = language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?: return "en"
        val normalized = raw.split("-").let { parts ->
            if (parts.size == 2) "${parts[0].lowercase(Locale.US)}-${parts[1].uppercase(Locale.US)}"
            else raw.lowercase(Locale.US)
        }
        return when (normalized) {
            "es-419" -> "es-MX"
            else -> normalized
        }
    }

    private fun buildImageLanguageParam(normalizedLanguage: String): String = buildString {
        append(normalizedLanguage.substringBefore("-"))
        append(",")
        append(normalizedLanguage)
        append(",en,null")
    }

    private fun selectBestLocalizedImagePath(
        images: List<TmdbImage>,
        normalizedLanguage: String
    ): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        val explicitRegion = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        val regionCode = explicitRegion
            ?: LANGUAGE_DEFAULT_REGION[languageCode]
            ?: DEFAULT_LANGUAGE_REGIONS[languageCode]
        return images
            .sortedWith(
                compareByDescending<TmdbImage> { it.iso6391 == languageCode && it.iso31661 == regionCode }
                    .thenByDescending { it.iso6391 == languageCode && it.iso31661 == null }
                    .thenByDescending { it.iso6391 == languageCode }
                    .thenByDescending { it.iso6391 == "en" }
                    .thenByDescending { it.iso6391 == null }
            )
            .firstOrNull()
            ?.filePath
    }

    private fun ContentType.toTmdbType(): String =
        if (this == ContentType.SERIES || this == ContentType.TV) "tv" else "movie"

    private companion object {
        private val DEFAULT_LANGUAGE_REGIONS = mapOf(
            "pt" to "PT",
            "es" to "ES"
        )
    }
}

// ---------------------------------------------------------------------
// Public DTOs
// ---------------------------------------------------------------------

data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val directorMembers: List<MetaCastMember>,
    val writerMembers: List<MetaCastMember>,
    val castMembers: List<MetaCastMember>,
    val releaseInfo: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val director: List<String>,
    val writer: List<String>,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val ageRating: String?,
    val status: String?,
    val countries: List<String>?,
    val language: String?,
    val collectionId: Int?,
    val collectionName: String?,
    val originalTitle: String? = null,
    val alternativeTitles: List<String> = emptyList(),
    val trailers: List<MetaTrailer> = emptyList()
)

data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?
)

enum class TmdbEntityKind(val routeValue: String) {
    COMPANY("company"),
    NETWORK("network");

    companion object {
        fun fromRouteValue(value: String): TmdbEntityKind = when (value.trim().lowercase(Locale.US)) {
            "network" -> NETWORK
            else -> COMPANY
        }
    }
}

enum class TmdbEntityMediaType(val value: String) {
    MOVIE("movie"),
    TV("tv")
}

enum class TmdbEntityRailType(val value: String) {
    POPULAR("popular"),
    TOP_RATED("top_rated"),
    RECENT("recent")
}

data class TmdbEntityHeader(
    val id: Int,
    val kind: TmdbEntityKind,
    val name: String,
    val logo: String?,
    val originCountry: String?,
    val secondaryLabel: String?,
    val description: String?
)

data class TmdbEntityRail(
    val mediaType: TmdbEntityMediaType,
    val railType: TmdbEntityRailType,
    val items: List<MetaPreview>,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

data class TmdbEntityBrowseData(
    val header: TmdbEntityHeader,
    val rails: List<TmdbEntityRail>
)

data class TmdbEntityRailPageResult(
    val items: List<MetaPreview>,
    val hasMore: Boolean
)

// ---------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------

/** Bundle for the five parallel enrichment fetches. */
private class EnrichmentBundle(
    val details: com.sluggyard.tv.data.remote.api.TmdbDetailsResponse?,
    val credits: TmdbCreditsResponse?,
    val images: com.sluggyard.tv.data.remote.api.TmdbImagesResponse?,
    val ageRating: String?,
    val altTitles: List<String>
)

// Fallback regions for bare language codes (e.g. "fr" instead of "fr-FR").
// Without this, non-hyphenated locales fall straight through to US/GB defaults
// and users see American ratings.
private val LANGUAGE_DEFAULT_REGION: Map<String, String> = mapOf(
    "ar" to "SA", "bg" to "BG", "bs" to "BA", "cs" to "CZ", "da" to "DK",
    "de" to "DE", "el" to "GR", "es" to "ES", "et" to "EE", "fi" to "FI",
    "fr" to "FR", "he" to "IL", "hi" to "IN", "hr" to "HR", "hu" to "HU",
    "id" to "ID", "it" to "IT", "ja" to "JP", "ko" to "KR", "lt" to "LT",
    "lv" to "LV", "nl" to "NL", "no" to "NO", "pl" to "PL", "pt" to "PT",
    "ro" to "RO", "ru" to "RU", "sk" to "SK", "sl" to "SI", "sr" to "RS",
    "sv" to "SE", "th" to "TH", "tr" to "TR", "uk" to "UA", "vi" to "VN",
    "zh" to "CN"
)

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val languageCode = normalizedLanguage.substringBefore("-").lowercase(Locale.US)
    val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
        ?: LANGUAGE_DEFAULT_REGION[languageCode]
    return buildList {
        if (!fromLanguage.isNullOrBlank()) add(fromLanguage)
        add("US")
        add("GB")
    }.distinct()
}

private fun selectMovieAgeRating(
    countries: List<TmdbMovieReleaseDateCountry>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = countries.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]
            ?.releaseDates
            .orEmpty()
            .mapNotNull { it.certification?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!rating.isNullOrBlank()) return rating
    }
    return countries
        .asSequence()
        .flatMap { it.releaseDates.orEmpty().asSequence() }
        .mapNotNull { it.certification?.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun selectTvAgeRating(
    ratings: List<TmdbTvContentRatingItem>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = ratings.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]?.rating?.trim()
        if (!rating.isNullOrBlank()) return rating
    }
    return ratings
        .mapNotNull { it.rating?.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun TmdbEpisode.toEnrichment(): TmdbEpisodeEnrichment = TmdbEpisodeEnrichment(
    title = name?.takeIf { it.isNotBlank() },
    overview = overview?.takeIf { it.isNotBlank() },
    thumbnail = stillPath?.takeIf { it.isNotBlank() }?.let { "${IMAGE_BASE_URL}w500$it" },
    airDate = airDate?.takeIf { it.isNotBlank() },
    runtimeMinutes = runtime
)