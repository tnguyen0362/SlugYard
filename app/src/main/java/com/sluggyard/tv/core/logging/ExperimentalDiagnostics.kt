package com.sluggyard.tv.core.logging

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.sluggyard.tv.BuildConfig
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured Logcat diagnostics for the experimental build. Values are intentionally bounded
 * and sensitive-looking fields are redacted before they reach Logcat.
 */
object ExperimentalDiagnostics {
    private const val TAG = "SlugYardExperiment"
    private const val MAX_VALUE_LENGTH = 240
    private val sequence = AtomicLong(0L)

    fun event(
        surface: String,
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        if (!BuildConfig.DEBUG) return
        val id = sequence.incrementAndGet()
        val payload = fields
            .filter { it.second != null }
            .joinToString(" ") { (key, value) -> "$key=${safeValue(key, value)}" }
        Log.i(TAG, "seq=$id surface=${safeToken(surface)} event=${safeToken(name)} ${payload.ifBlank { "fields=none" }}")
    }

    fun event(
        surface: String,
        name: String,
        fields: Map<String, Any?>,
    ) {
        event(surface, name, *fields.map { it.key to it.value }.toTypedArray())
    }

    fun failure(
        surface: String,
        name: String,
        error: Throwable,
        vararg fields: Pair<String, Any?>,
    ) {
        event(
            surface,
            name,
            fields.toMap() + mapOf(
                "errorType" to error.javaClass.simpleName,
                "errorMessage" to error.diagnosticSummary(),
            ),
        )
    }

    fun settingMutation(
        setting: String,
        success: Boolean,
        durationMs: Long,
        changedKeys: Collection<String> = emptyList(),
        value: Any? = null,
    ) {
        event(
            "settings",
            "mutation",
            mapOf(
                "setting" to setting,
                "success" to success,
                "durationMs" to durationMs,
                "changedKeys" to changedKeys.sorted().joinToString(",").ifBlank { "none" },
                "value" to value,
            ),
        )
    }

    fun logDeviceSnapshot(context: Context) {
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { memory?.getMemoryInfo(it) }
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay
            ?.getRealMetrics(displayMetrics)
        val codecInfos = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList())
        val codecSummary = codecInfos
            .asSequence()
            .filter { it.supportedTypes.any { type -> type.startsWith("video/") || type.startsWith("audio/") } }
            .take(80)
            .joinToString(",") { codec ->
                val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ":hw=${codec.isHardwareAccelerated}:sw=${codec.isSoftwareOnly}"
                } else {
                    ""
                }
                "${codec.name}$hardware"
            }
        event(
            "compatibility",
            "device_snapshot",
            mapOf(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "product" to Build.PRODUCT,
                "sdk" to Build.VERSION.SDK_INT,
                "release" to Build.VERSION.RELEASE,
                "abis" to Build.SUPPORTED_ABIS.joinToString(","),
                "displayWidth" to displayMetrics.widthPixels,
                "displayHeight" to displayMetrics.heightPixels,
                "displayDensity" to displayMetrics.density,
                "memoryTotalMb" to memoryInfo.totalMem / (1024L * 1024L),
                "memoryAvailableMb" to memoryInfo.availMem / (1024L * 1024L),
                "memoryLow" to memoryInfo.lowMemory,
                "codecCount" to codecInfos.size,
                "codecs" to codecSummary,
            ),
        )
    }

    private fun safeValue(key: String, value: Any?): String {
        if (value == null) return "null"
        val normalizedKey = key.filter(Char::isLetterOrDigit).lowercase()
        if (normalizedKey in setOf(
                "apikey",
                "token",
                "accesstoken",
                "refreshtoken",
                "secret",
                "password",
                "cookie",
                "authorization",
                "credential",
                "credentials",
            )
        ) return "<redacted>"
        return value.toString()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("https?://[^\\s,]+")) { match ->
                val host = runCatching { URI(match.value).host }.getOrNull()
                "<url:${host ?: "redacted"}>"
            }
            .take(MAX_VALUE_LENGTH)
    }

    private fun safeToken(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        .take(80)
        .ifBlank { "unknown" }
}
