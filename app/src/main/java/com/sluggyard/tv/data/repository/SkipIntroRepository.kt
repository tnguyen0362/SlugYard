package com.sluggyard.tv.data.repository

import android.util.Log
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.data.remote.api.AniSkipApi
import com.sluggyard.tv.data.remote.api.ArmApi
import com.sluggyard.tv.data.remote.api.ArmEntry
import com.sluggyard.tv.data.remote.api.IntroDbApi
import com.sluggyard.tv.data.remote.api.IntroDbSegment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Cap how long IntroDB waits on AniSkip enrichment once opening segments are already known. */
private const val ANISKIP_ENRICH_TIMEOUT_MS = 2_500L

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // "intro", "op", "mixed-op", "ed", "mixed-ed", "recap", "outro", "credits", "ending"
    val provider: String   // "introdb", "aniskip"
)

/**
 * Aggregates skip-interval data from IntroDB and AniSkip, resolving anime
 * identifiers (IMDB / MAL / Kitsu) via the ARM service. Results are merged
 * per segment category with IntroDB taking priority.
 */
@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val aniSkipApi: AniSkipApi,
    private val armApi: ArmApi
) {
    private val intervalCache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val armEntriesCache = ConcurrentHashMap<String, List<ArmEntry>>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> = coroutineScope {
        if (imdbId == null) return@coroutineScope emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        intervalCache[cacheKey]?.let { return@coroutineScope it }

        // Fetch IntroDB and ARM/AniSkip in parallel. Prefer returning IntroDB promptly so
        // Skip Intro can surface while the viewer is still inside the opening window —
        // waiting on ARM+AniSkip was delaying the prompt by many seconds.
        val introDbDeferred = async {
            if (introDbConfigured) fetchIntroDb(imdbId, season, episode) else emptyList()
        }
        val aniSkipDeferred = async {
            val entries = resolveArmEntries(imdbId)
            val seasonEntry = entries.firstOrNull { it.season == season }
                ?: entries.getOrNull(season - 1)
                ?: entries.firstOrNull()
            val malId = seasonEntry?.myanimelist?.toString()
                ?: entries.firstOrNull()?.myanimelist?.toString()
            if (malId != null) fetchAniSkip(malId, episode) else emptyList()
        }

        val introDb = introDbDeferred.await()
        val aniSkip = if (introDb.any { segmentCategory(it.type) == "opening" }) {
            withTimeoutOrNull(ANISKIP_ENRICH_TIMEOUT_MS) { aniSkipDeferred.await() }.orEmpty()
        } else {
            aniSkipDeferred.await()
        }

        return@coroutineScope mergeByPriority(introDb, aniSkip)
            .also { result -> if (result.isNotEmpty()) intervalCache[cacheKey] = result }
    }

    suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> = coroutineScope {
        val cacheKey = "mal:$malId:$episode"
        intervalCache[cacheKey]?.let { return@coroutineScope it }

        val aniSkipDeferred = async { fetchAniSkip(malId, episode) }
        val imdbIdDeferred = async {
            try {
                armApi.resolveMalToImdb(malId = malId).takeIf { it.isSuccessful }?.body()?.imdb
            } catch (_: Exception) { null }
        }

        var introDb = emptyList<SkipInterval>()
        val imdbId = imdbIdDeferred.await()
        if (imdbId != null) {
            val entries = resolveArmEntries(imdbId)
            val season = entries.firstOrNull { it.myanimelist == malId.toIntOrNull() }?.season
                ?: (entries.indexOfFirst { it.myanimelist == malId.toIntOrNull() } + 1)
            introDb = if (introDbConfigured) fetchIntroDb(imdbId, season, episode) else emptyList()
        }

        return@coroutineScope mergeByPriority(introDb, aniSkipDeferred.await()).also { result -> if (result.isNotEmpty()) intervalCache[cacheKey] = result }
    }

    suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> = coroutineScope {
        val cacheKey = "kitsu:$kitsuId:$episode"
        intervalCache[cacheKey]?.let { return@coroutineScope it }

        val malIdDeferred = async {
            try {
                armApi.resolveKitsuToMal(kitsuId = kitsuId)
                    .takeIf { it.isSuccessful }?.body()?.myanimelist?.toString()
            } catch (_: Exception) { null }
        }
        val imdbIdDeferred = async {
            try {
                armApi.resolveKitsuToImdb(kitsuId = kitsuId).takeIf { it.isSuccessful }?.body()?.imdb
            } catch (_: Exception) { null }
        }
        val aniSkipDeferred = async {
            malIdDeferred.await()?.let { fetchAniSkip(it, episode) } ?: emptyList()
        }

        var introDb = emptyList<SkipInterval>()
        val imdbId = imdbIdDeferred.await()
        if (imdbId != null) {
            val entries = resolveArmEntries(imdbId)
            val season = entries.firstOrNull { it.kitsu == kitsuId.toIntOrNull() }?.season
                ?: (entries.indexOfFirst { it.kitsu == kitsuId.toIntOrNull() } + 1)
            introDb = if (introDbConfigured) fetchIntroDb(imdbId, season, episode) else emptyList()
        }

        return@coroutineScope mergeByPriority(introDb, aniSkipDeferred.await()).also { result -> if (result.isNotEmpty()) intervalCache[cacheKey] = result }
    }

    /**
     * Merges provider results into one best-of: fill each segment category
     * (opening / ending / recap) from the highest-priority provider that has
     * it. Arguments MUST be passed in priority order (IntroDB has the
     * broadest coverage, then AniSkip), so a partial result from one provider
     * never shadows a complete segment from another.
     */
    private fun mergeByPriority(vararg providerResults: List<SkipInterval>): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in providerResults) {
            for (interval in result) {
                val category = segmentCategory(interval.type) ?: continue
                chosen.putIfAbsent(category, interval)
            }
        }
        return chosen.values.toList()
    }

    private fun segmentCategory(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op" -> "opening"
        "outro", "ed", "mixed-ed", "credits", "ending" -> "ending"
        "recap" -> "recap"
        else -> null
    }

    private suspend fun fetchIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode)
            val data = response.body()
            if (!response.isSuccessful || data == null) {
                emptyList()
            } else {
                listOfNotNull(
                    data.intro.toSkipInterval("intro"),
                    data.recap.toSkipInterval("recap"),
                    data.outro.toSkipInterval("outro")
                )
            }
        } catch (error: Exception) {
            Log.w("SkipIntro", "IntroDB request failed for $imdbId S${season}E${episode}", error)
            emptyList()
        }
    }

    private fun IntroDbSegment?.toSkipInterval(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private suspend fun fetchAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")
            val response = aniSkipApi.getSkipTimes(malId, episode, types)
            val body = response.body()
            if (!response.isSuccessful || body?.found != true) {
                emptyList()
            } else {
                body.results?.map { result ->
                    SkipInterval(
                        startTime = result.interval.startTime,
                        endTime = result.interval.endTime,
                        type = result.skipType,
                        provider = "aniskip"
                    )
                } ?: emptyList()
            }
        } catch (error: Exception) {
            Log.w("SkipIntro", "AniSkip request failed for MAL $malId ep $episode", error)
            emptyList()
        }
    }

    private suspend fun resolveArmEntries(imdbId: String): List<ArmEntry> {
        armEntriesCache[imdbId]?.let { return it }
        val entries = try {
            armApi.resolveImdbToAll(imdbId).takeIf { it.isSuccessful }?.body() ?: emptyList()
        } catch (error: Exception) {
            Log.w("SkipIntro", "ARM identifier lookup failed for IMDb $imdbId", error)
            emptyList()
        }
        armEntriesCache[imdbId] = entries
        return entries
    }
}
