package com.sluggyard.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.SlugYardEngineConfig
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.sluggyard.tv.data.local.PlayerSettings
import java.util.concurrent.TimeUnit

/**
 * Centralizes all SlugYard ExoPlayer performance enhancements behind a single toggle.
 *
 * When [enabled] is `true`, the helper applies:
 * - Large allocator segments (256 KB) with a configurable target buffer
 * - Extended buffer durations with a back-buffer
 * - 50 Mbps initial bandwidth estimate
 * - Scrubbing mode for faster seeks (disables audio/metadata, boosts codec rate)
 * - In-buffer seek detection to suppress transient buffering UI
 * - HTTP/2 with a configurable connection pool for networking
 *
 * When [enabled] is `false`, stock ExoPlayer defaults are used everywhere.
 *
 * NOTE: [SlugYardEngineConfig] is provided by the forked Media3 AAR and is not
 * re-authored here — references to it are preserved verbatim.
 */
@androidx.media3.common.util.UnstableApi
object ExoPlayerPerformanceHelper {

    /** Whether SlugYard performance enhancements are active. Set from PlayerSettingsDataStore. */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            val newValue = value && supported
            field = newValue
            applyEngineConfig(newValue)
        }

    @Volatile
    var sharedConnectionPool: okhttp3.ConnectionPool = okhttp3.ConnectionPool(
        DEFAULT_SLUGYARD_CONNECTION_POOL_SIZE, 3, TimeUnit.MINUTES
    )
        private set

    // ─── Constants ────────────────────────────────────────────────────────────
    const val DEFAULT_SLUGYARD_ALLOCATOR_SEGMENT_SIZE = 64 * 1024
    const val DEFAULT_SLUGYARD_TARGET_BUFFER_BYTES = 250 * 1024 * 1024
    const val DEFAULT_SLUGYARD_MIN_BUFFER_MS = 40_000
    const val DEFAULT_SLUGYARD_MAX_BUFFER_MS = 120_000
    const val DEFAULT_SLUGYARD_BACK_BUFFER_MS = 1_000
    const val DEFAULT_SLUGYARD_INITIAL_BITRATE_ESTIMATE = 50_000_000L
    const val DEFAULT_SLUGYARD_CONNECTION_POOL_SIZE = 8

    // ─── Customization Variables ──────────────────────────────────────────────
    @Volatile var minBufferMs: Int = DEFAULT_SLUGYARD_MIN_BUFFER_MS
    @Volatile var maxBufferMs: Int = DEFAULT_SLUGYARD_MAX_BUFFER_MS
    @Volatile var bufferForPlaybackMs: Int = 3_000
    @Volatile var bufferForPlaybackAfterRebufferMs: Int = 3_000
    @Volatile var backBufferMs: Int = DEFAULT_SLUGYARD_BACK_BUFFER_MS
    @Volatile var targetBufferSizeMb: Int = 250
    @Volatile var connectionPoolSize: Int = DEFAULT_SLUGYARD_CONNECTION_POOL_SIZE
    @Volatile var enableHttp2: Boolean = false

    private const val SEEK_BACK_BUFFER_THRESHOLD_MS = 10_000L
    private const val SEEK_BACKWARD_TOLERANCE_MS = 2_000L
    const val SEEK_SUPPRESS_TIMEOUT_MS = 800L

    /** Updates the performance helper with customized settings from PlayerSettings. */
    fun updateSettings(settings: PlayerSettings, context: Context) {
        val customBuffers = settings.bufferEngineEnabled
        val bufferSettings = settings.bufferSettings
        enableHttp2 = settings.enableHttp2

        minBufferMs = if (customBuffers) bufferSettings.minBufferMs else DEFAULT_SLUGYARD_MIN_BUFFER_MS
        maxBufferMs = if (customBuffers) bufferSettings.maxBufferMs else DEFAULT_SLUGYARD_MAX_BUFFER_MS
        bufferForPlaybackMs = if (customBuffers) bufferSettings.bufferForPlaybackMs else 3_000
        bufferForPlaybackAfterRebufferMs = if (customBuffers) bufferSettings.bufferForPlaybackAfterRebufferMs else 3_000
        backBufferMs = if (customBuffers) bufferSettings.backBufferDurationMs else DEFAULT_SLUGYARD_BACK_BUFFER_MS

        val safeLimitMb = getSafeNativeMemoryLimitMb(context)
        targetBufferSizeMb = if (customBuffers && !settings.bufferBudgetManaged) {
            val stored = bufferSettings.targetBufferSizeMb
            if (!settings.allowLargeTargetBuffer && stored > safeLimitMb) safeLimitMb else stored
        } else {
            safeLimitMb
        }

        val previousPoolSize = connectionPoolSize
        val customNetwork = settings.parallelNetworkEnabled
        connectionPoolSize = if (customNetwork && settings.useParallelConnections) {
            settings.parallelConnectionCount * 2
        } else {
            DEFAULT_SLUGYARD_CONNECTION_POOL_SIZE
        }
        if (connectionPoolSize != previousPoolSize) {
            sharedConnectionPool = okhttp3.ConnectionPool(connectionPoolSize, 3, TimeUnit.MINUTES)
        }
    }

    // ─── Memory detection ─────────────────────────────────────────────────────

    @Volatile
    private var cachedDevicePhysicalRamBytes: Long = 0L

    /** Clears the cached RAM size. Useful for testing. */
    fun clearCache() { cachedDevicePhysicalRamBytes = 0L }

    /** Reads total physical memory from /proc/meminfo as a reliable fallback. */
    private fun readRamFromMemInfo(): Long = try {
        val file = java.io.File("/proc/meminfo")
        if (file.exists()) {
            file.useLines { lines ->
                val firstLine = lines.firstOrNull().orEmpty()
                val matcher = java.util.regex.Pattern.compile("\\d+").matcher(firstLine)
                if (matcher.find()) matcher.group().toLong() * 1024L else 0L
            }
        } else 0L
    } catch (_: Exception) { 0L }

    /** Total physical memory of the device in bytes. */
    fun getDevicePhysicalRamBytes(context: Context): Long {
        cachedDevicePhysicalRamBytes.takeIf { it > 0L }?.let { return it }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (activityManager != null) {
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            if (memoryInfo.totalMem > 0L) {
                cachedDevicePhysicalRamBytes = memoryInfo.totalMem
                return memoryInfo.totalMem
            }
        }
        val ram = readRamFromMemInfo()
        if (ram > 0L) cachedDevicePhysicalRamBytes = ram
        return ram
    }

    /** Total physical memory of the device in GB. */
    fun getDevicePhysicalRamGb(context: Context): Double =
        getDevicePhysicalRamBytes(context).toDouble() / (1024.0 * 1024.0 * 1024.0)

    /**
     * Friendly, marketed description of device physical memory. Uses mid-point
     * boundaries adjusted for up to 20% hardware reservations.
     */
    fun getFriendlyRamLabel(context: Context): String {
        val total = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            total <= 0L -> "Unknown"
            total < 1.15 * gb -> "1 GB"
            total < 1.45 * gb -> "1.5 GB"
            total < 2.3 * gb -> "2 GB"
            total < 3.2 * gb -> "3 GB"
            total < 4.8 * gb -> "4 GB"
            total < 6.8 * gb -> "6 GB"
            total < 9.6 * gb -> "8 GB"
            total < 13.8 * gb -> "12 GB"
            else -> "16 GB"
        }
    }

    /** Safe ExoPlayer native target buffer size limit in MB based on RAM tier thresholds. */
    fun getSafeNativeMemoryLimitMb(context: Context): Int {
        val total = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            total <= 0L -> 250
            total < 1.15 * gb -> 150
            total < 1.45 * gb -> 200
            total < 2.3 * gb -> 250
            total < 3.2 * gb -> 500
            total < 4.8 * gb -> 1000
            total < 6.8 * gb -> 1600
            else -> 2000
        }
    }

    /** Warning native target buffer size limit in MB based on RAM tier thresholds. */
    fun getWarningNativeMemoryLimitMb(context: Context): Int {
        val total = getDevicePhysicalRamBytes(context)
        val gb = 1024L * 1024L * 1024L
        return when {
            total <= 0L -> 325
            total < 1.15 * gb -> 180
            total < 1.45 * gb -> 250
            total < 2.3 * gb -> 325
            total < 3.2 * gb -> 650
            total < 4.8 * gb -> 1200
            total < 6.8 * gb -> 2000
            else -> 2500
        }
    }

    // ─── LoadControl ──────────────────────────────────────────────────────────

    /** Builds a [DefaultLoadControl] tuned for SlugYard performance when enabled, or stock defaults otherwise. */
    fun buildLoadControl(context: Context? = null): DefaultLoadControl =
        if (enabled) {
            val targetBufferBytes = (targetBufferSizeMb.toLong() * 1024L * 1024L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, DEFAULT_SLUGYARD_ALLOCATOR_SEGMENT_SIZE, 64, enabled))
                .setTargetBufferBytes(targetBufferBytes)
                .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
                .setBackBuffer(backBufferMs, true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setTargetBufferBytes(100 * 1024 * 1024)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    70_000,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    5_000
                )
                .build()
        }

    // ─── BandwidthMeter ───────────────────────────────────────────────────────

    /** Builds a [DefaultBandwidthMeter] with an aggressive initial estimate when enabled. */
    fun buildBandwidthMeter(context: Context): DefaultBandwidthMeter =
        if (enabled) {
            DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(DEFAULT_SLUGYARD_INITIAL_BITRATE_ESTIMATE).build()
        } else {
            DefaultBandwidthMeter.Builder(context).build()
        }

    // ─── Seek / Scrubbing ─────────────────────────────────────────────────────

    /** Scrubbing parameters for fastest seek, or `null` when performance mode is off. */
    fun buildScrubbingParams(): ScrubbingModeParameters? {
        if (!enabled) return null
        return ScrubbingModeParameters.Builder()
            .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA))
            .setShouldIncreaseCodecOperatingRate(true)
            .setAllowSkippingMediaCodecFlush(true)
            .setShouldEnableDynamicScheduling(true)
            .build()
    }

    /**
     * Returns `true` when the seek target [positionMs] falls within the player's
     * already-buffered window. Only meaningful when performance mode is enabled.
     */
    fun isSeekInBuffer(player: androidx.media3.exoplayer.ExoPlayer, positionMs: Long): Boolean {
        if (!enabled) return false
        val bufferedPos = player.bufferedPosition
        val currentPos = player.currentPosition
        val backBufferStart = (currentPos - SEEK_BACK_BUFFER_THRESHOLD_MS - SEEK_BACKWARD_TOLERANCE_MS).coerceAtLeast(0L)
        return positionMs in backBufferStart..bufferedPos
    }

    // ─── Buffering UI ─────────────────────────────────────────────────────────

    fun shouldSuppressBufferingUi(
        suppressBufferingUiForSeek: Boolean,
        seekBufferingUiDeferred: Boolean,
        isBuffering: Boolean
    ): Boolean {
        if (!enabled) return false
        return (suppressBufferingUiForSeek && isBuffering) || (seekBufferingUiDeferred && isBuffering)
    }

    // ─── Networking ──────────────────────────────────────────────────────────

    /** Applies HTTP/2 and a connection pool to [builder] when performance mode is enabled. No-op otherwise. */
    fun applyNetworkOptimizations(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        val withPool = builder.connectionPool(sharedConnectionPool)
        return if (enableHttp2) {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        } else {
            withPool.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        }
    }

    // ─── Audio Renderer ──────────────────────────────────────────────────────

    /** `true` when the audio renderer should bypass the codec for a non-PCM format the sink supports directly. */
    fun shouldBypassForNonPcmFormat(): Boolean = enabled

    /** `true` when off-heap allocator memory logging should be active. */
    fun shouldLogMemoryFootprint(): Boolean = enabled

    /** `true` when track selection rebuild should be skipped after seeks (only allow on first ready). */
    fun shouldGuardTrackRebuild(): Boolean = enabled

    // ─── Engine Config ────────────────────────────────────────────────────────

    /**
     * Applies [SlugYardEngineConfig] based on the toggle state.
     * When enabled: native off-heap allocation + zero-copy ByteBuffer pipeline + 64 KB scratch.
     * When disabled: stock heap allocation + standard byte[] pipeline + 4 KB scratch.
     *
     * Must be called **before** building an ExoPlayer instance.
     */
    private fun applyEngineConfig(performanceModeEnabled: Boolean) {
        if (performanceModeEnabled) {
            SlugYardEngineConfig.set(SlugYardEngineConfig.slugyardMode())
        } else {
            SlugYardEngineConfig.set(SlugYardEngineConfig.stockMode())
        }
    }
}