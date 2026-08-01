package com.sluggyard.tv.data.repository

import com.sluggyard.tv.data.remote.api.DonationsApi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class SupporterDonation(
    val key: String,
    val name: String,
    val date: String,
    val message: String?,
    val sortTimestamp: Long
)

data class DonationProgress(
    val progressPercent: Int
)

data class SupportersResult(
    val supporters: List<SupporterDonation>,
    val progress: DonationProgress?
)

/**
 * Loads the public donor list and monthly funding progress from the donations
 * API and shapes it into display-ready [SupporterDonation] entries.
 */
@Singleton
class SupportersRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val donationsApi: DonationsApi
) {

    suspend fun getSupporters(): Result<SupportersResult> = runCatching {
        val response = donationsApi.getDonations()
        if (!response.isSuccessful) {
            error(appContext.getString(com.sluggyard.tv.R.string.supporters_error_api_http, response.code()))
        }

        val body = response.body()
        val supporters = body
            ?.donations
            .orEmpty()
            .mapNotNull { donation ->
                val name = donation.name?.trim().orEmpty()
                val date = donation.date?.trim() ?: donation.createdAt?.trim() ?: ""
                if (name.isBlank() || date.isBlank()) return@mapNotNull null

                SupporterDonation(
                    key = donation.id?.trim()?.takeIf { it.isNotBlank() } ?: "$name|$date",
                    name = name,
                    date = date,
                    message = donation.message?.trim()?.takeIf { it.isNotBlank() },
                    sortTimestamp = parseTimestamp(date)
                )
            }
            .sortedByDescending { it.sortTimestamp }
            .mapIndexed { index, donation -> donation.copy(key = "${donation.key}#$index") }

        val progress = body
            ?.monthlyGoal
            ?.progressPercent
            ?.toInt()
            ?.coerceIn(0, 100)
            ?.let { DonationProgress(progressPercent = it) }

        SupportersResult(supporters = supporters, progress = progress)
    }

    private fun parseTimestamp(rawDate: String): Long =
        runCatching { Instant.parse(rawDate).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}