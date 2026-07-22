package com.sluggyard.tv.data.local

import androidx.media3.common.util.UnstableApi

/**
 * Shared memory-budget constants and helpers for buffer + parallel-connection
 * configuration. Values are derived from the device's max heap at startup.
 */
@UnstableApi
object MemoryBudget {
    const val TAG = "MemoryBudget"

    private const val LOW_HEAP_RATIO = 0.65
    private const val HIGH_HEAP_RATIO = 0.85
    private const val HIGH_HEAP_THRESHOLD_MB = 512L
    private const val LOW_HEAP_RESERVE_MB = 210L

    /** ParallelRangeDataSource schedules maxAhead = parallelConnections + 1 chunks concurrently. */
    private const val BUFFER_OVERHEAD = 1

    const val MIN_CONNECTIONS = 2
    const val MAX_CONNECTIONS = 4
    const val MIN_CHUNK_MB = 8
    const val MAX_CHUNK_MB = 128
    const val BUFFER_STEP_MB = 25
    const val MIN_BUFFER_MB = 25
    const val MAX_BUFFER_MB = 1024 * 4
    private const val FALLBACK_EFFECTIVE_BUFFER_MB = 50

    val defaultBufferSizeMb: Int = if (BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB > 0) {
        BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB
    } else {
        FALLBACK_EFFECTIVE_BUFFER_MB
    }

    private val maxHeapMb: Long = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

    val isLowRamTier: Boolean = maxHeapMb < HIGH_HEAP_THRESHOLD_MB

    private val rawBudgetMb: Int =
        (maxHeapMb * (if (isLowRamTier) LOW_HEAP_RATIO else HIGH_HEAP_RATIO)).toInt()

    val budgetMb: Int =
        if (isLowRamTier) {
            rawBudgetMb.coerceAtMost((maxHeapMb - LOW_HEAP_RESERVE_MB).toInt()).coerceAtLeast(MIN_BUFFER_MB)
        } else {
            rawBudgetMb
        }

    val conversionBudgetMb: Int =
        (if (isLowRamTier) rawBudgetMb / 3 else rawBudgetMb / 2)
            .coerceAtMost(budgetMb).coerceAtLeast(MIN_BUFFER_MB)

    fun effectiveBufferMb(stored: Int): Int =
        if (stored > 0) stored else defaultBufferSizeMb

    fun bufferCount(connectionCount: Int): Int = connectionCount + BUFFER_OVERHEAD

    fun parallelOverheadMb(connectionCount: Int, chunkSizeMb: Int): Int =
        bufferCount(connectionCount) * chunkSizeMb

    fun totalUsageMb(bufferMb: Int, connectionCount: Int, chunkSizeMb: Int, parallelEnabled: Boolean): Int =
        bufferMb + if (parallelEnabled) parallelOverheadMb(connectionCount, chunkSizeMb) else 0

    fun maxChunkMb(bufferMb: Int, connectionCount: Int): Int =
        ((budgetMb - bufferMb) / bufferCount(connectionCount)).coerceIn(MIN_CHUNK_MB, MAX_CHUNK_MB)

    fun maxBufferMb(parallelOverheadMb: Int): Int =
        ((budgetMb - parallelOverheadMb) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)

    fun maxBufferMbWithOverride(parallelOverheadMb: Int, allowLargeTargetBuffer: Boolean): Int {
        val safeMax = maxBufferMb(parallelOverheadMb)
        return if (allowLargeTargetBuffer) {
            PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB
                .coerceAtMost(MAX_BUFFER_MB)
                .coerceAtLeast(safeMax)
        } else {
            safeMax
        }
    }

    fun enforce(bufferMb: Int, chunkMb: Int, connectionCount: Int): Pair<Int, Int> {
        val buffers = bufferCount(connectionCount)
        if (bufferMb + buffers * chunkMb <= budgetMb) return bufferMb to chunkMb

        val reducedChunkMb = maxChunkMb(bufferMb, connectionCount)
        if (bufferMb + buffers * reducedChunkMb <= budgetMb) return bufferMb to reducedChunkMb

        val reducedBufferMb = ((budgetMb - buffers * MIN_CHUNK_MB) / BUFFER_STEP_MB * BUFFER_STEP_MB)
            .coerceAtLeast(MIN_BUFFER_MB)
        return reducedBufferMb to MIN_CHUNK_MB
    }

    fun getUsageStatus(
        totalUsageMb: Int,
        safeLimitMb: Int,
        warningLimitMb: Int
    ): MemoryUsageStatus = when {
        totalUsageMb > warningLimitMb -> MemoryUsageStatus.DANGER
        totalUsageMb > safeLimitMb -> MemoryUsageStatus.WARNING
        else -> MemoryUsageStatus.SAFE
    }
}

@UnstableApi
enum class MemoryUsageStatus {
    SAFE,
    WARNING,
    DANGER
}