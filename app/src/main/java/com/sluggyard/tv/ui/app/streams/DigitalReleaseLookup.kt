package com.sluggyard.tv.ui.app.streams

import android.util.Log
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/**
 * Resolves whether a movie has a past digital release via TMDB (types 4–6).
 * Results are cached per IMDb / TMDB id for the process lifetime, bounded to
 * [MAX_CACHE_SIZE] LRU entries so a long-lived cached process cannot grow
 * without limit across a browsing session.
 *
 * Home TMDB rails navigate with `tmdb:{id}` — those must hit release-dates directly.
 * Requiring IMDb-only used to fail open (UNKNOWN) and let CAM junk auto-play.
 */
class DigitalReleaseLookup(
    private val tmdbApi: TmdbApi,
    private val apiKey: String = BuildConfig.TMDB_API_KEY,
) {
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, DigitalReleasePolicy.Status>(0, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DigitalReleasePolicy.Status>?,
        ): Boolean = size > MAX_CACHE_SIZE
    }

    /** Drops all cached rows (called on OS memory pressure; rebuilt on next lookup). */
    fun clear() = synchronized(cacheLock) { cache.clear() }

    private fun getCached(key: String): DigitalReleasePolicy.Status? =
        synchronized(cacheLock) { cache[key] }

    private fun putCached(key: String, status: DigitalReleasePolicy.Status) =
        synchronized(cacheLock) { cache[key] = status }

    suspend fun movieStatus(contentId: String?, contentType: String?): DigitalReleasePolicy.Status =
        withContext(Dispatchers.IO) {
            val type = contentType?.trim()?.lowercase().orEmpty()
            val series = type in SERIES_TYPES
            if (type !in MOVIE_TYPES && !series) return@withContext DigitalReleasePolicy.Status.UNKNOWN
            if (apiKey.isBlank()) return@withContext DigitalReleasePolicy.Status.UNKNOWN

            val cacheKey = cacheKey(contentId) ?: return@withContext DigitalReleasePolicy.Status.UNKNOWN
            getCached(cacheKey)?.let {
                Log.i(TAG, "cache hit key=$cacheKey status=$it type=$type")
                return@withContext it
            }

            val status = runCatching {
                val tmdbId = parseTmdbId(contentId)
                if (tmdbId != null) {
                    lookupByTmdbId(tmdbId, series)
                } else {
                    val imdb = normalizeImdbId(contentId)
                        ?: return@runCatching DigitalReleasePolicy.Status.UNKNOWN
                    lookupByImdb(imdb, series)
                }
            }
                .onFailure { Log.w(TAG, "digital-release lookup failed for $cacheKey: ${it.message}") }
                .getOrDefault(DigitalReleasePolicy.Status.UNKNOWN)
            Log.i(TAG, "resolved key=$cacheKey status=$status type=$type id=$contentId")
            putCached(cacheKey, status)
            status
        }

    private suspend fun lookupByImdb(imdbId: String, series: Boolean): DigitalReleasePolicy.Status {
        val find = tmdbApi.findByExternalId(imdbId, apiKey)
        if (!find.isSuccessful) return DigitalReleasePolicy.Status.UNKNOWN
        // TMDB's find endpoint separates movie and TV hits; a series must resolve
        // through tv_results or the movie lookup will 404 / fail open.
        val results = if (series) find.body()?.tvResults else find.body()?.movieResults
        val tmdbId = results?.firstOrNull()?.id
            ?: return DigitalReleasePolicy.Status.UNKNOWN
        return lookupByTmdbId(tmdbId, series)
    }

    private suspend fun lookupByTmdbId(tmdbId: Int, series: Boolean): DigitalReleasePolicy.Status {
        if (!series) {
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
        return lookupSeriesStatus(tmdbId)
    }

    /**
     * Series gate: TMDB's TV status + first_air_date. Planned / in-production /
     * post-production titles are NOT_YET even without a date (the pilot has not
     * screened); otherwise a past first air is AVAILABLE and a future one NOT_YET.
     * No usable signal stays UNKNOWN (fail open).
     */
    private suspend fun lookupSeriesStatus(tmdbId: Int): DigitalReleasePolicy.Status {
        val details = tmdbApi.getTvDetails(tmdbId, apiKey)
        if (!details.isSuccessful) {
            Log.w(TAG, "series details HTTP ${details.code()} for tmdb:$tmdbId — fail-open UNKNOWN")
            return DigitalReleasePolicy.Status.UNKNOWN
        }
        val body = details.body()
        val status = DigitalReleasePolicy.seriesStatus(body?.status, body?.firstAirDate)
        Log.i(TAG, "tmdb:$tmdbId series status='${body?.status}' firstAir='${body?.firstAirDate}' → $status")
        return status
    }

    private suspend fun lookupPrimaryReleaseFallback(tmdbId: Int): DigitalReleasePolicy.Status {
        val details = tmdbApi.getMovieDetails(tmdbId, apiKey)
        if (!details.isSuccessful) return DigitalReleasePolicy.Status.UNKNOWN
        val primary = DigitalReleasePolicy.parseReleaseDate(details.body()?.releaseDate)
        return DigitalReleasePolicy.statusFromPrimaryReleaseDate(primary)
    }

    companion object {
        /** Bounds the in-memory LRU so a long-lived cached process cannot grow without limit. */
        private const val MAX_CACHE_SIZE = 512

        private const val TAG = "DigitalRelease"
        private val MOVIE_TYPES = setOf("movie", "movies", "film", "films")
        private val SERIES_TYPES = setOf("series", "tv", "show", "shows", "tvseries", "tvshow", "episode", "episodes")

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
