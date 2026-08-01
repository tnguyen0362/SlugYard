package com.sluggyard.tv.ui.app.debrid

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android-Keystore boundary for rewrite-owned provider credentials.
 *
 * DataStore persists only a versioned encrypted blob. The secret key is non-exportable and stays
 * in Android Keystore, so neither navigation state nor preference files contain usable API keys.
 */
internal object CredentialCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "slugyard.rewrite.debrid.credentials.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val BLOB_PREFIX = "enc-v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val keyLock = Any()

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val encrypted = cipher.doFinal(plainText.encodeToByteArray())
        return BLOB_PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String? {
        if (!blob.startsWith(BLOB_PREFIX)) return null
        return runCatching {
            val payload = Base64.decode(blob.removePrefix(BLOB_PREFIX), Base64.NO_WRAP)
            require(payload.size > IV_BYTES) { "Malformed encrypted credential" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
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
