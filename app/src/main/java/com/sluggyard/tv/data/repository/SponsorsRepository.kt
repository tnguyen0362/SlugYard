package com.sluggyard.tv.data.repository

import com.sluggyard.tv.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

data class DevelopmentSponsor(
    val id: String,
    val name: String,
    val channelUrl: String?,
    val createdAt: String,
    val sortTimestamp: Long
)

/**
 * Surfaces the project's development sponsors. The list is shipped in the build
 * configuration as a comma-separated string so it can be surfaced without a
 * network round-trip; this repository simply parses and orders it.
 */
@Singleton
class SponsorsRepository @Inject constructor() {

    suspend fun getSponsors(): Result<List<DevelopmentSponsor>> = runCatching {
        parseSponsorNames(BuildConfig.SPONSOR_NAMES)
    }

    internal fun parseSponsorNames(rawNames: String): List<DevelopmentSponsor> {
        return rawNames
            .split(",")
            .mapIndexedNotNull { index, rawName ->
                val name = rawName.trim()
                if (name.isBlank()) return@mapIndexedNotNull null
                DevelopmentSponsor(
                    id = "${name.lowercase()}|$index",
                    name = name,
                    channelUrl = null,
                    createdAt = "",
                    // Preserve original declaration order via a descending
                    // sentinel so earlier entries sort first.
                    sortTimestamp = (Int.MAX_VALUE - index).toLong()
                )
            }
    }
}