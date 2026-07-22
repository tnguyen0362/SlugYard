package com.sluggyard.tv.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.sluggyard.tv.core.sync.model.SyncCursor
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.model.SyncMutationEnvelope
import com.sluggyard.tv.core.sync.model.SyncOperation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedSyncStateStoreTest {
    @Test
    fun emptyStateBelongsToTheRequestedOwner() = runBlocking {
        val store = store()

        assertEquals(SyncStateEnvelope(ownerUserId = "user-1"), store.read("user-1"))
    }

    @Test
    fun encryptedEnvelopeRoundTripsMutationsAndCursors() = runBlocking {
        val cipher = FakeStateCipher()
        val store = DataStoreEncryptedSyncStateStore(createDataStore(), cipher)
        val mutation = SyncMutationEnvelope.create(
            ownerUserId = "user-1",
            domain = SyncDomain.LIBRARY,
            profileId = 1,
            recordKey = "movie-1",
            operation = SyncOperation.UPSERT,
            clientChangedAtEpochMs = 123L,
            payloadJson = "{}",
        )
        val expected = SyncStateEnvelope(
            ownerUserId = "user-1",
            mutations = listOf(mutation),
            cursors = listOf(SyncCursor("user-1", SyncDomain.LIBRARY, 1, cursorValue = 4L)),
        )

        store.write("user-1", expected)

        assertTrue(
            cipher.payloads().single().contains("\"profile_id\":1"),
            cipher.payloads().single(),
        )
        assertEquals(expected, store.read("user-1"))
    }

    @Test
    fun wrongOwnerIsRejectedAndQuarantined() = runBlocking {
        val dataStore = createDataStore()
        val cipher = FakeStateCipher()
        val store = DataStoreEncryptedSyncStateStore(dataStore, cipher)
        store.write("user-1", SyncStateEnvelope("user-1"))

        assertEquals(SyncStateEnvelope("user-2"), store.read("user-2"))
        val values = dataStore.data.first()
        assertNull(values[SYNC_STATE_ACTIVE_KEY])
        assertTrue(values[SYNC_STATE_QUARANTINE_KEY]?.contains("WRONG_OWNER") == true)
    }

    @Test
    fun malformedStateIsQuarantinedInsteadOfBeingReturned() = runBlocking {
        val dataStore = createDataStore()
        val cipher = FakeStateCipher()
        cipher.add("malformed", "not-json")
        dataStore.edit { it[SYNC_STATE_ACTIVE_KEY] = "malformed" }
        val store = DataStoreEncryptedSyncStateStore(dataStore, cipher)

        assertEquals(SyncStateEnvelope("user-1"), store.read("user-1"))
        val values = dataStore.data.first()
        assertNull(values[SYNC_STATE_ACTIVE_KEY])
        assertTrue(values[SYNC_STATE_QUARANTINE_KEY]?.contains("MALFORMED") == true)
    }

    @Test
    fun unownedLegacyStateIsQuarantinedWithoutAutomaticMigration() = runBlocking {
        val dataStore = createDataStore()
        dataStore.edit { it[SYNC_STATE_LEGACY_KEY] = "legacy-encrypted-blob" }
        val store = DataStoreEncryptedSyncStateStore(dataStore, FakeStateCipher())

        assertEquals(SyncStateEnvelope("user-1"), store.read("user-1"))
        val values = dataStore.data.first()
        assertNull(values[SYNC_STATE_LEGACY_KEY])
        assertTrue(values[SYNC_STATE_QUARANTINE_KEY]?.contains("LEGACY_UNOWNED") == true)
    }

    @Test
    fun quarantineAndClearRemoveTheActiveEnvelope() = runBlocking {
        val dataStore = createDataStore()
        val store = DataStoreEncryptedSyncStateStore(dataStore, FakeStateCipher())
        store.write("user-1", SyncStateEnvelope("user-1"))

        store.quarantine("user-1", SyncCorruptionReason.MALFORMED)
        assertEquals(SyncStateEnvelope("user-1"), store.read("user-1"))

        store.write("user-1", SyncStateEnvelope("user-1"))
        store.clearActive("user-1")
        assertEquals(SyncStateEnvelope("user-1"), store.read("user-1"))
    }

    @Test
    fun concurrentAccessDoesNotMixEnvelopeWrites() = runBlocking {
        val store = store()
        val states = (1..20).map { index ->
            SyncStateEnvelope(ownerUserId = "user-1", schemaVersion = 1, payloadJson = index.toString())
        }

        coroutineScope {
            states.map { state ->
                async {
                    store.write("user-1", state)
                    store.read("user-1")
                }
            }.awaitAll()
        }

        assertTrue(store.read("user-1").payloadJson in states.map(SyncStateEnvelope::payloadJson))
    }

    private fun store(): DataStoreEncryptedSyncStateStore {
        return DataStoreEncryptedSyncStateStore(createDataStore(), FakeStateCipher())
    }

    private fun createDataStore(): DataStore<Preferences> {
        val directory = Files.createTempDirectory("slugyard-sync-state-test")
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { directory.resolve("sync.preferences_pb").toFile() },
        )
    }

    private class FakeStateCipher : SyncStateCipher {
        private val values = mutableMapOf<String, String>()
        private var nextId = 0

        override fun encrypt(payload: String): String {
            val id = "state-${nextId++}"
            values[id] = payload
            return id
        }

        override fun decrypt(blob: String): String? = values[blob]

        fun add(blob: String, payload: String) {
            values[blob] = payload
        }

        fun payloads(): Collection<String> = values.values
    }
}
