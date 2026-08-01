package com.sluggyard.tv.core.sync.model

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts provider secrets before they enter the sync state or network payload. */
object ProviderCredentialCiphertextCodec {
    private const val KEY_ALIAS = "slugyard_provider_credentials_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION = "v1"

    @Synchronized
    fun encrypt(providerId: String, plaintext: String): String {
        require(providerId.isNotBlank()) { "Provider identity is required" }
        require(plaintext.isNotBlank()) { "Provider credential is required" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(providerId.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            VERSION,
            encode(cipher.iv),
            encode(ciphertext),
        ).joinToString(":")
    }

    @Synchronized
    fun decrypt(providerId: String, encoded: String): String {
        require(providerId.isNotBlank()) { "Provider identity is required" }
        val parts = encoded.split(':')
        require(parts.size == 3 && parts[0] == VERSION) { "Unsupported credential ciphertext" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, decode(parts[1])),
        )
        cipher.updateAAD(providerId.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(decode(parts[2])), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
