package com.sluggyard.tv.ui.app.streams

import android.util.Log
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves whether a movie has a past digital release via TMDB (types 4–6).
 * Results are cached per IMDb / TMDB id for the process lifetime.
 *
 * Home TMDB rails navigate with `tmdb:{id}` — those must hit release-dates directly.
 * Requiring IMDb-only used to fail open (UNKNOWN) and let CAM junk auto-play.
 */
class DigitalReleaseLookup(
    private val tmdbApi: TmdbApi,
    private val apiKey: String = BuildConfig.TMDB_API_KEY,
) {
    private val cache = ConcurrentHashMap<String, DigitalReleasePolicy.Status>()

    suspend fun movieStatus(contentId: String?, contentType: String?): DigitalReleasePolicy.Status =
        withContext(Dispatchers.IO) {
            val type = contentType?.trim()?.lowercase().orEmpty()
            if (type !in MOVIE_TYPES) return@withContext DigitalReleasePolicy.Status.UNKNOWN
            if (apiKey.isBlank()) return@withContext DigitalReleasePolicy.Status.UNKNOWN

            val cacheKey = cacheKey(contentId) ?: return@withContext DigitalReleasePolicy.Status.UNKNOWN
            cache[cacheKey]?.let {
                Log.i(TAG, "cache hit key=$cacheKey status=$it type=$type")
                return@withContext it
            }

            val status = runCatching {
                val tmdbId = parseTmdbId(contentId)
                if (tmdbId != null) {
                    lookupByTmdbId(tmdbId)
                } else {
                    val imdb = normalizeImdbId(contentId)
                        ?: return@runCatching DigitalReleasePolicy.Status.UNKNOWN
                    lookupByImdb(imdb)
                }
            }
                .onFailure { Log.w(TAG, "digital-release lookup failed for $cacheKey: ${it.message}") }
                .getOrDefault(DigitalReleasePolicy.Status.UNKNOWN)
            Log.i(TAG, "resolved key=$cacheKey status=$status type=$type id=$contentId")
            cache[cacheKey] = status
            status
        }

    private suspend fun lookupByImdb(imdbId: String): DigitalReleasePolicy.Status {
        val find = tmdbApi.findByExternalId(imdbId, apiKey)
        if (!find.isSuccessful) return DigitalReleasePolicy.Status.UNKNOWN
        val tmdbId = find.body()?.movieResults?.firstOrNull()?.id
            ?: return DigitalReleasePolicy.Status.UNKNOWN
        return lookupByTmdbId(tmdbId)
    }

    private suspend fun lookupByTmdbId(tmdbId: Int): DigitalReleasePolicy.Status {
        val releases = tmdbApi.getMovieReleaseDates(tmdbId, apiKey)
        if (!releases.isSuccessful) {
            Log.w(TAG, "release-dates HTTP ${releases.code()} for tmdb:$tmdbId — trying primary release_date")
            return lookupPrimaryReleaseFallback(tmdbId)
        }
        val rows = releases.body()?.results.orEmpty()
            .flatMap { country -> country.releaseDates.orEmpty() }
            .mapNotNull { item ->
                val releaseType = item.type ?: return@mapNotNull null
                val date = DigitalReleasePolicy.parseReleaseDate(item.releaseDate) ?: return@mapNotNull null
                DigitalReleasePolicy.ReleaseRow(type = releaseType, date = date)
            }
        val fromRows = DigitalReleasePolicy.status(rows)
        if (fromRows != DigitalReleasePolicy.Status.UNKNOWN) {
            Log.i(TAG, "tmdb:$tmdbId status=$fromRows rows=${rows.size}")
            return fromRows
        }
        // Empty release-dates payload: fall back to movie details primary release_date so
        // theatrical-only titles (Brand New Day, etc.) are NOT_YET instead of fail-open.
        val fallback = lookupPrimaryReleaseFallback(tmdbId)
        Log.i(TAG, "tmdb:$tmdbId release-dates empty → primary fallback status=$fallback")
        return fallback
    }

    private suspend fun lookupPrimaryReleaseFallback(tmdbId: Int): DigitalReleasePolicy.Status {
        val details = tmdbApi.getMovieDetails(tmdbId, apiKey)
        if (!details.isSuccessful) return DigitalReleasePolicy.Status.UNKNOWN
        val primary = DigitalReleasePolicy.parseReleaseDate(details.body()?.releaseDate)
        return DigitalReleasePolicy.statusFromPrimaryReleaseDate(primary)
    }

    companion object {
        private const val TAG = "DigitalRelease"
        private val MOVIE_TYPES = setOf("movie", "movies", "film", "films")

        fun cacheKey(raw: String?): String? {
            parseTmdbId(raw)?.let { return "tmdb:$it" }
            return normalizeImdbId(raw)
        }

        fun parseTmdbId(raw: String?): Int? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return null
            if (!value.startsWith("tmdb:", ignoreCase = true)) return null
            return value.removePrefix("tmdb:")
                .removePrefix("TMDB:")
                .substringBefore(':')
                .substringBefore('/')
                .toIntOrNull()
                ?.takeIf { it > 0 }
        }

        fun normalizeImdbId(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return null
            val base = value.substringBefore(':').substringBefore('/').trim()
            return base.takeIf { it.startsWith("tt", ignoreCase = true) && it.length > 2 }
                ?.lowercase()
        }
    }
}
