package androidx.media3.exoplayer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class SampleDataQueueNativeTest {

    @Test
    fun testCopyFromArray() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val target = ByteBuffer.allocateDirect(20)

        // Success copy
        val targetAddress = SampleDataQueueNative.getDirectBufferAddressCached(target)
        val result = SampleDataQueueNative.copyFromArray(targetAddress + 5, source, 2, 5)
        assertEquals("copyFromArray should return copied length", 5, result)

        // Verify content
        val verification = ByteArray(5)
        target.position(5)
        target.get(verification)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), verification)

        assertEquals(
            "copyFromArray should reject a non-direct destination",
            -1,
            SampleDataQueueNative.copyBetweenDirectBuffers(
                ByteBuffer.allocate(20),
                0,
                target,
                0,
                1,
            ),
        )
    }

    @Test
    fun testCopyToArray() {
        val source = ByteBuffer.allocateDirect(20)
        for (i in 0 until 10) {
            source.put(i, (i + 1).toByte())
        }
        val target = ByteArray(15)

        // Success copy
        val sourceAddress = SampleDataQueueNative.getDirectBufferAddressCached(source)
        val result = SampleDataQueueNative.copyToArray(target, 4, sourceAddress + 2, 5)
        assertEquals("copyToArray should return copied length", 5, result)

        // Verify content
        val verification = ByteArray(5)
        System.arraycopy(target, 4, verification, 0, 5)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), verification)

        assertEquals(
            "copyToArray should copy from the resolved direct-buffer address",
            5,
            SampleDataQueueNative.copyToArray(target, 0, sourceAddress, 5),
        )
    }

    @Test
    fun testCopyBetweenDirectBuffers() {
        val source = ByteBuffer.allocateDirect(20)
        val target = ByteBuffer.allocateDirect(20)
        for (i in 0 until 10) {
            source.put(i, (i + 1).toByte())
        }

        // Success copy
        val result = SampleDataQueueNative.copyBetweenDirectBuffers(target, 5, source, 2, 5)
        assertEquals("copyBetweenDirectBuffers should return copied length", 5, result)

        // Verify content
        val verification = ByteArray(5)
        target.position(5)
        target.get(verification)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), verification)

        assertEquals(
            "copyBetweenDirectBuffers should reject a non-direct source",
            -1,
            SampleDataQueueNative.copyBetweenDirectBuffers(
                target,
                0,
                ByteBuffer.allocate(20),
                0,
                1,
            ),
        )
    }
}
