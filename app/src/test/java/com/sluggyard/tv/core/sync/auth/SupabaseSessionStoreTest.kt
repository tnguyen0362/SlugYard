package com.sluggyard.tv.core.sync.auth

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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SupabaseSessionStoreTest {
    @Test
    fun encryptedSessionRoundTripsAndEmptyStoreIsSignedOut() = runBlocking {
        val dataStore = createDataStore()
        val cipher = FakeSessionCipher()
        val store = DataStoreSupabaseSessionStore(dataStore, cipher)

        assertEquals(SupabaseSessionState.SignedOut, store.read())

        store.write(session)

        assertEquals(SupabaseSessionState.Active(session), store.read())
        val raw = dataStore.data.first()[SUPABASE_SESSION_KEY]
            ?: error("Session blob was not persisted")
        assertTrue(raw.startsWith("blob-"))
        assertTrue(!raw.contains("access-token"))
    }

    @Test
    fun malformedEncryptedPayloadIsCorrupt() = runBlocking {
        val dataStore = createDataStore()
        val cipher = FakeSessionCipher()
        val store = DataStoreSupabaseSessionStore(dataStore, cipher)
        cipher.add("blob-malformed", "not-json")
        dataStore.edit { it[SUPABASE_SESSION_KEY] = "blob-malformed" }

        assertEquals(SupabaseSessionState.Corrupt, store.read())
    }

    @Test
    fun clearRemovesTheSession() = runBlocking {
        val store = DataStoreSupabaseSessionStore(createDataStore(), FakeSessionCipher())
        store.write(session)

        store.clear()

        assertEquals(SupabaseSessionState.SignedOut, store.read())
    }

    @Test
    fun concurrentWritesAndReadsRemainSerialized() = runBlocking {
        val store = DataStoreSupabaseSessionStore(createDataStore(), FakeSessionCipher())
        val sessions = (1..20).map { session.copy(userId = "user-$it") }

        coroutineScope {
            sessions.map { value ->
                async {
                    store.write(value)
                    store.read()
                }
            }.awaitAll()
        }

        assertIs<SupabaseSessionState.Active>(store.read())
        Unit
    }

    private fun createDataStore(): DataStore<Preferences> {
        val directory = Files.createTempDirectory("slugyard-session-test")
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { directory.resolve("session.preferences_pb").toFile() },
        )
    }

    private class FakeSessionCipher : SupabaseSessionCipher {
        private val values = mutableMapOf<String, String>()
        private var nextId = 0

        override fun encrypt(payload: String): String {
            val id = "blob-${nextId++}"
            values[id] = payload
            return id
        }

        override fun decrypt(blob: String): String? = values[blob]

        fun add(blob: String, payload: String) {
            values[blob] = payload
        }
    }

    private companion object {
        val session = SupabaseSession(
            userId = "user-1",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAtEpochMs = 2_000L,
        )
    }
}
