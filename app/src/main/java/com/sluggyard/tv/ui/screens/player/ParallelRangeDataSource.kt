package com.sluggyard.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.sluggyard.tv.data.local.PlayerSettings
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [DataSource] that downloads progressive files using multiple parallel HTTP
 * range requests.
 *
 * Each individual TCP connection may be limited to ~100 Mbps (CDN per-connection
 * limits or Java/Okio overhead). By downloading different byte ranges in parallel
 * across multiple connections, effective throughput multiplies (e.g. 3
 * connections ≈ 300 Mbps).
 *
 * Uses a buffer pool to reuse ByteArrays or native ByteBuffers and avoid GC
 * churn from large allocations. Only used for progressive downloads (MKV, MP4);
 * HLS/DASH already handle chunked parallel downloads.
 *
 * Subtitle sidecar requests (URLs carrying the `subtitle_sidecar` query param)
 * bypass parallel mode and use a single connection.
 */
@UnstableApi
internal class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
    private val useNativeMemory: Boolean = false,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {}
) : DataSource, ByteBufferDataReader {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 64 * 1024
        private const val BOOTSTRAP_READ_BYTES = 1L * 1024L * 1024L
        private const val SUBTITLE_SIDEKICK_QUERY = "subtitle_sidecar"

        private val readBufferLocal = object : ThreadLocal<ByteArray>() {
            override fun initialValue(): ByteArray = ByteArray(READ_BUFFER_SIZE)
        }

        // A single, shared, lazy cached thread pool with bounded max threads to
        // prevent OOM/pthread_create failure.
        private val sharedExecutor: ExecutorService by lazy {
            val threadFactory = ThreadFactory { runnable ->
                Thread(runnable, "parallel-ds-worker").apply {
                    priority = Thread.NORM_PRIORITY
                    isDaemon = true
                }
            }
            ThreadPoolExecutor(
                32, 64, 60L, TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>(),
                threadFactory,
                ThreadPoolExecutor.DiscardPolicy()
            ).apply { allowCoreThreadTimeOut(true) }
        }

        private val activeInstances = AtomicInteger(0)
        private val globalBufferPool = ConcurrentHashMap<Long, ConcurrentLinkedDeque<PooledBuffer>>()

        private fun freeDirectBuffer(buffer: ByteBuffer) {
            if (!buffer.isDirect) return
            try {
                val cleanerMethod = buffer.javaClass.getMethod("cleaner")
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer)
                if (cleaner != null) {
                    val cleanMethod = cleaner.javaClass.getMethod("clean")
                    cleanMethod.isAccessible = true
                    cleanMethod.invoke(cleaner)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to explicitly free direct buffer: ${e.message}")
            }
        }

        private fun clearGlobalPool() {
            globalBufferPool.values.forEach { pool ->
                while (true) {
                    val buf = pool.pollFirst() ?: break
                    if (buf.allocation != null) {
                        androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buf.allocation)
                    } else if (buf.byteBuffer.isDirect) {
                        freeDirectBuffer(buf.byteBuffer)
                    }
                }
            }
            globalBufferPool.clear()
            Log.d(TAG, "Cleared global buffer pool as all ParallelRangeDataSource instances are closed")
        }
    }

    init { activeInstances.incrementAndGet() }

    internal data class BootstrapCacheEntry(
        val requestUri: Uri,
        val startPosition: Long,
        val resolvedUri: Uri?,
        val openLength: Long,
        val totalFileLength: Long,
        val bootstrapData: ByteArray,
        val bootstrapSize: Int,
        val createdAtUptimeMs: Long
    )

    private class PooledBuffer(
        val allocation: androidx.media3.exoplayer.upstream.Allocation?,
        val byteBuffer: ByteBuffer
    )

    private class DownloadedChunk(val buffer: PooledBuffer, val size: Int)

    private var resolvedUri: Uri? = null
    private var originalDataSpec: DataSpec? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(false)

    private val chunks = ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>()
    private val maxPoolSize = parallelConnections + 2

    private var currentChunk: DownloadedChunk? = null
    private var currentChunkIndex: Long = -1
    private var currentChunkReadOffset: Int = 0
    private var bootstrapPrefetchDeferred: Boolean = false
    private var bootstrapChunk: DownloadedChunk? = null
    private var bootstrapStartPosition: Long = C.TIME_UNSET
    private var continuationSource: OkHttpDataSource? = null
    private val activeDataSources = ConcurrentHashMap.newKeySet<DataSource>()
    private var continuationEndPositionExclusive: Long = C.TIME_UNSET

    private val transferListeners = mutableListOf<TransferListener>()

    // Fallback: if parallel mode fails, use a single upstream DataSource.
    private var fallbackSource: OkHttpDataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.getQueryParameter(SUBTITLE_SIDEKICK_QUERY) == "subtitle") {
            return openSubtitleSidecar(dataSpec)
        }

        val wasClosed = closed.get()
        val isReopen = !wasClosed &&
            originalDataSpec != null &&
            originalDataSpec?.uri == dataSpec.uri &&
            position == dataSpec.position &&
            totalFileLength != C.LENGTH_UNSET.toLong()

        closed.set(false)

        if (isReopen) {
            position = dataSpec.position
            bytesRemaining = (totalFileLength - position).coerceAtLeast(0L)
            bootstrapPrefetchDeferred = true
            Log.d(TAG, "Reusing active ParallelRangeDataSource for reopen at $position, file=${totalFileLength / 1024 / 1024}MB")
            return bytesRemaining
        }

        originalDataSpec = dataSpec
        position = dataSpec.position
        bootstrapPrefetchDeferred = false
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET
        cancelAllChunks()

        consumeBootstrapCache(dataSpec)?.let { cached ->
            resolvedUri = cached.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = cached.totalFileLength
            bytesRemaining = cached.openLength
            bootstrapChunk = DownloadedChunk(PooledBuffer(null, ByteBuffer.wrap(cached.bootstrapData)), cached.bootstrapSize)
            bootstrapStartPosition = cached.startPosition
            bootstrapPrefetchDeferred = true
            Log.d(
                TAG,
                "Reusing bootstrap window for immediate reopen at ${cached.startPosition}, " +
                    "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}"
            )
            return cached.openLength
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL.
        val probeSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }

        val openLength: Long
        try {
            openLength = probeSource.open(dataSpec)
            resolvedUri = probeSource.uri
            onResolvedUri(resolvedUri)
        } catch (e: Exception) {
            probeSource.close()
            throw e
        }

        val responseHeaders = probeSource.responseHeaders
        val acceptsRanges = responseHeaders.entries
            .firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }?.value
            ?.any { it.contains("bytes") } == true ||
            responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value
                ?.isNotEmpty() == true

        if (openLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {
            // Can't determine length or server doesn't support ranges — reuse probe as single connection.
            Log.w(TAG, "Falling back to single connection (length=$openLength, acceptsRanges=$acceptsRanges)")
            fallbackSource = probeSource
            return openLength
        }

        totalFileLength = position + openLength
        bytesRemaining = openLength

        Log.d(
            TAG,
            "Parallel mode: $parallelConnections connections, ${chunkSize / 1024 / 1024}MB chunks, " +
                "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}"
        )

        // Reuse a small probe window immediately for both startup and large seek reopens.
        if (openLength > 0L) {
            val bootstrapBytes = minOf(minOf(chunkSize, BOOTSTRAP_READ_BYTES), openLength).toInt()
            val chunk = readBootstrapChunk(probeSource, bootstrapBytes)
            bootstrapChunk = chunk
            bootstrapStartPosition = position
            // Avoid startup churn from immediate background fetches during repeated startup
            // opens, but do not redownload the active seek chunk from its start.
            bootstrapPrefetchDeferred = true
            if (position == 0L) {
                updateBootstrapCache(
                    BootstrapCacheEntry(
                        requestUri = dataSpec.uri,
                        startPosition = dataSpec.position,
                        resolvedUri = resolvedUri,
                        openLength = openLength,
                        totalFileLength = totalFileLength,
                        bootstrapData = chunk.buffer.byteBuffer.array(),
                        bootstrapSize = chunk.size,
                        createdAtUptimeMs = SystemClock.uptimeMillis()
                    )
                )
            }
            probeSource.close()
        } else {
            probeSource.close()
        }

        return openLength
    }

    private fun openSubtitleSidecar(dataSpec: DataSpec): Long {
        closed.set(false)
        cancelAllChunks()
        // Clean the custom query parameter from the subtitle URL before requesting.
        val cleanedUri = dataSpec.uri.buildUpon().clearQuery().let { builder ->
            dataSpec.uri.queryParameterNames.forEach { name ->
                if (name != SUBTITLE_SIDEKICK_QUERY) {
                    dataSpec.uri.getQueryParameters(name).forEach { value ->
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
            builder.build()
        }
        val cleanedDataSpec = dataSpec.withUri(cleanedUri)

        val probeSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }
        fallbackSource = probeSource
        val openLength = probeSource.open(cleanedDataSpec)

        totalFileLength = openLength
        bytesRemaining = openLength
        position = dataSpec.position

        Log.d(TAG, "Subtitle request detected. Bypassing parallel mode for single-connection download: ${cleanedUri.host}")
        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallbackSource?.let { source ->
            val read = source.read(buffer, offset, length)
            if (read > 0) {
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize
        val bootstrap = bootstrapChunk
        if (currentChunk == null && bootstrap != null &&
            position >= bootstrapStartPosition && position < bootstrapStartPosition + bootstrap.size
        ) {
            currentChunk = bootstrap
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position - bootstrapStartPosition).toInt()
        }

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive && bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val read = source.read(buffer, offset, toRead)
                if (read > 0) {
                    position += read
                    bytesRemaining -= read
                    if (position >= continuationEndPositionExclusive) {
                        source.close(); continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET; scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close(); continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET; scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close(); continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            ensureChunkScheduled(chunkIndex)
            val future = chunks[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            try {
                currentChunk = future.get(60, TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()
            cleanupOldChunks(chunkIndex)
            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            if (chunk === bootstrapChunk) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            return read(buffer, offset, length)
        }

        val readSize = minOf(toRead, available)
        val readBuf = chunk.buffer.byteBuffer
        readBuf.position(currentChunkReadOffset)
        readBuf.get(buffer, offset, readSize)
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        return readSize
    }

    private fun scheduleChunks() {
        if (!shouldAllowBackgroundPrefetch()) return
        val currentChunkIdx =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET &&
                position < continuationEndPositionExclusive
            ) continuationEndPositionExclusive / chunkSize
            else position / chunkSize
        val maxAhead = parallelConnections + 1
        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            ensureChunkScheduled(ci)
        }
    }

    private fun ensureChunkScheduled(chunkIndex: Long) {
        chunks.computeIfAbsent(chunkIndex) {
            val future = CompletableFuture<DownloadedChunk>()
            Log.d(TAG, "Scheduling chunk $chunkIndex")
            sharedExecutor.execute {
                try {
                    if (!future.isCancelled) {
                        val result = downloadChunkWithRetry(chunkIndex, future)
                        if (!future.complete(result)) releaseBuffer(result.buffer)
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            future
        }
    }

    private fun downloadChunkWithRetry(chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        var lastException: Exception? = null
        for (attempt in 0..1) {
            if (future.isCancelled) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(chunkIndex, future)
            } catch (e: Exception) {
                if (closed.get() || future.isCancelled) throw IOException("DataSource closed or cancelled")
                lastException = e
                if (attempt == 0) {
                    if (e.isTransientInterruption()) {
                        Log.d(TAG, "Chunk $chunkIndex interrupted during prefetch (attempt 1), retrying")
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                    } else {
                        Log.w(TAG, "Chunk $chunkIndex download failed (attempt 1), retrying: ${e.message}")
                    }
                }
            }
        }
        throw IOException("Failed to download chunk $chunkIndex after 2 attempts", lastException)
    }

    private fun downloadChunkOnce(chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        val start = chunkIndex * chunkSize
        val end = if (totalFileLength != C.LENGTH_UNSET.toLong()) minOf(start + chunkSize, totalFileLength)
        else start + chunkSize

        val ds = upstreamFactory.createDataSource()
        transferListeners.forEach { ds.addTransferListener(it) }
        activeDataSources.add(ds)
        try {
            val uri = resolvedUri ?: originalDataSpec?.uri ?: throw IOException("No URI available")
            val spec = DataSpec.Builder().setUri(uri).setPosition(start).setLength(end - start).build()

            if (future.isCancelled) throw IOException("Cancelled")
            Log.d(TAG, "Starting chunk download: idx=$chunkIndex, range=$start-$end")
            ds.open(spec)
            val chunk = readIntoChunk(ds, future)
            Log.d(TAG, "Successfully downloaded chunk $chunkIndex, size=${chunk.size} bytes")
            return chunk
        } finally {
            activeDataSources.remove(ds)
            try { ds.close() } catch (_: Exception) {}
        }
    }

    private fun Exception.isTransientInterruption(): Boolean {
        if (this is InterruptedIOException || this is InterruptedException) return true
        val cause = cause
        return cause is InterruptedIOException || cause is InterruptedException
    }

    /** Read from an already-opened DataSource into a pooled chunk buffer. */
    private fun readIntoChunk(ds: DataSource, future: CompletableFuture<*>): DownloadedChunk {
        val buffer = acquireBuffer()
        val tempArray = readBufferLocal.get()!!
        var totalRead = 0
        try {
            val byteBufferReader = if (useNativeMemory && ds is ByteBufferDataReader && ds.supportsByteBufferRead()) ds else null
            while (!closed.get()) {
                if (future.isCancelled) throw IOException("Chunk download cancelled")
                val maxRead = minOf(buffer.byteBuffer.capacity() - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break

                val read = if (byteBufferReader != null) {
                    buffer.byteBuffer.position(totalRead)
                    byteBufferReader.read(buffer.byteBuffer, maxRead)
                } else {
                    val r = ds.read(tempArray, 0, maxRead)
                    if (r != C.RESULT_END_OF_INPUT) {
                        buffer.byteBuffer.position(totalRead)
                        buffer.byteBuffer.put(tempArray, 0, r)
                    }
                    r
                }
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
            }
        } catch (e: Exception) {
            releaseBuffer(buffer)
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        if (closed.get()) {
            releaseBuffer(buffer)
            throw IOException("DataSource closed")
        }
        buffer.byteBuffer.flip()
        return DownloadedChunk(buffer, totalRead)
    }

    /** Read only a small startup window from an already-opened DataSource. */
    private fun readBootstrapChunk(ds: DataSource, maxBytes: Int): DownloadedChunk {
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        try {
            while (!closed.get() && totalRead < buffer.size) {
                val maxRead = minOf(buffer.size - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break
                val read = ds.read(buffer, totalRead, maxRead)
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
            }
        } catch (e: Exception) {
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        if (closed.get()) throw IOException("DataSource closed")
        val wrapped = ByteBuffer.wrap(buffer, 0, totalRead)
        return DownloadedChunk(PooledBuffer(null, wrapped), totalRead)
    }

    private fun acquireBuffer(): PooledBuffer {
        val pool = globalBufferPool.computeIfAbsent(chunkSize) { ConcurrentLinkedDeque() }
        pool.pollLast()?.let { it.byteBuffer.clear(); return it }
        return if (useNativeMemory) {
            val allocation = androidx.media3.exoplayer.upstream.DefaultAllocatorNative.createAllocation(chunkSize.toInt())
            val allocBuffer = allocation?.buffer
            if (allocation != null && allocBuffer != null) PooledBuffer(allocation, allocBuffer)
            else PooledBuffer(null, ByteBuffer.allocateDirect(chunkSize.toInt()))
        } else {
            PooledBuffer(null, ByteBuffer.allocate(chunkSize.toInt()))
        }
    }

    private fun releaseBuffer(buffer: PooledBuffer) {
        val pool = globalBufferPool.computeIfAbsent(chunkSize) { ConcurrentLinkedDeque() }
        if (pool.size < maxPoolSize) {
            pool.offerLast(buffer)
        } else {
            if (buffer.allocation != null) {
                androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buffer.allocation)
            } else if (buffer.byteBuffer.isDirect) {
                freeDirectBuffer(buffer.byteBuffer)
            }
        }
    }

    private fun cleanupOldChunks(currentChunkIndex: Long) {
        val iter = chunks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < currentChunkIndex) {
                val future = entry.value
                if (future.isDone && !future.isCancelled) {
                    try { releaseBuffer(future.get().buffer) } catch (_: Exception) {}
                }
                future.cancel(true)
                iter.remove()
            }
        }
    }

    /** Cancel and clean up all in-flight chunks, returning buffers to the pool. */
    private fun cancelAllChunks() {
        val released = java.util.Collections.newSetFromMap(ConcurrentHashMap<PooledBuffer, Boolean>())

        currentChunk?.let {
            if (it !== bootstrapChunk && released.add(it.buffer)) releaseBuffer(it.buffer)
        }
        currentChunk = null
        currentChunkIndex = -1
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET

        activeDataSources.forEach { ds -> try { ds.close() } catch (_: Exception) {} }
        activeDataSources.clear()

        chunks.values.forEach { future ->
            if (future.isDone && !future.isCancelled) {
                try {
                    val chunk = future.get()
                    if (released.add(chunk.buffer)) releaseBuffer(chunk.buffer)
                } catch (_: Exception) {}
            }
            future.cancel(true)
        }
        chunks.clear()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            fallbackSource?.close()
            fallbackSource = null
            continuationSource?.close()
            continuationSource = null
            continuationEndPositionExclusive = C.TIME_UNSET
            cancelAllChunks()
            if (activeInstances.decrementAndGet() <= 0) clearGlobalPool()
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun getUri(): Uri? = resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallbackSource?.responseHeaders ?: emptyMap()

    override fun supportsByteBufferRead(): Boolean = true

    override fun read(buffer: ByteBuffer, length: Int): Int {
        fallbackSource?.let { source ->
            val temp = ByteArray(minOf(length, READ_BUFFER_SIZE))
            val read = source.read(temp, 0, temp.size)
            if (read > 0) {
                buffer.put(temp, 0, read)
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize
        val bootstrap = bootstrapChunk
        if (currentChunk == null && bootstrap != null &&
            position >= bootstrapStartPosition && position < bootstrapStartPosition + bootstrap.size
        ) {
            currentChunk = bootstrap
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position - bootstrapStartPosition).toInt()
        }

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive && bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val temp = ByteArray(minOf(toRead, READ_BUFFER_SIZE))
                val read = source.read(temp, 0, temp.size)
                if (read > 0) {
                    buffer.put(temp, 0, read)
                    position += read
                    bytesRemaining -= read
                    if (position >= continuationEndPositionExclusive) {
                        source.close(); continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET; scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close(); continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET; scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close(); continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            ensureChunkScheduled(chunkIndex)
            val future = chunks[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            try {
                currentChunk = future.get(60, TimeUnit.SECONDS)
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()
            cleanupOldChunks(chunkIndex)
            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            if (chunk === bootstrapChunk) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            return read(buffer, length)
        }

        val readSize = minOf(toRead, available)
        val src = chunk.buffer.byteBuffer.duplicate()
        src.position(currentChunkReadOffset)
        src.limit(currentChunkReadOffset + readSize)
        buffer.put(src)
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        return readSize
    }

    /**
     * Factory for creating [ParallelRangeDataSource] instances with a shared
     * startup bootstrap cache so rapid reopens during startup reuse the first
     * connection's bootstrap window.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
        private val useNativeMemory: Boolean = false,
        private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
        private val onResolvedUri: (Uri?) -> Unit = {}
    ) : DataSource.Factory {
        @Volatile
        private var startupBootstrapCache: BootstrapCacheEntry? = null

        override fun createDataSource(): DataSource = ParallelRangeDataSource(
            upstreamFactory = upstreamFactory,
            parallelConnections = parallelConnections,
            chunkSize = chunkSize,
            useNativeMemory = useNativeMemory,
            shouldAllowBackgroundPrefetch = shouldAllowBackgroundPrefetch,
            onResolvedUri = onResolvedUri,
            consumeBootstrapCache = { dataSpec ->
                val cached = startupBootstrapCache ?: return@ParallelRangeDataSource null
                val isFresh = SystemClock.uptimeMillis() - cached.createdAtUptimeMs <= 15_000L
                if (!isFresh) {
                    startupBootstrapCache = null
                    return@ParallelRangeDataSource null
                }
                if (cached.startPosition != 0L || dataSpec.position != 0L) return@ParallelRangeDataSource null
                if (dataSpec.position != cached.startPosition) return@ParallelRangeDataSource null
                if (dataSpec.uri != cached.requestUri) return@ParallelRangeDataSource null
                cached
            },
            updateBootstrapCache = { entry -> startupBootstrapCache = entry }
        )
    }
}