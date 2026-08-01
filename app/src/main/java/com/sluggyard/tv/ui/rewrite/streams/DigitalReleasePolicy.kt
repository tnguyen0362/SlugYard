package com.sluggyard.tv.ui.rewrite.streams

import java.time.Clock
import java.time.LocalDate

/**
 * SlugYard-side digital-release gate (AIOStreams-equivalent, using our TMDB key).
 *
 * When a movie is not digitally released yet, auto-play should refuse CAM/TS/SCR/trailer
 * junk so Play does not land on dud early rips. Missing TMDB data fails open.
 */
object DigitalReleasePolicy {
    enum class Status {
        /** Digital/Physical/TV release date is today or earlier, or theatrical is 1+ year old. */
        AVAILABLE,
        /** Still theatrical-only / future digital — treat early-release junk as unplayable for auto-pick. */
        NOT_YET,
        /** No key, no rows, or ambiguous — do not block. */
        UNKNOWN,
    }

    data class ReleaseRow(val type: Int, val date: LocalDate)

    fun status(
        rows: List<ReleaseRow>,
        clock: Clock = Clock.systemUTC(),
    ): Status {
        if (rows.isEmpty()) return Status.UNKNOWN
        val today = LocalDate.now(clock)
        val digital = rows.filter { it.type in DIGITAL_TYPES }
        if (digital.any { !it.date.isAfter(today) }) return Status.AVAILABLE

        val theatrical = rows.filter { it.type in THEATRICAL_TYPES }.minOfOrNull { it.date }
        if (theatrical != null && theatrical.isBefore(today.minusDays(365))) {
            return Status.AVAILABLE
        }
        if (digital.any { it.date.isAfter(today) }) return Status.NOT_YET
        if (theatrical != null && theatrical.isAfter(today)) return Status.NOT_YET
        // Theatrical already happened (or unknown types only) but no past digital row.
        if (theatrical != null && !theatrical.isAfter(today)) return Status.NOT_YET
        return Status.UNKNOWN
    }

    fun isEarlyReleaseJunk(text: String): Boolean =
        EARLY_RELEASE_JUNK.containsMatchIn(text.lowercase())

    /**
     * Auto-play eligibility for a candidate given digital-release status.
     * Only [Status.NOT_YET] blocks junk; everything else allows the candidate through.
     */
    fun allowsAutoPlay(status: Status?, text: String): Boolean {
        if (status != Status.NOT_YET) return true
        return !isEarlyReleaseJunk(text)
    }

    fun parseReleaseDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }

    private val DIGITAL_TYPES = 4..6
    private val THEATRICAL_TYPES = 1..3
    private val EARLY_RELEASE_JUNK = Regex(
        "\\b(cam|hdcam|hqcam|telesync|telecine|tscam|screener|dvdscr|bdscr|r5|pdvd|predvd|" +
            "workprint|wp|trailer|teaser)\\b|\\b(?:hd)?ts\\b|\\btc\\b",
    )
}
