package com.sluggyard.tv.ui.app.streams

import android.util.Log
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves whether a movie has a past digital release via TMDB (types 4–6).
 * Results are cached per IMDb id for the process lifetime.
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
            val imdb = normalizeImdbId(contentId) ?: return@withContext DigitalReleasePolicy.Status.UNKNOWN
            cache[imdb]?.let { return@withContext it }

            val status = runCatching { lookup(imdb) }
                .onFailure { Log.w(TAG, "digital-release lookup failed for $imdb: ${it.message}") }
                .getOrDefault(DigitalReleasePolicy.Status.UNKNOWN)
            cache[imdb] = status
            status
        }

    private suspend fun lookup(imdbId: String): DigitalReleasePolicy.Status {
        val find = tmdbApi.findByExternalId(imdbId, apiKey)
        if (!find.isSuccessful) return DigitalReleasePolicy.Status.UNKNOWN
        val tmdbId = find.body()?.movieResults?.firstOrNull()?.id
            ?: return DigitalReleasePolicy.Status.UNKNOWN
        val releases = tmdbApi.getMovieReleaseDates(tmdbId, apiKey)
        if (!releases.isSuccessful) return DigitalReleasePolicy.Status.UNKNOWN
        val rows = releases.body()?.results.orEmpty()
            .flatMap { country -> country.releaseDates.orEmpty() }
            .mapNotNull { item ->
                val type = item.type ?: return@mapNotNull null
                val date = DigitalReleasePolicy.parseReleaseDate(item.releaseDate) ?: return@mapNotNull null
                DigitalReleasePolicy.ReleaseRow(type = type, date = date)
            }
        return DigitalReleasePolicy.status(rows)
    }

    companion object {
        private const val TAG = "DigitalRelease"
        private val MOVIE_TYPES = setOf("movie", "movies", "film", "films")

        fun normalizeImdbId(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank()) return null
            val base = value.substringBefore(':').substringBefore('/').trim()
            return base.takeIf { it.startsWith("tt") && it.length > 2 }
        }
    }
}
