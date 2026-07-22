package com.sluggyard.tv.core.sync.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SupabaseSessionStore {
    suspend fun read(): SupabaseSessionState

    suspend fun write(session: SupabaseSession)

    suspend fun clear()
}

interface SupabaseSessionCipher {
    fun encrypt(payload: String): String

    fun decrypt(blob: String): String?
}

internal val SUPABASE_SESSION_KEY = stringPreferencesKey("slugyard_supabase_session_v1")

class DataStoreSupabaseSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SupabaseSessionCipher = AndroidSupabaseSessionCipher,
) : SupabaseSessionStore {
    private val mutex = Mutex()

    override suspend fun read(): SupabaseSessionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val blob = dataStore.data.first()[SUPABASE_SESSION_KEY]
                ?: return@withLock SupabaseSessionState.SignedOut
            cipher.decrypt(blob)
                ?.let(SupabaseSessionCodec::decode)
                ?.let(SupabaseSessionState::Active)
                ?: SupabaseSessionState.Corrupt
        }
    }

    override suspend fun write(session: SupabaseSession) {
        require(session.isUsable()) { "Invalid Supabase session" }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val blob = cipher.encrypt(SupabaseSessionCodec.encode(session))
                dataStore.edit { preferences -> preferences[SUPABASE_SESSION_KEY] = blob }
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                dataStore.edit { preferences -> preferences.remove(SUPABASE_SESSION_KEY) }
            }
        }
    }
}

private object AndroidSupabaseSessionCipher : SupabaseSessionCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "slugyard.supabase.session.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc-v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val keyLock = Any()

    override fun encrypt(payload: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val encrypted = cipher.doFinal(payload.encodeToByteArray())
        return PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String? {
        if (!blob.startsWith(PREFIX)) return null
        return runCatching {
            val payload = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
            require(payload.size > IV_BYTES) { "Malformed session blob" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)),
                )
            }
            cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).decodeToString()
        }.getOrNull()
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
