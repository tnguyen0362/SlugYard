package com.sluggyard.tv.ui.app.streams

import java.time.Clock
import java.time.LocalDate

/**
 * SlugYard-side digital-release gate (AIOStreams-equivalent, using our TMDB key).
 *
 * When a movie is not digitally released yet, auto-play should refuse CAM/TS/SCR/trailer
 * junk so Play does not land on dud early rips. Fail open only when TMDB truly has no
 * usable release signal — theatrical-without-past-digital must be [Status.NOT_YET].
 */
object DigitalReleasePolicy {
    enum class Status {
        /** Digital/Physical/TV release date is today or earlier, or theatrical is 1+ year old. */
        AVAILABLE,
        /** Still theatrical-only / future digital — treat early-release junk as unplayable for auto-pick. */
        NOT_YET,
        /** No key / no TMDB rows at all — do not block. */
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
        // Non-empty TMDB payload without a past digital date — treat as pre-digital, not fail-open.
        return Status.NOT_YET
    }

    /**
     * When release-dates rows are empty but the movie detail primary [releaseDate] exists,
     * use it as a theatrical stand-in so we do not fail open on theatrical-only titles.
     */
    fun statusFromPrimaryReleaseDate(
        releaseDate: LocalDate?,
        clock: Clock = Clock.systemUTC(),
    ): Status {
        val theatrical = releaseDate ?: return Status.UNKNOWN
        return status(
            rows = listOf(ReleaseRow(type = 3, date = theatrical)),
            clock = clock,
        )
    }

    fun isEarlyReleaseJunk(text: String): Boolean =
        EARLY_RELEASE_JUNK.containsMatchIn(text.lowercase())

    /**
     * Auto-play eligibility for a candidate given digital-release status.
     *
     * [Status.NOT_YET] blocks **all** auto-play — not only CAM/TS tags. Early WEB-DL /
     * REMUX leaks for theatrical titles were still winning auto-pick (e.g. Brand New Day).
     * UNKNOWN / AVAILABLE / null fail open so missing TMDB data does not brick Play.
     */
    fun allowsAutoPlay(status: Status?, text: String): Boolean {
        if (status != Status.NOT_YET) return true
        return false
    }

    fun parseReleaseDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }

    private val DIGITAL_TYPES = 4..6
    private val THEATRICAL_TYPES = 1..3
    // Match CAMRip / HDTS / TS.CAM etc., not only spaced whole-word "CAM".
    private val EARLY_RELEASE_JUNK = Regex(
        "\\b(cam(?:rip)?|hdcam|hqcam|telesync|telecine|tscam|screener|dvdscr|bdscr|r5|pdvd|predvd|" +
            "workprint|wp|trailer|teaser)\\b|\\b(?:hd)?ts\\b|\\btc\\b|\\[\\s*cam(?:rip)?\\s*]",
    )
}
