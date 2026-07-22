package com.sluggyard.tv.data.repository

import android.content.Context
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.core.tmdb.TmdbService
import com.sluggyard.tv.data.local.LayoutPreferenceDataStore
import com.sluggyard.tv.data.local.TraktAuthDataStore
import com.sluggyard.tv.data.local.TraktSettingsDataStore
import com.sluggyard.tv.data.local.WatchProgressPreferences
import com.sluggyard.tv.data.local.WatchedItemsPreferences
import com.sluggyard.tv.domain.model.WatchProgress
import com.sluggyard.tv.domain.repository.MetaRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for [WatchProgressRepositoryImpl]'s pure next-up-seed
 * merge/dedup/replacement decision helpers, ahead of any Phase 2 rewrite. These pin
 * down current behavior as a regression oracle rather than a new spec.
 */
class WatchProgressRepositoryImplRulesTest {

    private fun newRepository(): WatchProgressRepositoryImpl = WatchProgressRepositoryImpl(
        watchProgressPreferences = mockk(relaxed = true),
        traktAuthDataStore = mockk(relaxed = true),
        traktSettingsDataStore = mockk(relaxed = true),
        layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
        traktProgressService = mockk(relaxed = true),
        watchedItemsPreferences = mockk<WatchedItemsPreferences>(relaxed = true),
        metaRepository = mockk<MetaRepository>(relaxed = true),
        tmdbService = mockk<TmdbService>(relaxed = true),
        profileManager = mockk<ProfileManager>(relaxed = true)
    )

    private fun progress(
        contentId: String = "tt1",
        contentType: String = "series",
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long = 0L,
        source: String = WatchProgress.SOURCE_LOCAL,
        traktShowId: Int? = null
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
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched,
        progressPercent = 100f,
        source = source,
        traktShowId = traktShowId
    )

    // --- isOptimisticNextUpSeedCandidate ---

    @Test
    fun `isOptimisticNextUpSeedCandidate requires series type, completed, trakt-playback source, and a valid season-episode`() {
        val repository = newRepository()
        val now = 1_000_000L

        val valid = progress(
            contentType = "series", season = 1, episode = 2,
            lastWatched = now - 1000L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        )
        assertTrue(repository.isOptimisticNextUpSeedCandidate(valid, now))

        assertFalse(repository.isOptimisticNextUpSeedCandidate(valid.copy(contentType = "movie"), now))
        assertFalse(repository.isOptimisticNextUpSeedCandidate(valid.copy(source = WatchProgress.SOURCE_LOCAL), now))
        assertFalse(repository.isOptimisticNextUpSeedCandidate(valid.copy(season = 0), now))
        assertFalse(repository.isOptimisticNextUpSeedCandidate(valid.copy(season = null), now))
    }

    @Test
    fun `isOptimisticNextUpSeedCandidate rejects entries outside the optimistic time window`() {
        val repository = newRepository()
        val now = 1_000_000_000L
        val tooOld = progress(
            contentType = "series", season = 1, episode = 2,
            lastWatched = now - (4 * 60_000L), source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        )
        assertFalse(repository.isOptimisticNextUpSeedCandidate(tooOld, now))
    }

    // --- isMalformedNextUpSeedContentId ---

    @Test
    fun `isMalformedNextUpSeedContentId flags blank and bare provider prefixes`() {
        val repository = newRepository()
        assertTrue(repository.isMalformedNextUpSeedContentId(null))
        assertTrue(repository.isMalformedNextUpSeedContentId(""))
        assertTrue(repository.isMalformedNextUpSeedContentId("tmdb:"))
        assertFalse(repository.isMalformedNextUpSeedContentId("tt1234567"))
    }

    // --- nextUpSeedKey ---

    @Test
    fun `nextUpSeedKey prefers traktShowId over contentId`() {
        val repository = newRepository()
        val withTraktId = progress(contentId = "tt1", traktShowId = 42)
        val withoutTraktId = progress(contentId = " tt2 ")

        assertEquals("trakt_show:42", repository.nextUpSeedKey(withTraktId))
        assertEquals("tt2", repository.nextUpSeedKey(withoutTraktId))
    }

    // --- shouldReplaceNextUpSeed ---

    @Test
    fun `shouldReplaceNextUpSeed in non-furthest mode picks the most recently watched`() {
        val repository = newRepository()
        val existing = progress(lastWatched = 100L)
        val newerCandidate = progress(lastWatched = 200L)
        val olderCandidate = progress(lastWatched = 50L)

        assertTrue(repository.shouldReplaceNextUpSeed(existing, newerCandidate, useFurthest = false))
        assertFalse(repository.shouldReplaceNextUpSeed(existing, olderCandidate, useFurthest = false))
    }

    @Test
    fun `shouldReplaceNextUpSeed in furthest mode picks the higher season-episode regardless of recency`() {
        val repository = newRepository()
        val existing = progress(season = 1, episode = 5, lastWatched = 900L)
        val furtherButOlder = progress(season = 2, episode = 1, lastWatched = 100L)
        val sameSpotButNewer = progress(season = 1, episode = 5, lastWatched = 950L)
        val behind = progress(season = 1, episode = 3, lastWatched = 999L)

        assertTrue(repository.shouldReplaceNextUpSeed(existing, furtherButOlder, useFurthest = true))
        assertTrue(repository.shouldReplaceNextUpSeed(existing, sameSpotButNewer, useFurthest = true))
        assertFalse(repository.shouldReplaceNextUpSeed(existing, behind, useFurthest = true))
    }

    // --- mergeNextUpSeeds ---

    @Test
    fun `mergeNextUpSeeds keeps canonical seed when no optimistic seed replaces it`() {
        val repository = newRepository()
        val canonical = progress(contentId = "tt1", season = 2, episode = 1, lastWatched = 100L)
        val staleOptimistic = progress(contentId = "tt1", season = 1, episode = 3, lastWatched = 200L)

        val merged = repository.mergeNextUpSeeds(
            canonicalSeeds = listOf(canonical),
            optimisticSeeds = listOf(staleOptimistic),
            useFurthest = true
        )

        assertEquals(1, merged.size)
        assertEquals(canonical, merged[0])
    }

    @Test
    fun `mergeNextUpSeeds replaces canonical seed with a further optimistic seed for the same key`() {
        val repository = newRepository()
        val canonical = progress(contentId = "tt1", season = 1, episode = 3, lastWatched = 100L)
        val fresherOptimistic = progress(contentId = "tt1", season = 1, episode = 4, lastWatched = 200L)

        val merged = repository.mergeNextUpSeeds(
            canonicalSeeds = listOf(canonical),
            optimisticSeeds = listOf(fresherOptimistic),
            useFurthest = true
        )

        assertEquals(1, merged.size)
        assertEquals(fresherOptimistic, merged[0])
    }

    @Test
    fun `mergeNextUpSeeds sorts distinct entries by lastWatched descending`() {
        val repository = newRepository()
        val a = progress(contentId = "tt1", lastWatched = 100L)
        val b = progress(contentId = "tt2", lastWatched = 300L)
        val c = progress(contentId = "tt3", lastWatched = 200L)

        val merged = repository.mergeNextUpSeeds(canonicalSeeds = listOf(a, b, c), optimisticSeeds = emptyList(), useFurthest = true)

        assertEquals(listOf(b, c, a), merged)
    }

    // --- progressKey ---

    @Test
    fun `progressKey includes season and episode for episodic progress`() {
        val repository = newRepository()
        val episodic = progress(contentId = "tt1", season = 2, episode = 5)
        val movie = progress(contentId = "tt2", season = null, episode = null)

        assertEquals("tt1_s2e5", repository.progressKey(episodic))
        assertEquals("tt2", repository.progressKey(movie))
    }
}
