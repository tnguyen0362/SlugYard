package com.sluggyard.tv.ui.app.debrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialCipherAndroidTest {

    @Test
    fun encryptDecryptRoundTripReturnsOriginalPlaintext() {
        val plain = "K2ABC123XYZtorboxapikey987"
        val blob = CredentialCipher.encrypt(plain)
        val recovered = CredentialCipher.decrypt(blob)
        assertEquals("Round-trip must recover the original plaintext", plain, recovered)
    }

    @Test
    fun encryptedBlobCarriesEncV1Prefix() {
        val blob = CredentialCipher.encrypt("secret")
        assertTrue("Blob must start with enc-v1: prefix", blob.startsWith("enc-v1:"))
    }

    @Test
    fun keyPersistsInKeystoreAcrossCalls() {
        val first = CredentialCipher.encrypt("persist-test-1")
        val second = CredentialCipher.encrypt("persist-test-2")
        assertEquals("persist-test-1", CredentialCipher.decrypt(first))
        assertEquals("persist-test-2", CredentialCipher.decrypt(second))
    }

    @Test
    fun decryptReturnsNullForNonPrefixedBlob() {
        assertNull(CredentialCipher.decrypt("rawdata"))
        assertNull(CredentialCipher.decrypt(""))
    }

    @Test
    fun decryptReturnsNullForCorruptedPayload() {
        val blob = CredentialCipher.encrypt("tamper-me")
        val corrupted = "enc-v1:" + blob.removePrefix("enc-v1:").dropLast(4) + "AAAA"
        assertNull(CredentialCipher.decrypt(corrupted))
    }

    @Test
    fun decryptReturnsNullForTruncatedPayload() {
        assertNull(CredentialCipher.decrypt("enc-v1:AAAA"))
    }

    @Test
    fun distinctEncryptionsProduceDistinctCiphertexts() {
        val a = CredentialCipher.encrypt("same-plaintext")
        val b = CredentialCipher.encrypt("same-plaintext")
        assertNotEquals("Random IV must yield different ciphertexts", a, b)
        assertEquals("same-plaintext", CredentialCipher.decrypt(a))
        assertEquals("same-plaintext", CredentialCipher.decrypt(b))
    }

    @Test
    fun realisticLongApiKeyRoundTrips() {
        val key = "tdl_3Kz9QwXvPmNbRgYhVkDfGsJcLpUiHoMyTrEwZxAqSbClDmEnFpGrHsItJuKvLwXyZ0123456789abcdef"
        val blob = CredentialCipher.encrypt(key)
        assertEquals(key, CredentialCipher.decrypt(blob))
    }
}
