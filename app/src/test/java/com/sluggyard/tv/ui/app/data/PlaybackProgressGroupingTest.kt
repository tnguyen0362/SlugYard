package com.sluggyard.tv.ui.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressGroupingTest {
    @Test
    fun groupsEpisodesByExplicitParentAndKeepsNewestCheckpoint() {
        val checkpoints = listOf(
            checkpoint("tt-show:1:1", parentId = "tt-show", episode = 1, updatedAt = 10L),
            checkpoint("tt-show:1:2", parentId = "tt-show", episode = 2, updatedAt = 20L),
            checkpoint("tt-other:1:1", parentId = "tt-other", episode = 1, updatedAt = 15L),
        )

        val grouped = groupPlaybackCheckpoints(checkpoints)

        assertEquals(listOf("tt-show:1:2", "tt-other:1:1"), grouped.map { it.contentId })
    }

    @Test
    fun infersProviderPrefixedParentWhenCheckpointHasNoParent() {
        val checkpoints = listOf(
            checkpoint("kitsu:987:2:4", episode = 4, updatedAt = 10L),
            checkpoint("kitsu:987:2:5", episode = 5, updatedAt = 20L),
        )

        val grouped = groupPlaybackCheckpoints(checkpoints)

        assertEquals(listOf("kitsu:987:2:5"), grouped.map { it.contentId })
    }

    @Test
    fun keepsMoviesAsSeparateEntries() {
        val checkpoints = listOf(
            checkpoint("movie-a", updatedAt = 10L),
            checkpoint("movie-b", updatedAt = 20L),
        )

        val grouped = groupPlaybackCheckpoints(checkpoints)

        assertEquals(listOf("movie-b", "movie-a"), grouped.map { it.contentId })
    }

    private fun checkpoint(
        contentId: String,
        parentId: String? = null,
        episode: Int? = null,
        updatedAt: Long,
    ) = PlaybackCheckpoint(
        contentId = contentId,
        contentType = if (episode == null) "movie" else "series",
        title = contentId,
        parentId = parentId,
        parentType = parentId?.let { "series" },
        positionMs = 1_000L,
        durationMs = 10_000L,
        updatedAtEpochMs = updatedAt,
        season = episode?.let { 1 },
        episode = episode,
    )
}
