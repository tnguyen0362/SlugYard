package com.sluggyard.tv.ui.rewrite.debrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RewriteCredentialCipherAndroidTest {

    @Test
    fun encryptDecryptRoundTripReturnsOriginalPlaintext() {
        val plain = "K2ABC123XYZtorboxapikey987"
        val blob = RewriteCredentialCipher.encrypt(plain)
        val recovered = RewriteCredentialCipher.decrypt(blob)
        assertEquals("Round-trip must recover the original plaintext", plain, recovered)
    }

    @Test
    fun encryptedBlobCarriesEncV1Prefix() {
        val blob = RewriteCredentialCipher.encrypt("secret")
        assertTrue("Blob must start with enc-v1: prefix", blob.startsWith("enc-v1:"))
    }

    @Test
    fun keyPersistsInKeystoreAcrossCalls() {
        val first = RewriteCredentialCipher.encrypt("persist-test-1")
        val second = RewriteCredentialCipher.encrypt("persist-test-2")
        assertEquals("persist-test-1", RewriteCredentialCipher.decrypt(first))
        assertEquals("persist-test-2", RewriteCredentialCipher.decrypt(second))
    }

    @Test
    fun decryptReturnsNullForNonPrefixedBlob() {
        assertNull(RewriteCredentialCipher.decrypt("rawdata"))
        assertNull(RewriteCredentialCipher.decrypt(""))
    }

    @Test
    fun decryptReturnsNullForCorruptedPayload() {
        val blob = RewriteCredentialCipher.encrypt("tamper-me")
        val corrupted = "enc-v1:" + blob.removePrefix("enc-v1:").dropLast(4) + "AAAA"
        assertNull(RewriteCredentialCipher.decrypt(corrupted))
    }

    @Test
    fun decryptReturnsNullForTruncatedPayload() {
        assertNull(RewriteCredentialCipher.decrypt("enc-v1:AAAA"))
    }

    @Test
    fun distinctEncryptionsProduceDistinctCiphertexts() {
        val a = RewriteCredentialCipher.encrypt("same-plaintext")
        val b = RewriteCredentialCipher.encrypt("same-plaintext")
        assertNotEquals("Random IV must yield different ciphertexts", a, b)
        assertEquals("same-plaintext", RewriteCredentialCipher.decrypt(a))
        assertEquals("same-plaintext", RewriteCredentialCipher.decrypt(b))
    }

    @Test
    fun realisticLongApiKeyRoundTrips() {
        val key = "tdl_3Kz9QwXvPmNbRgYhVkDfGsJcLpUiHoMyTrEwZxAqSbClDmEnFpGrHsItJuKvLwXyZ0123456789abcdef"
        val blob = RewriteCredentialCipher.encrypt(key)
        assertEquals(key, RewriteCredentialCipher.decrypt(blob))
    }
}
