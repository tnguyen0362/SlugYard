package com.sluggyard.tv.data.repository

import android.util.Log
import com.sluggyard.tv.data.remote.api.ImdbApiParentsGuideCategory
import com.sluggyard.tv.data.remote.api.ParentalGuideApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolved parental guide data with a single severity per category, determined
 * by the highest-voted severity level from the API.
 */
data class ParentalGuideResult(
    val nudity: String? = null,
    val violence: String? = null,
    val profanity: String? = null,
    val alcohol: String? = null,
    val frightening: String? = null
)

/**
 * Fetches and caches IMDb parental-guide categories, collapsing each category
 * to its dominant non-"none" severity level.
 */
@Singleton
class ParentalGuideRepository @Inject constructor(
    private val api: ParentalGuideApi
) {
    private val cache = ConcurrentHashMap<String, ParentalGuideResult>()

    suspend fun getParentalGuide(imdbId: String): ParentalGuideResult? {
        if (!imdbId.startsWith("tt")) return null
        cache[imdbId]?.let { return it }

        return try {
            val response = api.getParentsGuide(imdbId)
            val categories = response.body()?.parentsGuide
            if (!response.isSuccessful || categories.isNullOrEmpty()) {
                null
            } else {
                val result = collapseToResult(categories)
                cache[imdbId] = result
                result
            }
        } catch (e: Exception) {
            Log.e("ParentalGuide", "Failed to fetch parental guide for $imdbId", e)
            null
        }
    }

    private fun collapseToResult(categories: List<ImdbApiParentsGuideCategory>): ParentalGuideResult {
        val byKey = categories.associateBy { it.category.uppercase() }
        return ParentalGuideResult(
            nudity = dominantSeverity(byKey["SEXUAL_CONTENT"]),
            violence = dominantSeverity(byKey["VIOLENCE"]),
            profanity = dominantSeverity(byKey["PROFANITY"]),
            alcohol = dominantSeverity(byKey["ALCOHOL_DRUGS"]),
            frightening = dominantSeverity(byKey["FRIGHTENING_INTENSE_SCENES"])
        )
    }

    /**
     * Picks the severity level with the highest vote count, excluding "none".
     * If "none" outvotes every other level, the category is treated as having
     * no concern and null is returned.
     */
    private fun dominantSeverity(category: ImdbApiParentsGuideCategory?): String? {
        val breakdowns = category?.severityBreakdowns ?: return null
        val noneVotes = breakdowns.firstOrNull { it.severityLevel.lowercase() == "none" }?.voteCount ?: 0
        val leader = breakdowns
            .filter { it.severityLevel.lowercase() != "none" }
            .maxByOrNull { it.voteCount }
            ?: return null
        if (leader.voteCount <= noneVotes) return null
        return leader.severityLevel.lowercase()
    }
}