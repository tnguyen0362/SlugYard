package com.sluggyard.tv.data.repository

import android.content.Context
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.core.tmdb.TmdbService
import com.sluggyard.tv.data.local.LayoutPreferenceDataStore
import com.sluggyard.tv.data.local.TraktSettingsDataStore
import com.sluggyard.tv.data.local.WatchedSeriesStateHolder
import com.sluggyard.tv.data.remote.api.TraktApi
import com.sluggyard.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktHistoryRemoveCountDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktHistoryAddNotFoundDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktIdsDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktShowDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktWatchedShowEpisodeDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktWatchedShowSeasonDto
import com.sluggyard.tv.domain.model.WatchProgress
import com.sluggyard.tv.domain.repository.MetaRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for [TraktProgressService]'s pure watched-state
 * classification / DTO-mapping helpers, ahead of any Phase 2 rewrite. These pin
 * down current behavior as a regression oracle rather than a new spec.
 */
class TraktProgressServiceRulesTest {

    private fun newService(): TraktProgressService = TraktProgressService(
        appContext = mockk<Context>(relaxed = true),
        traktApi = mockk<TraktApi>(relaxed = true),
        traktAuthService = mockk(relaxed = true),
        metaRepository = mockk<MetaRepository>(relaxed = true),
        tmdbService = mockk<TmdbService>(relaxed = true),
        traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true),
        layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
        traktEpisodeMappingService = mockk(relaxed = true),
        profileManager = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileId } returns MutableStateFlow(0)
        },
        watchedSeriesStateHolder = mockk<WatchedSeriesStateHolder>(relaxed = true) {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        }
    )

    private fun progress(
        contentId: String = "tt1",
        contentType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        source: String = WatchProgress.SOURCE_LOCAL,
        position: Long = 1L,
        duration: Long = 100L
    ): WatchProgress = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = 0L,
        source = source
    )

    // --- isKnownWatchedPlayback ---

    @Test
    fun `isKnownWatchedPlayback is false when the progress is still in progress`() {
        val service = newService()
        val inProgress = progress(position = 10L, duration = 100L)
        assertFalse(service.isKnownWatchedPlayback(inProgress, setOf("tt1"), emptyMap(), emptySet()))
    }

    @Test
    fun `isKnownWatchedPlayback checks movie membership in the watched movies set`() {
        val service = newService()
        val completedMovie = progress(contentId = "tt1", contentType = "movie", position = 100L, duration = 100L)
        assertTrue(service.isKnownWatchedPlayback(completedMovie, setOf("tt1"), emptyMap(), emptySet()))
        assertFalse(service.isKnownWatchedPlayback(completedMovie, setOf("tt2"), emptyMap(), emptySet()))
    }

    @Test
    fun `isKnownWatchedPlayback is false for series progress missing season or episode`() {
        val service = newService()
        val noSeason = progress(contentId = "tt1", contentType = "series", season = null, episode = 3, position = 100L, duration = 100L)
        assertFalse(service.isKnownWatchedPlayback(noSeason, emptySet(), mapOf("tt1" to setOf(1 to 1)), emptySet()))
    }

    @Test
    fun `isKnownWatchedPlayback checks season-episode membership in the watched episodes map`() {
        val service = newService()
        val watchedEpisode = progress(contentId = "tt1", contentType = "series", season = 1, episode = 2, position = 100L, duration = 100L)
        assertTrue(
            service.isKnownWatchedPlayback(
                watchedEpisode,
                watchedMovies = emptySet(),
                watchedEpisodes = mapOf("tt1" to setOf(1 to 2)),
                fullyWatchedSeries = emptySet()
            )
        )
        assertFalse(
            service.isKnownWatchedPlayback(
                watchedEpisode,
                watchedMovies = emptySet(),
                watchedEpisodes = mapOf("tt1" to setOf(1 to 3)),
                fullyWatchedSeries = emptySet()
            )
        )
    }

    // --- hasSuccessfulHistoryAdd / hasHistoryAddNotFound ---

    @Test
    fun `hasSuccessfulHistoryAdd is true only when some added count is positive`() {
        val service = newService()
        assertFalse(service.hasSuccessfulHistoryAdd(null))
        assertFalse(service.hasSuccessfulHistoryAdd(TraktHistoryAddResponseDto(added = null)))
        assertFalse(
            service.hasSuccessfulHistoryAdd(
                TraktHistoryAddResponseDto(added = TraktHistoryRemoveCountDto(movies = 0, episodes = 0, shows = 0, seasons = 0))
            )
        )
        assertTrue(
            service.hasSuccessfulHistoryAdd(
                TraktHistoryAddResponseDto(added = TraktHistoryRemoveCountDto(episodes = 1))
            )
        )
    }

    @Test
    fun `hasHistoryAddNotFound is true when any not-found bucket is non-empty`() {
        val service = newService()
        assertFalse(service.hasHistoryAddNotFound(null))
        assertFalse(service.hasHistoryAddNotFound(TraktHistoryAddResponseDto(notFound = null)))
        assertFalse(
            service.hasHistoryAddNotFound(
                TraktHistoryAddResponseDto(notFound = TraktHistoryAddNotFoundDto())
            )
        )
        assertTrue(
            service.hasHistoryAddNotFound(
                TraktHistoryAddResponseDto(notFound = TraktHistoryAddNotFoundDto(movies = listOf(mockk(relaxed = true))))
            )
        )
    }

    // --- isSeriesEpisodeProgress ---

    @Test
    fun `isSeriesEpisodeProgress requires series or tv type with both season and episode`() {
        val service = newService()
        assertTrue(service.isSeriesEpisodeProgress(progress(contentType = "series", season = 1, episode = 1)))
        assertTrue(service.isSeriesEpisodeProgress(progress(contentType = "tv", season = 1, episode = 1)))
        assertFalse(service.isSeriesEpisodeProgress(progress(contentType = "movie", season = 1, episode = 1)))
        assertFalse(service.isSeriesEpisodeProgress(progress(contentType = "series", season = null, episode = 1)))
    }

    // --- watchedMovieLookupKeys ---

    @Test
    fun `watchedMovieLookupKeys builds candidate keys from available id fields`() {
        val service = newService()
        assertEquals(emptyList<String>(), service.watchedMovieLookupKeys(null))

        val keys = service.watchedMovieLookupKeys(
            TraktIdsDto(trakt = 5, slug = "movie-slug", imdb = "tt9", tmdb = 42)
        )

        assertEquals(listOf("tt9", "tmdb:42", "trakt:5", "movie-slug"), keys)
    }

    // --- toTraktUtcDateTime ---

    @Test
    fun `toTraktUtcDateTime formats a known epoch millis value as UTC ISO-ish string`() {
        val service = newService()
        // 2021-01-01T00:00:00.000Z
        assertEquals("2021-01-01T00:00:00.000Z", service.toTraktUtcDateTime(1609459200000L))
    }

    // --- mapWatchedShowSeed ---

    @Test
    fun `mapWatchedShowSeed returns null when the show is missing`() {
        val service = newService()
        assertNull(service.mapWatchedShowSeed(TraktWatchedShowItemDto(show = null), useFurthestEpisode = true))
    }

    @Test
    fun `mapWatchedShowSeed picks the furthest season-episode when useFurthestEpisode is true`() {
        val service = newService()
        val item = TraktWatchedShowItemDto(
            show = TraktShowDto(title = "Show", ids = TraktIdsDto(imdb = "tt1")),
            seasons = listOf(
                TraktWatchedShowSeasonDto(
                    number = 1,
                    episodes = listOf(TraktWatchedShowEpisodeDto(number = 5, plays = 1, lastWatchedAt = "2021-01-01T00:00:00.000Z"))
                ),
                TraktWatchedShowSeasonDto(
                    number = 2,
                    episodes = listOf(TraktWatchedShowEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2020-01-01T00:00:00.000Z"))
                )
            )
        )

        val seed = service.mapWatchedShowSeed(item, useFurthestEpisode = true)

        assertEquals("tt1", seed?.contentId)
        assertEquals(2, seed?.season)
        assertEquals(1, seed?.episode)
        assertEquals(WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS, seed?.source)
    }

    @Test
    fun `mapWatchedShowSeed picks the most recently watched episode when useFurthestEpisode is false`() {
        val service = newService()
        val item = TraktWatchedShowItemDto(
            show = TraktShowDto(title = "Show", ids = TraktIdsDto(imdb = "tt1")),
            seasons = listOf(
                TraktWatchedShowSeasonDto(
                    number = 1,
                    episodes = listOf(TraktWatchedShowEpisodeDto(number = 5, plays = 1, lastWatchedAt = "2021-01-01T00:00:00.000Z"))
                ),
                TraktWatchedShowSeasonDto(
                    number = 2,
                    episodes = listOf(TraktWatchedShowEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2020-01-01T00:00:00.000Z"))
                )
            )
        )

        val seed = service.mapWatchedShowSeed(item, useFurthestEpisode = false)

        assertEquals(1, seed?.season)
        assertEquals(5, seed?.episode)
    }

    @Test
    fun `mapWatchedShowSeed ignores season 0 (specials) and unplayed episodes`() {
        val service = newService()
        val item = TraktWatchedShowItemDto(
            show = TraktShowDto(title = "Show", ids = TraktIdsDto(imdb = "tt1")),
            seasons = listOf(
                TraktWatchedShowSeasonDto(
                    number = 0,
                    episodes = listOf(TraktWatchedShowEpisodeDto(number = 99, plays = 1, lastWatchedAt = "2099-01-01T00:00:00.000Z"))
                ),
                TraktWatchedShowSeasonDto(
                    number = 1,
                    episodes = listOf(
                        TraktWatchedShowEpisodeDto(number = 1, plays = 0, lastWatchedAt = "2022-01-01T00:00:00.000Z"),
                        TraktWatchedShowEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2020-01-01T00:00:00.000Z")
                    )
                )
            )
        )

        val seed = service.mapWatchedShowSeed(item, useFurthestEpisode = true)

        assertEquals(1, seed?.season)
        assertEquals(2, seed?.episode)
    }
}
