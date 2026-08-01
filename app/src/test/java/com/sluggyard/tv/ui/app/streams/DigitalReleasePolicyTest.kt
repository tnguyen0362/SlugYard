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
}
