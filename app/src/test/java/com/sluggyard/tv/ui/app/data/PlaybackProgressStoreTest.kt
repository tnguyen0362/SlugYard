package com.sluggyard.tv.ui.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class PlaybackProgressStoreTest {
    @Test
    fun `episodes retain independent resume checkpoints`() = runBlocking {
        val store = PlaybackProgressStore(createDataStore(), FakeProfiles())
        val episodeOne = checkpoint(season = 1, episode = 1, updatedAt = 1L)
        val episodeTwo = checkpoint(season = 1, episode = 2, updatedAt = 2L)

        store.save(episodeOne)
        store.save(episodeTwo)

        assertEquals(listOf(episodeTwo, episodeOne), store.checkpoints.first())
    }

    @Test
    fun `completing one episode does not remove another episode`() = runBlocking {
        val store = PlaybackProgressStore(createDataStore(), FakeProfiles())
        val episodeOne = checkpoint(season = 1, episode = 1, updatedAt = 1L)
        val episodeTwo = checkpoint(season = 1, episode = 2, updatedAt = 2L)

        store.save(episodeOne)
        store.save(episodeTwo)
        store.save(episodeOne.copy(positionMs = 950L, durationMs = 1_000L, updatedAtEpochMs = 3L))

        assertEquals(listOf(episodeTwo), store.checkpoints.first())
    }

    @Test
    fun `sub two percent progress is not persisted`() = runBlocking {
        val store = PlaybackProgressStore(createDataStore(), FakeProfiles())
        store.save(checkpoint(season = 1, episode = 1, updatedAt = 1L).copy(positionMs = 10L))

        assertEquals(emptyList<PlaybackCheckpoint>(), store.checkpoints.first())
    }

    private fun checkpoint(season: Int, episode: Int, updatedAt: Long) = PlaybackCheckpoint(
        contentId = "show-1",
        contentType = "series",
        title = "Show",
        positionMs = 100L,
        durationMs = 1_000L,
        updatedAtEpochMs = updatedAt,
        season = season,
        episode = episode,
    )

    private fun createDataStore(): DataStore<Preferences> {
        val directory = Files.createTempDirectory("rewrite-progress-test")
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { directory.resolve("progress.preferences_pb").toFile() },
        )
    }

    private class FakeProfiles : ProfileRepository {
        override val state: Flow<ProfileState> = flowOf(
            ProfileState(
                profiles = listOf(Profile("1", "Viewer")),
                activeProfileId = "1",
            ),
        )

        override suspend fun select(profileId: String) = Unit
        override suspend fun create(name: String) = Profile("1", name)
        override suspend fun rename(profileId: String, name: String) = Unit
        override suspend fun remove(profileId: String) = Unit
        override suspend fun setRememberLastProfile(enabled: Boolean) = Unit
        override suspend fun migrateIfDefault(
            profiles: List<PriorProfileSnapshot>,
            activeProfileId: String,
            rememberLastProfile: Boolean,
        ) = Unit
    }
}
