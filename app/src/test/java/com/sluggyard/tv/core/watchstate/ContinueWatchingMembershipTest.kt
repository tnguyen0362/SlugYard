package com.sluggyard.tv.core.watchstate

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMembershipTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 7)

    @Test
    fun `mid episode resume is emitted`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = listOf(resume("tt1", season = 1, episode = 2, fraction = 0.5)),
            seeds = emptyList(),
            episodeOrder = emptyMap(),
            dismissedShowIds = emptySet(),
        )
        assertEquals(1, rows.size)
        val row = rows.single() as CwRailRow.Resume
        assertEquals(1, row.season)
        assertEquals(2, row.episode)
        assertEquals(0.5, row.progressFraction!!, 0.001)
    }

    @Test
    fun `ninety seven percent still resumes while ninety eight is gone`() {
        val under = ContinueWatchingMembership.build(
            resumeInputs = listOf(resume("tt1", fraction = 0.97)),
            seeds = emptyList(),
            episodeOrder = emptyMap(),
            dismissedShowIds = emptySet(),
        )
        assertEquals(1, under.size)

        val over = ContinueWatchingMembership.build(
            resumeInputs = listOf(resume("tt1", fraction = 0.98)),
            seeds = emptyList(),
            episodeOrder = emptyMap(),
            dismissedShowIds = emptySet(),
        )
        assertEquals(0, over.size)
    }

    @Test
    fun `completed seed advances to next episode not the completed one`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = emptyList(),
            seeds = listOf(seed(season = 1, episode = 4)),
            episodeOrder = mapOf("tt1" to order("1:1", "1:2", "1:3", "1:4", "1:5")),
            dismissedShowIds = emptySet(),
        )
        val row = rows.single() as CwRailRow.NextUp
        assertEquals(1, row.season)
        assertEquals(5, row.episode)
    }

    @Test
    fun `seed at last episode of series produces no tile`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = emptyList(),
            seeds = listOf(seed(season = 2, episode = 8)),
            episodeOrder = mapOf("tt1" to order("1:1", "2:8")),
            dismissedShowIds = emptySet(),
        )
        assertEquals(0, rows.size)
    }

    @Test
    fun `seed with no order advances to next episode number`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = emptyList(),
            seeds = listOf(seed(season = 1, episode = 2)),
            episodeOrder = emptyMap(),
            dismissedShowIds = emptySet(),
        )
        val row = rows.single() as CwRailRow.NextUp
        assertEquals(1, row.season)
        assertEquals(3, row.episode)
    }

    @Test
    fun `completed at ninety eight becomes next episode tile without midstream resume`() {
        // Same product rule: resume ≥98% is gone; seed S1E4 + order → Next Up S1E5.
        val rows = ContinueWatchingMembership.build(
            resumeInputs = listOf(resume("tt1", season = 1, episode = 4, fraction = 0.98)),
            seeds = listOf(seed(season = 1, episode = 4)),
            episodeOrder = mapOf("tt1" to order("1:1", "1:2", "1:3", "1:4", "1:5")),
            dismissedShowIds = emptySet(),
        )
        assertEquals(1, rows.size)
        val row = rows.single() as CwRailRow.NextUp
        assertEquals(1, row.season)
        assertEquals(5, row.episode)
    }

    @Test
    fun `resume wins over next up for same series`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = listOf(resume("tt1", season = 1, episode = 3, fraction = 0.5)),
            seeds = listOf(seed(season = 1, episode = 2)),
            episodeOrder = mapOf("tt1" to order("1:1", "1:2", "1:3")),
            dismissedShowIds = emptySet(),
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single() is CwRailRow.Resume)
    }

    @Test
    fun `movie resume only and no next up`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = listOf(resume("movie1", contentType = "movie", fraction = 0.5)),
            seeds = emptyList(),
            episodeOrder = emptyMap(),
            dismissedShowIds = emptySet(),
        )
        assertEquals(1, rows.size)
        assertTrue(rows.single() is CwRailRow.Resume)
    }

    @Test
    fun `rows sort by last activity descending`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = listOf(
                resume("old", fraction = 0.5, activity = 100L),
                resume("new", fraction = 0.4, activity = 300L),
            ),
            seeds = emptyList(),
            episodeOrder = emptyMap(),
            dismissedShowIds = emptySet(),
        )
        assertEquals(listOf("new", "old"), rows.map { it.seriesKey })
    }

    @Test
    fun `dismissed series hides next up only`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = emptyList(),
            seeds = listOf(seed(season = 1, episode = 2)),
            episodeOrder = mapOf("tt1" to order("1:1", "1:2", "1:3")),
            dismissedShowIds = setOf("tt1"),
        )
        assertEquals(0, rows.size)
    }

    @Test
    fun `unaired next episode drops show when unaired is off`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = emptyList(),
            seeds = listOf(seed(season = 1, episode = 2)),
            episodeOrder = mapOf(
                "tt1" to listOf(
                    CwEpisodeOrderItem(1, 1),
                    CwEpisodeOrderItem(1, 2),
                    CwEpisodeOrderItem(1, 3, releaseDate = today.plusDays(7)),
                ),
            ),
            dismissedShowIds = emptySet(),
            settings = CwMembershipSettings(showUnaired = false),
            today = today,
        )
        assertEquals(0, rows.size)
    }

    @Test
    fun `unaired next episode allowed when show unaired is on`() {
        val rows = ContinueWatchingMembership.build(
            resumeInputs = emptyList(),
            seeds = listOf(seed(season = 1, episode = 2)),
            episodeOrder = mapOf(
                "tt1" to listOf(
                    CwEpisodeOrderItem(1, 1),
                    CwEpisodeOrderItem(1, 2),
                    CwEpisodeOrderItem(1, 3, releaseDate = today.plusDays(7)),
                ),
            ),
            dismissedShowIds = emptySet(),
            settings = CwMembershipSettings(showUnaired = true),
            today = today,
        )
        assertEquals(1, rows.size)
        val row = rows.single() as CwRailRow.NextUp
        assertEquals(3, row.episode)
    }

    @Test
    fun `sub threshold and zero duration are never resumes`() {
        val sub = ContinueWatchingMembership.build(
            resumeInputs = listOf(resume("tt1", fraction = 0.01)),
            seeds = emptyList(),
            episodeOrder = emptyMap(),
            dismissedShowIds = emptySet(),
        )
        assertEquals(0, sub.size)
    }

    private fun resume(
        key: String,
        contentType: String = "series",
        season: Int? = null,
        episode: Int? = null,
        fraction: Double,
        activity: Long = 1_000L,
    ) = CwResumeInput(
        seriesKey = key,
        contentId = key,
        contentType = contentType,
        title = key,
        season = season,
        episode = episode,
        positionMs = (fraction * 100_000L).toLong(),
        durationMs = 100_000L,
        updatedAtEpochMs = activity,
    )

    private fun seed(season: Int, episode: Int, activity: Long = 1_000L) = CwNextUpSeed(
        seriesKey = "tt1",
        showId = "tt1",
        season = season,
        episode = episode,
        watchedAtMs = activity,
    )

    private fun order(vararg raw: String): List<CwEpisodeOrderItem> = raw.map { segment ->
        val (s, e) = segment.split(":")
        CwEpisodeOrderItem(s.toInt(), e.toInt())
    }
}
