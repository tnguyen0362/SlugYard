package com.sluggyard.tv.core.aggregation

import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset

/**
 * One explicit Home rule for the spec's hide-unreleased option. Ambiguous or absent addon dates
 * stay visible; only a date we can establish is future-dated is filtered out.
 */
object ReleaseVisibilityPolicy {
    fun isVisible(releaseInfo: String?, hideUnreleased: Boolean, clock: Clock = Clock.systemUTC()): Boolean {
        if (!hideUnreleased) return true
        val releaseDate = parse(releaseInfo) ?: return true
        return !releaseDate.isAfter(LocalDate.now(clock))
    }

    private fun parse(value: String?): LocalDate? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching { LocalDate.parse(normalized.take(10)) }.getOrNull()
            ?: normalized.take(4).toIntOrNull()?.let { year -> Year.of(year).atDay(1) }
    }
}
