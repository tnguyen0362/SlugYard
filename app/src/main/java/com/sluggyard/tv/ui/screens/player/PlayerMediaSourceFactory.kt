@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import com.sluggyard.tv.SlugYardApplication
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.VodCacheSizeMode
import okhttp3.OkHttpClient
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds ExoPlayer [MediaSource]s for the SlugYard player, wiring up:
 *  - parallel HTTP range downloads (opt-in) via [ParallelRangeDataSource];
 *  - a VOD disk cache (opt-in) layered on top of the parallel upstream;
 *  - HLS/DASH adaptive sources with custom load-error handling;
 *  - optional custom extractors / subtitle parser factories (libass);
 *  - audio-delay shifting via [AudioDelayMediaSource].
 */
internal class PlayerMediaSourceFactory(private val context: Context) {
    private var customExtractorsFactory: ExtractorsFactory? = null
    private var customSubtitleParserFactory: SubtitleParser.Factory? = null
    private val loadErrorHandlingPolicy = PlayerLoadErrorHandlingPolicy()

    @Volatile private var currentVodCacheUrl: String? = null
    @Volatile private var currentVodCacheResolvedUrl: String? = null
    @Volatile private var currentVodCacheActive: Boolean = false
    @Volatile var progressiveRangeOverride: String? = null
    @Volatile var progressiveUrlOverride: String? = null
    private val parallelStartupPrefetchUnlocked = AtomicBoolean(true)

    fun unlockStartupPrefetch() {
        parallelStartupPrefetchUnlocked.set(true)
    }

    var useParallelConnections: Boolean = PlayerSettings.DEFAULT_USE_PARALLEL_CONNECTIONS
    var parallelConnectionCount: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT
    var parallelChunkSizeKb: Int = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB
    var exoPerformanceModeEnabled: Boolean = PlayerSettings.DEFAULT_EXO_PERFORMANCE_MODE_ENABLED
    var vodCacheEnabled: Boolean = PlayerSettings.DEFAULT_VOD_CACHE_ENABLED
    var vodCacheSizeMode: VodCacheSizeMode = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MODE
    var vodCacheSizeMb: Int = PlayerSettings.DEFAULT_VOD_CACHE_SIZE_MB

    // OkHttp client used only by the opt-in parallel-connections path.
    private val playbackHttpClient by lazy {
        PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
            .cookieJar(SlugYardApplication.extensionCookieJar)
            .let { ExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    fun configureSubtitleParsing(
        extractorsFactory: ExtractorsFactory?,
        subtitleParserFactory: SubtitleParser.Factory?
    ) {
        customExtractorsFactory = extractorsFactory
        customSubtitleParserFactory = subtitleParserFactory
    }

    fun createMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        filename: String? = null,
        responseHeaders: Map<String, String> = emptyMap(),
        mimeTypeOverride: String? = null,
        audioDelayUsProvider: (() -> Long)? = null,
        mediaMetadata: androidx.media3.common.MediaMetadata? = null
    ): MediaSource {
        val sanitizedHeaders = sanitizeHeaders(headers)
        val resolvedMimeType = mimeTypeOverride ?: inferMimeType(
            url = url, filename = filename, responseHeaders = responseHeaders
        )
        val playbackUrl = progressiveUrlOverride?.takeIf {
            resolvedMimeType?.lowercase(Locale.US) in PROGRESSIVE_RANGE_MIMES
        } ?: url
        val playbackHeaders = buildPlaybackRequestHeaders(
            url = playbackUrl,
            mimeType = resolvedMimeType,
            headers = sanitizedHeaders,
            rangeOverride = progressiveRangeOverride
        )
        val httpDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, playbackHeaders)
        val isHls = resolvedMimeType == MimeTypes.APPLICATION_M3U8
        val isDash = resolvedMimeType == MimeTypes.APPLICATION_MPD

        val mediaItemBuilder = MediaItem.Builder().setUri(playbackUrl)
        resolvedMimeType?.let(mediaItemBuilder::setMimeType)
        filename?.takeIf { it.isNotBlank() }?.let(mediaItemBuilder::setMediaId)
        mediaMetadata?.let(mediaItemBuilder::setMediaMetadata)
        if (subtitleConfigurations.isNotEmpty()) mediaItemBuilder.setSubtitleConfigurations(subtitleConfigurations)
        val mediaItem = mediaItemBuilder.build()

        // 1. Parallel connections (opt-in). ParallelRangeDataSource needs a concrete
        // OkHttpDataSource.Factory, so build one only on this path.
        parallelStartupPrefetchUnlocked.set(!(useParallelConnections && !isHls && !isDash))
        val progressiveUpstreamFactory: DataSource.Factory = if (useParallelConnections && !isHls && !isDash) {
            val okHttpFactory = OkHttpDataSource.Factory(playbackHttpClient).apply {
                setDefaultRequestProperties(playbackHeaders)
                setUserAgent(DEFAULT_USER_AGENT)
            }
            ParallelRangeDataSource.Factory(
                okHttpFactory,
                parallelConnectionCount,
                parallelChunkSizeKb.toLong() * 1024L,
                useNativeMemory = exoPerformanceModeEnabled,
                shouldAllowBackgroundPrefetch = { true },
                onResolvedUri = { resolved -> currentVodCacheResolvedUrl = resolved?.toString() }
            )
        } else {
            httpDataSourceFactory
        }

        // 2. VOD disk cache (opt-in).
        val useVodCache = ENABLE_VOD_CACHE && vodCacheEnabled && !isHls && !isDash && isHttpScheme(playbackUrl)
        val previousVodCacheActive = currentVodCacheActive
        currentVodCacheUrl = playbackUrl
        currentVodCacheResolvedUrl = null
        val vodCacheMaxBytes = if (useVodCache && !isVodCacheDisabled) resolveVodCacheMaxBytes() else 0L
        val vodCacheActive = vodCacheMaxBytes > 0L

        if (vodCacheActive) {
            maybeApplyLiveVodCacheCapIncrease(context, vodCacheMaxBytes, !previousVodCacheActive)
        }

        val progressiveFactory: DataSource.Factory = if (vodCacheActive) {
            val cache = getReadySimpleCache(vodCacheMaxBytes) ?: getAnySimpleCache()
            if (cache != null) {
                currentVodCacheActive = true
                buildVodCacheDataSourceFactory(progressiveUpstreamFactory, cache)
            } else {
                currentVodCacheActive = false
                progressiveUpstreamFactory
            }
        } else {
            currentVodCacheActive = false
            progressiveUpstreamFactory
        }

        val extractorsFactory = customExtractorsFactory ?: DefaultExtractorsFactory()
        val defaultFactory = DefaultMediaSourceFactory(progressiveFactory, extractorsFactory).apply {
            setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            customSubtitleParserFactory?.let { setSubtitleParserFactory(it) }
        }
        val forceDefaultFactory = customExtractorsFactory != null

        // Sidecar subtitles are more reliable through DefaultMediaSourceFactory.
        if (subtitleConfigurations.isNotEmpty()) {
            return wrapAudioDelay(
                mediaSource = defaultFactory.createMediaSource(mediaItem),
                audioDelayUsProvider = audioDelayUsProvider
            )
        }

        val mediaSource = when {
            isHls && !forceDefaultFactory -> HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .applySubtitleParserFactory(customSubtitleParserFactory)
                .createMediaSource(mediaItem)
            isDash && !forceDefaultFactory -> DashMediaSource.Factory(httpDataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .applySubtitleParserFactory(customSubtitleParserFactory)
                .createMediaSource(mediaItem)
            else -> defaultFactory.createMediaSource(mediaItem)
        }
        return wrapAudioDelay(mediaSource = mediaSource, audioDelayUsProvider = audioDelayUsProvider)
    }

    fun shutdown() = Unit

    private fun HlsMediaSource.Factory.applySubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory?
    ): HlsMediaSource.Factory = apply { subtitleParserFactory?.let(::setSubtitleParserFactory) }

    private fun DashMediaSource.Factory.applySubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory?
    ): DashMediaSource.Factory = apply { subtitleParserFactory?.let(::setSubtitleParserFactory) }

    private fun buildVodCacheDataSourceFactory(
        upstreamFactory: DataSource.Factory,
        cache: SimpleCache
    ): DataSource.Factory {
        val dataSinkFactory = CacheDataSink.Factory().setCache(cache).setFragmentSize(2L * 1024L * 1024L)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(dataSinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun isHttpScheme(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        return scheme == "https" || scheme == "http"
    }

    private fun resolveVodCacheMaxBytes(): Long {
        val minBytes = PlayerSettings.MIN_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
        val maxBytes = PlayerSettings.MAX_VOD_CACHE_SIZE_MB.toLong() * 1024L * 1024L
        val runtimeMaxBytes = resolveRuntimeVodCacheUpperBoundBytes(maxBytes)
        // Not enough free space to host a useful cache: skip it (0 = caller streams direct).
        if (runtimeMaxBytes < minBytes) return 0L
        val manualBytes = vodCacheSizeMb
            .coerceIn(PlayerSettings.MIN_VOD_CACHE_SIZE_MB, PlayerSettings.MAX_VOD_CACHE_SIZE_MB)
            .toLong() * 1024L * 1024L
        val resolvedManualBytes = manualBytes.coerceAtMost(runtimeMaxBytes)

        if (vodCacheSizeMode == VodCacheSizeMode.MANUAL) return resolvedManualBytes

        val freeSpaceBytes = context.cacheDir.usableSpace
        if (freeSpaceBytes <= 0L) return resolvedManualBytes
        val autoBytes = freeSpaceBytes / 5L // 20% for a healthy buffer
        return autoBytes.coerceIn(minBytes, runtimeMaxBytes)
    }

    private fun resolveRuntimeVodCacheUpperBoundBytes(hardMaxBytes: Long): Long {
        val freeSpaceBytes = context.cacheDir.usableSpace
        val headroomAdjusted = if (freeSpaceBytes > VOD_CACHE_FREE_SPACE_RESERVE_BYTES) {
            freeSpaceBytes - VOD_CACHE_FREE_SPACE_RESERVE_BYTES
        } else {
            (freeSpaceBytes * 8L) / 10L
        }
        return headroomAdjusted.coerceAtLeast(1L * 1024L * 1024L).coerceAtMost(hardMaxBytes)
    }

    companion object {
        private const val MIME_VIDEO_QUICK_TIME = "video/quicktime"
        private const val ENABLE_VOD_CACHE = true
        private const val VOD_CACHE_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L * 1024L
        internal const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val MIME_PROBE_CACHE_SIZE = 64

        data class StreamProbeInfo(
            val contentLength: Long,
            val acceptsRanges: Boolean
        )

        private val probeInfoCache = object : LinkedHashMap<String, StreamProbeInfo>(MIME_PROBE_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamProbeInfo>?): Boolean =
                size > MIME_PROBE_CACHE_SIZE
        }

        @JvmStatic
        fun getProbeInfo(url: String, headers: Map<String, String>): StreamProbeInfo? {
            val sanitized = sanitizeHeaders(headers)
            val cacheKey = buildMimeProbeCacheKey(url, sanitized)
            return synchronized(probeInfoCache) { probeInfoCache[cacheKey] }
        }

        private fun cacheProbeInfo(url: String, headers: Map<String, String>, contentLength: Long, acceptsRanges: Boolean) {
            val sanitized = sanitizeHeaders(headers)
            val cacheKey = buildMimeProbeCacheKey(url, sanitized)
            synchronized(probeInfoCache) { probeInfoCache[cacheKey] = StreamProbeInfo(contentLength, acceptsRanges) }
        }

        private fun buildMimeProbeCacheKey(url: String, headers: Map<String, String>): String {
            if (headers.isEmpty()) return url
            return buildString {
                append(url)
                headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (k, v) ->
                    append('|'); append(k); append('='); append(v)
                }
            }
        }

        @Volatile private var sharedSimpleCache: SimpleCache? = null
        @Volatile private var configuredVodCacheMaxBytes: Long = -1L
        @Volatile private var isVodCacheDisabled: Boolean = false

        fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String> {
            val raw: Map<*, *> = headers ?: return emptyMap()
            if (raw.isEmpty()) return emptyMap()
            val sanitized = LinkedHashMap<String, String>(raw.size)
            raw.forEach { (rawKey, rawValue) ->
                val key = (rawKey as? String)?.trim().orEmpty()
                val value = (rawValue as? String)?.trim().orEmpty()
                if (key.isEmpty() || value.isEmpty()) return@forEach
                if (key.equals("Range", ignoreCase = true)) return@forEach
                sanitized[key] = value
            }
            return sanitized
        }

        fun parseHeaders(headers: String?): Map<String, String> {
            if (headers.isNullOrEmpty()) return emptyMap()
            return try {
                // JSON format first (new).
                if (headers.trimStart().startsWith("{")) {
                    val json = org.json.JSONObject(headers)
                    val result = LinkedHashMap<String, String>()
                    json.keys().forEach { key ->
                        val value = json.optString(key, "")
                        if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
                    }
                    sanitizeHeaders(result)
                } else {
                    // Legacy key=value&key=value format (backward compat).
                    val parsed = headers.split("&").associate { pair ->
                        val parts = pair.split("=", limit = 2)
                        if (parts.size == 2) {
                            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                        } else "" to ""
                    }.filterKeys { it.isNotEmpty() }
                    sanitizeHeaders(parsed)
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun getReadySimpleCache(expectedMaxBytes: Long): SimpleCache? {
            val cache = sharedSimpleCache ?: return null
            return if (configuredVodCacheMaxBytes == expectedMaxBytes) cache else null
        }

        private fun getAnySimpleCache(): SimpleCache? = sharedSimpleCache

        private fun maybeApplyLiveVodCacheCapIncrease(
            context: Context,
            requestedMaxBytes: Long,
            allowLiveReconfigure: Boolean
        ) {
            // Live cache reconfiguration is not yet implemented; the shared cache is
            // created lazily elsewhere. Kept as the integration point for the VOD cache.
        }

        private fun inferAdaptiveMimeTypeFromPath(path: String?): String? {
            val normalized = path?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            val pathWithoutFragment = normalized.substringBefore('#')
            val pathPart = pathWithoutFragment.substringBefore('?')
            val fileName = pathPart.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            return when (extension) {
                "m3u8", "m3u" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                "ism", "isml" -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }

        /**
         * Some progressive providers return an HLS playlist for an un-ranged request but the
         * actual Matroska file for an initial byte-range request. Keep the range hint limited to
         * file-like media so adaptive manifests retain their normal request behavior.
         */
        internal fun buildPlaybackRequestHeaders(
            url: String,
            mimeType: String?,
            headers: Map<String, String>,
            rangeOverride: String? = null
        ): Map<String, String> {
            val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.US)
            val normalizedMime = mimeType?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return headers
            if (normalizedMime !in PROGRESSIVE_RANGE_MIMES) return headers
            val range = rangeOverride?.takeIf { it.startsWith("bytes=", ignoreCase = true) } ?: return headers
            return LinkedHashMap(headers).apply { put("Range", range) }
        }

        internal fun inferMimeType(
            url: String,
            filename: String?,
            responseHeaders: Map<String, String>? = null
        ): String? {
            val adaptiveMime = inferAdaptiveMimeTypeFromPath(filename) ?: inferAdaptiveMimeTypeFromPath(url)
            if (adaptiveMime != null) return adaptiveMime
            return inferMimeTypeFromResponseHeaders(responseHeaders)
                ?: inferMimeTypeFromPath(filename)
                ?: inferMimeTypeFromPath(url)
        }

        internal fun normalizeMimeType(contentType: String?): String? {
            val normalized = contentType
                ?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return null
            return when (normalized) {
                "application/vnd.apple.mpegurl", "application/mpegurl", "application/x-mpegurl",
                "audio/mpegurl", "audio/x-mpegurl", "application/m3u8" -> MimeTypes.APPLICATION_M3U8
                "application/dash+xml", "video/vnd.mpeg.dash.mpd" -> MimeTypes.APPLICATION_MPD
                "application/vnd.ms-sstr+xml" -> MimeTypes.APPLICATION_SS
                "video/mp4", "application/mp4", "video/x-m4v" -> MimeTypes.VIDEO_MP4
                "video/webm", "audio/webm" -> MimeTypes.VIDEO_WEBM
                "video/x-matroska", "audio/x-matroska", "video/mkv", "audio/mkv" -> MimeTypes.VIDEO_MATROSKA
                else -> null
            }
        }

        internal fun sniffManifestMimeType(snippet: String?): String? {
            val normalized = snippet?.trimStart()?.lowercase(Locale.US) ?: return null
            return when {
                normalized.startsWith("#extm3u") -> MimeTypes.APPLICATION_M3U8
                normalized.startsWith("<?xml") && normalized.contains("<mpd") -> MimeTypes.APPLICATION_MPD
                normalized.startsWith("<mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        suspend fun probeMimeType(
            url: String,
            headers: Map<String, String>,
            filename: String? = null,
            responseHeaders: Map<String, String>? = null
        ): String? = inferMimeType(url = url, filename = filename, responseHeaders = responseHeaders)

        private fun inferMimeTypeFromResponseHeaders(headers: Map<String, String>?): String? {
            if (headers.isNullOrEmpty()) return null
            val contentType = headers.entries
                .firstOrNull { (k, _) -> k.equals("Content-Type", ignoreCase = true) }?.value
            normalizeMimeType(contentType)?.let { return it }

            val contentDisposition = headers.entries
                .firstOrNull { (k, _) -> k.equals("Content-Disposition", ignoreCase = true) }?.value
                ?: return null

            val filename = contentDisposition
                .substringAfter("filename*=", missingDelimiterValue = "")
                .substringAfterLast("''", missingDelimiterValue = "")
                .ifBlank { contentDisposition.substringAfter("filename=", missingDelimiterValue = "") }
                .trim().trim('"', '\'').takeIf { it.isNotBlank() }
            return inferMimeTypeFromPath(filename)
        }

        private fun inferMimeTypeFromPath(path: String?): String? {
            val normalized = path?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            val pathWithoutFragment = normalized.substringBefore('#')
            val pathPart = pathWithoutFragment.substringBefore('?')
            val queryPart = pathWithoutFragment.substringAfter('?', missingDelimiterValue = "")
            val fileName = pathPart.substringAfterLast('/')
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

            return when (extension) {
                "m3u8", "m3u" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                "ism", "isml" -> MimeTypes.APPLICATION_SS
                "mkv" -> MimeTypes.VIDEO_MATROSKA
                "webm" -> MimeTypes.VIDEO_WEBM
                "mp4", "m4v" -> MimeTypes.VIDEO_MP4
                "ts", "mts", "m2ts" -> MimeTypes.VIDEO_MP2T
                "mov" -> MIME_VIDEO_QUICK_TIME
                "avi" -> MimeTypes.VIDEO_AVI
                "mpeg", "mpg" -> MimeTypes.VIDEO_MPEG
                else -> inferMimeTypeFromQuery(queryPart)
                    ?: inferMimeTypeFromDelimitedToken(pathPart)
                    ?: inferMimeTypeFromDelimitedToken(queryPart)
            }
        }

        private fun inferMimeTypeFromQuery(query: String): String? {
            if (query.isBlank()) return null
            query.split('&').forEach { parameter ->
                val key = parameter.substringBefore('=', missingDelimiterValue = "").trim()
                val value = parameter.substringAfter('=', missingDelimiterValue = "").trim()
                if (key.isBlank() || value.isBlank()) return@forEach

                if (key in MIME_HINT_QUERY_KEYS) {
                    val token = value.substringAfterLast('/').substringAfterLast('.')
                    val mime = mimeFromExtensionToken(token)
                    if (mime != null) return mime
                }
                val mime = mimeFromValueToken(value)
                if (mime != null) return mime
            }
            return null
        }

        private fun inferMimeTypeFromDelimitedToken(value: String): String? {
            if (value.isBlank()) return null
            return when {
                DELIMITED_M3U8_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
                PLAYLIST_HLS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
                DELIMITED_MPD_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_MPD
                DELIMITED_SS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }

        private fun mimeFromExtensionToken(token: String): String? = when (token) {
            "m3u8", "m3u" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            "ism", "isml" -> MimeTypes.APPLICATION_SS
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "ts", "mts", "m2ts" -> MimeTypes.VIDEO_MP2T
            "mov" -> MIME_VIDEO_QUICK_TIME
            "avi" -> MimeTypes.VIDEO_AVI
            "mpeg", "mpg" -> MimeTypes.VIDEO_MPEG
            else -> null
        }

        private fun mimeFromValueToken(value: String): String? = when (value) {
            "application/vnd.apple.mpegurl", "application/mpegurl", "application/x-mpegurl",
            "audio/mpegurl", "audio/x-mpegurl", "application/m3u8", "m3u8", "m3u", "hls" -> MimeTypes.APPLICATION_M3U8
            "application/dash+xml", "video/vnd.mpeg.dash.mpd", "dash" -> MimeTypes.APPLICATION_MPD
            "application/vnd.ms-sstr+xml", "smoothstreaming", "ss" -> MimeTypes.APPLICATION_SS
            else -> null
        }

        private fun wrapAudioDelay(
            mediaSource: MediaSource,
            audioDelayUsProvider: (() -> Long)?
        ): MediaSource = if (audioDelayUsProvider == null) mediaSource
        else AudioDelayMediaSource(mediaSource = mediaSource, audioDelayUsProvider = audioDelayUsProvider)

        private val DELIMITED_M3U8_PATTERN = Regex("(^|[=/_.?&-])(m3u8|m3u)($|[=/_.?&-])")
        private val PLAYLIST_HLS_PATTERN = Regex("/(playlist|hls|manifest|master|vs)/(?!stream$|list$|info$|details$)[a-zA-Z0-9_/-]+$")
        private val DELIMITED_MPD_PATTERN = Regex("(^|[=/_.?&-])mpd($|[=/_.?&-])")
        private val DELIMITED_SS_PATTERN = Regex("(^|[=/_.?&-])(ism|isml)($|[=/_.?&-])")

        private val MIME_HINT_QUERY_KEYS = setOf(
            "format", "mime", "mime_type", "contenttype", "content_type", "type",
            "ext", "extension", "output", "protocol", "mode", "stream", "service"
        )

        private val PROGRESSIVE_RANGE_MIMES = setOf(
            MimeTypes.VIDEO_MATROSKA,
            "audio/x-matroska",
            "video/mkv",
            "audio/mkv",
            MimeTypes.VIDEO_WEBM,
            "audio/webm"
        )

        /**
         * Extracts `user:password` from a URL's userinfo component and converts it
         * to a Basic Auth header. Returns the cleaned URL (without userinfo) and
         * merged headers. If the URL has no userinfo, returns the original URL and
         * headers unchanged.
         */
        fun extractUserInfoAuth(
            url: String,
            headers: Map<String, String>
        ): Pair<String, Map<String, String>> {
            if (url.isBlank()) return url to headers
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url to headers
            val userInfo = uri.userInfo?.takeIf { it.isNotBlank() } ?: return url to headers
            // Already has an Authorization header — don't override.
            if (headers.any { it.key.equals("Authorization", ignoreCase = true) }) return url to headers
            val encoded = android.util.Base64.encodeToString(
                userInfo.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            val cleanUri = java.net.URI(
                uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment
            )
            val merged = LinkedHashMap(headers).apply { this["Authorization"] = "Basic $encoded" }
            return cleanUri.toString() to merged
        }
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private class PlayerLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(6) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpException = loadErrorInfo.exception.findCause<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
        if (httpException != null) {
            val code = httpException.responseCode
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 410) {
                return androidx.media3.common.C.TIME_UNSET
            }
        }
        val timeout = loadErrorInfo.exception.findCause<SocketTimeoutException>() != null
        return if (timeout) {
            when (loadErrorInfo.errorCount) {
                1 -> 750L
                2 -> 1500L
                else -> 3000L
            }
        } else super.getRetryDelayMsFor(loadErrorInfo)
    }
}
