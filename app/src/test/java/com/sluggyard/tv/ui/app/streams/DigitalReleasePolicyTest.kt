package com.sluggyard.tv.ui.app.streams

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigitalReleasePolicyTest {
    private val today = LocalDate.of(2026, 7, 31)
    private val clock: Clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @Test
    fun pastDigitalReleaseIsAvailable() {
        val status = DigitalReleasePolicy.status(
            listOf(DigitalReleasePolicy.ReleaseRow(4, today.minusDays(3))),
            clock,
        )
        assertEquals(DigitalReleasePolicy.Status.AVAILABLE, status)
    }

    @Test
    fun futureDigitalOnlyIsNotYet() {
        val status = DigitalReleasePolicy.status(
            listOf(
                DigitalReleasePolicy.ReleaseRow(3, today.minusDays(10)),
                DigitalReleasePolicy.ReleaseRow(4, today.plusDays(14)),
            ),
            clock,
        )
        assertEquals(DigitalReleasePolicy.Status.NOT_YET, status)
    }

    @Test
    fun oldTheatricalWithoutDigitalRowsIsAvailable() {
        val status = DigitalReleasePolicy.status(
            listOf(DigitalReleasePolicy.ReleaseRow(3, today.minusDays(400))),
            clock,
        )
        assertEquals(DigitalReleasePolicy.Status.AVAILABLE, status)
    }

    @Test
    fun emptyRowsUnknown() {
        assertEquals(DigitalReleasePolicy.Status.UNKNOWN, DigitalReleasePolicy.status(emptyList(), clock))
    }

    @Test
    fun nonEmptyWithoutPastDigitalIsNotYet() {
        // Typed rows that aren't digital and aren't old theatrical still block early junk.
        val status = DigitalReleasePolicy.status(
            listOf(DigitalReleasePolicy.ReleaseRow(3, today.minusDays(5))),
            clock,
        )
        assertEquals(DigitalReleasePolicy.Status.NOT_YET, status)
    }

    @Test
    fun primaryReleaseDateFallback() {
        assertEquals(
            DigitalReleasePolicy.Status.NOT_YET,
            DigitalReleasePolicy.statusFromPrimaryReleaseDate(today.minusDays(2), clock),
        )
        assertEquals(
            DigitalReleasePolicy.Status.AVAILABLE,
            DigitalReleasePolicy.statusFromPrimaryReleaseDate(today.minusDays(400), clock),
        )
        assertEquals(
            DigitalReleasePolicy.Status.UNKNOWN,
            DigitalReleasePolicy.statusFromPrimaryReleaseDate(null, clock),
        )
    }

    @Test
    fun earlyJunkBlockedOnlyWhenNotYet() {
        assertFalse(DigitalReleasePolicy.allowsAutoPlay(DigitalReleasePolicy.Status.NOT_YET, "Movie 2026 CAM"))
        assertFalse(DigitalReleasePolicy.allowsAutoPlay(DigitalReleasePolicy.Status.NOT_YET, "Movie.2026.CAMRip.x264"))
        assertFalse(DigitalReleasePolicy.allowsAutoPlay(DigitalReleasePolicy.Status.NOT_YET, "Movie 2026 1080p WEB-DL"))
        assertTrue(DigitalReleasePolicy.allowsAutoPlay(DigitalReleasePolicy.Status.AVAILABLE, "Movie 2026 CAM"))
        assertTrue(DigitalReleasePolicy.allowsAutoPlay(null, "Movie 2026 CAM"))
    }

    @Test
    fun detectsCamTsTrailer() {
        assertTrue(DigitalReleasePolicy.isEarlyReleaseJunk("Foo.2026.HDTS.x264"))
        assertTrue(DigitalReleasePolicy.isEarlyReleaseJunk("Foo.2026.CAMRip.x264"))
        assertTrue(DigitalReleasePolicy.isEarlyReleaseJunk("Foo [CAM] 2026"))
        assertTrue(DigitalReleasePolicy.isEarlyReleaseJunk("Foo Official Trailer"))
        assertFalse(DigitalReleasePolicy.isEarlyReleaseJunk("Foo.2026.1080p.WEB-DL.DDP5.1"))
    }

    // Series gate must NOT reuse the movie theatrical ladder: a past first air means
    // aired episodes have real sources. Regression for the "Love Beneath the Night Sky"
    // ep-7 false gate (premiered Jul 6 2026, gated NOT_YET for a year).
    @Test
    fun seriesPastFirstAirIsAvailable() {
        assertEquals(
            DigitalReleasePolicy.Status.AVAILABLE,
            DigitalReleasePolicy.seriesStatus("Returning Series", "2026-07-06", clock),
        )
        assertEquals(
            DigitalReleasePolicy.Status.AVAILABLE,
            DigitalReleasePolicy.seriesStatus("Ended", "2025-12-12", clock),
        )
    }

    @Test
    fun seriesPlannedWithNoAirYetIsNotYet() {
        assertEquals(
            DigitalReleasePolicy.Status.NOT_YET,
            DigitalReleasePolicy.seriesStatus("In Production", "2026-09-01", clock),
        )
        assertEquals(
            DigitalReleasePolicy.Status.NOT_YET,
            DigitalReleasePolicy.seriesStatus("Planned", null, clock),
        )
    }

    @Test
    fun seriesPreReleaseWithAiredDateWinsPastDate() {
        // TMDB keeps "in production" up to premiere day; the aired date must win.
        assertEquals(
            DigitalReleasePolicy.Status.AVAILABLE,
            DigitalReleasePolicy.seriesStatus("In Production", "2026-07-06", clock),
        )
    }

    @Test
    fun seriesFutureFirstAirIsNotYet() {
        // Unknown/blank status + a future first air is still pre-release.
        assertEquals(
            DigitalReleasePolicy.Status.NOT_YET,
            DigitalReleasePolicy.seriesStatus("  ", "2026-09-01", clock),
        )
        // AIRED statuses always win: a returning/ended show exists now regardless of date.
        assertEquals(
            DigitalReleasePolicy.Status.AVAILABLE,
            DigitalReleasePolicy.seriesStatus("Returning Series", "2026-09-01", clock),
        )
    }

    @Test
    fun airedStatusWithoutDateIsAvailable() {
        assertEquals(
            DigitalReleasePolicy.Status.AVAILABLE,
            DigitalReleasePolicy.seriesStatus("Returning Series", null, clock),
        )
    }

    @Test
    fun seriesNoSignalIsUnknown() {
        assertEquals(
            DigitalReleasePolicy.Status.UNKNOWN,
            DigitalReleasePolicy.seriesStatus(null, null, clock),
        )
    }

    @Test
    fun episodeReleaseUsesEpisodeAirDate() {
        assertEquals(
            DigitalReleasePolicy.Status.AVAILABLE,
            DigitalReleasePolicy.episodeStatus("2026-07-30", clock),
        )
        assertEquals(
            DigitalReleasePolicy.Status.NOT_YET,
            DigitalReleasePolicy.episodeStatus("2026-08-01", clock),
        )
        assertEquals(
            DigitalReleasePolicy.Status.UNKNOWN,
            DigitalReleasePolicy.episodeStatus(null, clock),
        )
    }
}
