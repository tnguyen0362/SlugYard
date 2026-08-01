package androidx.media3.datasource

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Cipher

class AesCipherDataSourceTest {

    private val secretKey = ByteArray(16) { i -> (i + 1).toByte() }
    private val plaintext = "Hello World! This is a highly confidential zero-copy AES path test payload.".toByteArray()
    private val nonce = "test-nonce-key"

    @Test
    fun testAesDecryptionHeapBuffer() {
        val encryptedData = encryptPlaintext()

        // Wrap encrypted data in ByteArrayDataSource
        val byteArrayDataSource = ByteArrayDataSource(encryptedData)
        val aesDataSource = AesCipherDataSource(secretKey, byteArrayDataSource)

        val mockUri = mockk<Uri>(relaxed = true)
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .setKey(nonce)
            .build()

        val openedLength = aesDataSource.open(dataSpec)
        assertEquals(encryptedData.size.toLong(), openedLength)

        val decryptedBytes = ByteArray(plaintext.size)
        var totalRead = 0
        while (totalRead < decryptedBytes.size) {
            val read = aesDataSource.read(decryptedBytes, totalRead, decryptedBytes.size - totalRead)
            if (read == -1) break
            totalRead += read
        }

        assertEquals(plaintext.size, totalRead)
        assertArrayEquals(plaintext, decryptedBytes)

        aesDataSource.close()
    }

    private fun encryptPlaintext(): ByteArray {
        val encryptCipher = AesFlushingCipher(
            Cipher.ENCRYPT_MODE,
            secretKey,
            nonce,
            0L
        )
        val encryptedData = plaintext.clone()
        encryptCipher.updateInPlace(encryptedData, 0, encryptedData.size)
        return encryptedData
    }
}
