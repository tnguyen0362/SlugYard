package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.data.local.WatchProgressSource
import com.sluggyard.tv.domain.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueWatchingSourceTest {
    @Test
    fun traktOnlyWhenAuthenticatedAndTraktSourceSelected() {
        assertTrue(useTraktContinueWatching(WatchProgressSource.TRAKT, traktAuthenticated = true))
        assertFalse(useTraktContinueWatching(WatchProgressSource.TRAKT, traktAuthenticated = false))
        assertFalse(useTraktContinueWatching(WatchProgressSource.SLUGYARD_SYNC, traktAuthenticated = true))
    }

    @Test
    fun mapsTraktPercentOnlyProgressForHomeRail() {
        val checkpoint = WatchProgress(
            contentId = "tt123",
            contentType = "series",
            name = "Show",
            poster = "https://example/poster.jpg",
            backdrop = null,
            logo = null,
            videoId = "tt123:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Pilot",
            position = 0L,
            duration = 0L,
            lastWatched = 1_700_000_000_000L,
            progressPercent = 42f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
        ).toContinueWatchingCheckpoint()

        assertEquals("tt123:1:2", checkpoint.contentId)
        assertEquals("tt123", checkpoint.parentId)
        assertEquals(1, checkpoint.season)
        assertEquals(2, checkpoint.episode)
        assertEquals(0L, checkpoint.positionMs)
        assertEquals(0.42, checkpoint.progressFraction!!, absoluteTolerance = 0.001)
        assertTrue(checkpoint.isResumable)
    }

    @Test
    fun positionWithoutDurationDoesNotForceZeroRemoteFraction() {
        val checkpoint = WatchProgress(
            contentId = "tt999",
            contentType = "movie",
            name = "Movie",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt999",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 12_000L,
            duration = 0L,
            lastWatched = 1L,
            progressPercent = null,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
        ).toContinueWatchingCheckpoint()

        assertEquals(12_000L, checkpoint.positionMs)
        assertEquals(null, checkpoint.remoteProgressFraction)
        assertEquals(null, checkpoint.progressFraction)
    }
}
