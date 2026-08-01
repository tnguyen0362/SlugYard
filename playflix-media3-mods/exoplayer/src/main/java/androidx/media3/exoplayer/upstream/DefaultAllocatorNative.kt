package androidx.media3.exoplayer.upstream

import java.nio.ByteBuffer

/**
 * JNI bridge for native off-heap memory allocation.
 * Fork addition for SlugYardEngineConfig performance mode.
 *
 * Uses aligned allocation (64-byte) for SIMD-friendly access patterns.
 * Reuses the libdovi_bridge native library for JNI calls.
 */
object DefaultAllocatorNative {
    init {
        System.loadLibrary("dovi_bridge")
    }

    /**
     * Allocate off-heap memory. Returns Allocation with valid nativeHandle, or null on failure.
     */
    fun createAllocation(size: Int): Allocation? {
        val nativeHandle = nativeCreateAllocation(size)
        if (nativeHandle == 0L) return null
        val buffer = ByteBuffer.allocateDirect(size)
        return Allocation(buffer, size, nativeHandle)
    }

    /**
     * Free native memory backing an allocation.
     */
    fun freeAllocation(allocation: Allocation) {
        if (allocation.nativeHandle != 0L) {
            nativeFreeAllocation(allocation.nativeHandle)
            allocation.nativeHandle = 0L
        }
    }

    @JvmStatic
    private external fun nativeCreateAllocation(size: Int): Long

    @JvmStatic
    private external fun nativeFreeAllocation(handle: Long)
}
