package com.sluggyard.tv.core.sync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseMergePolicyTest {
    @Test
    fun `newer timestamp wins`() {
        val local = progress(position = 10L, lastWatched = 100L)
        val remote = progress(position = 20L, lastWatched = 200L)

        assertEquals(remote, SupabaseMergePolicy.mergeProgress(local, remote))
    }

    @Test
    fun `equal timestamp uses deterministic canonical tie break`() {
        val first = progress(position = 10L, lastWatched = 100L)
        val second = progress(position = 20L, lastWatched = 100L)

        assertEquals(
            SupabaseMergePolicy.mergeProgress(first, second),
            SupabaseMergePolicy.mergeProgress(second, first),
        )
    }

    @Test
    fun `profile merge keeps records isolated and preserves local-only items`() {
        val local = listOf(library(profileId = 1, contentId = "local"), library(profileId = 2, contentId = "other"))
        val remote = listOf(library(profileId = 1, contentId = "remote"))

        assertEquals(
            setOf("1:local", "1:remote", "2:other"),
            SupabaseMergePolicy.mergeLibrary(local, remote).map { "${it.profileId}:${it.contentId}" }.toSet(),
        )
    }

    @Test
    fun `nullable season and episode survive wire decoding`() {
        val decoded = SupabaseSyncJson.decodeProgress(
            """{"profile_id":1,"progress_key":"movie-1","content_id":"movie-1","content_type":"movie","video_id":"","position":4,"duration":10,"last_watched":20,"season":null,"episode":null,"unknown":"ignored"}""",
        )

        assertNotNull(decoded)
        assertNull(decoded?.season)
        assertNull(decoded?.episode)
    }

    @Test
    fun `missing stable identity is rejected`() {
        val decoded = SupabaseSyncJson.decodeProgress(
            """{"profile_id":1,"content_id":"movie-1","content_type":"movie","position":4,"duration":10,"last_watched":20}""",
        )

        assertNull(decoded)
    }

    private fun progress(position: Long, lastWatched: Long) = CloudWatchProgress(
        profileId = 1,
        progressKey = "show-1-s1e1",
        contentId = "show-1",
        contentType = "series",
        videoId = "s1e1",
        season = 1,
        episode = 1,
        position = position,
        duration = 100L,
        lastWatched = lastWatched,
    )

    private fun library(profileId: Int, contentId: String) = CloudLibraryItem(
        profileId = profileId,
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        changedAt = 1L,
    )
}
