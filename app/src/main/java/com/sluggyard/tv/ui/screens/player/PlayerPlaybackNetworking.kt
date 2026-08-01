package com.sluggyard.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.sluggyard.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import com.sluggyard.tv.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Networking primitives shared by the playback pipeline: OkHttp clients
 * (system-trusted + trust-all fallback for self-signed local media servers),
 * ExoPlayer [DataSource.Factory] builders, and a low-level [HttpURLConnection]
 * opener used for MIME probing and range requests.
 */
internal object PlayerPlaybackNetworking {

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val trustAllSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * Fallback OkHttpClient with trust-all SSL for self-signed or untrusted
     * local media servers (self-signed WebDAV / Plex / Jellyfin).
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
            .dns(IPv4FirstDns())
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllManager)
            .hostnameVerifier(permissiveHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI
     * support. Falls back to [trustAllPlaybackHttpClient] automatically when an
     * [SSLException] occurs on self-signed local media servers.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 32 })
            .dns(IPv4FirstDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLException) {
                    trustAllPlaybackHttpClient.newCall(request).execute()
                }
            }
            .build()
    }

    @UnstableApi
    fun createHttpDataSourceFactory(defaultHeaders: Map<String, String> = emptyMap()): DataSource.Factory {
        val builder = playbackHttpClient.newBuilder()
        // OkHttp strips the Authorization header on cross-host redirects. WebDAV
        // servers behind reverse proxies commonly redirect to a different
        // host/port, causing auth to be lost. A network interceptor ensures the
        // header is always present on every outgoing request (mpv/curl behavior).
        val authValue = defaultHeaders.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
        val rangeValue = defaultHeaders.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
        if (authValue != null || rangeValue != null) {
            val remainingNetworkTraces = AtomicInteger(MAX_NETWORK_REQUEST_TRACES)
            builder.addNetworkInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder().apply {
                    if (authValue != null && original.header("Authorization") == null) {
                        header("Authorization", authValue)
                    }
                    if (rangeValue != null && original.header("Range") == null) {
                        header("Range", rangeValue)
                    }
                }.build()
                val response = chain.proceed(request)
                if (takeTraceSlot(remainingNetworkTraces)) {
                    Log.d(
                        TRACE_TAG,
                        "OkHttp hop requestHost=${request.url.host} range=${describeRange(request.header("Range"))} " +
                            "responseHost=${response.request.url.host} status=${response.code} " +
                            "contentType=${response.header("Content-Type") ?: "none"} " +
                            "contentRange=${response.header("Content-Range") ?: "none"}"
                    )
                }
                response
            }
        }
        val client = builder.let { ExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }.build()
        val upstreamFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return TracingDataSourceFactory(
            upstreamFactory = upstreamFactory,
            headerNames = defaultHeaders.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString(",")
        )
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory = DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders))

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        requestMethod = method
        setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        headers.forEach { (key, value) ->
            if (key.equals("Range", ignoreCase = true)) return@forEach
            if (key.equals("User-Agent", ignoreCase = true)) return@forEach
            setRequestProperty(key, value)
        }
        range?.let { setRequestProperty("Range", it) }
    }

    /** Performs the same no-range response sniff that the normal OkHttp playback path uses. */
    internal suspend fun probePlaybackResponse(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int = 128
    ): PlaybackResponseProbe? = withContext(Dispatchers.IO) {
        val sanitized = PlayerMediaSourceFactory.sanitizeHeaders(headers)
        val requestHeaders = LinkedHashMap(sanitized).apply {
            if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                put("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        val request = Request.Builder()
            .url(url)
            .headers(Headers.Builder().apply {
                requestHeaders.forEach { (key, value) -> add(key, value) }
            }.build())
            .get()
            .build()
        runCatching {
            playbackHttpClient.newCall(request).execute().use { response ->
                val bytes = response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(maxBytes)
                    var read = 0
                    while (read < buffer.size) {
                        val count = input.read(buffer, read, buffer.size - read)
                        if (count < 0) break
                        read += count
                    }
                    buffer.copyOf(read)
                } ?: ByteArray(0)
                PlaybackResponseProbe(
                    status = response.code,
                    contentType = response.header("Content-Type"),
                    finalHost = response.request.url.host,
                    initialBytes = bytes
                )
            }
        }.getOrNull()
    }

    /**
     * Bounded Exo request diagnostics. The validator is a separate HTTP client, so this records
     * the request position and response signature that the actual Media3 source sees. Values are
     * deliberately limited to safe metadata; no URL path, query, header values, or media bytes
     * beyond the first eight bytes are emitted.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private class TracingDataSourceFactory(
        private val upstreamFactory: DataSource.Factory,
        private val headerNames: String
    ) : DataSource.Factory {
        private val remainingTraces = AtomicInteger(MAX_EXO_REQUEST_TRACES)

        override fun createDataSource(): DataSource = TracingDataSource(
            upstream = upstreamFactory.createDataSource(),
            remainingTraces = remainingTraces,
            headerNames = headerNames
        )
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private class TracingDataSource(
        private val upstream: DataSource,
        private val remainingTraces: AtomicInteger,
        private val headerNames: String
    ) : DataSource {
        private var traceThisOpen = false
        private var firstReadLogged = false
        private var requestPosition = 0L

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            traceThisOpen = takeTraceSlot()
            firstReadLogged = false
            requestPosition = dataSpec.position
            return try {
                val resolvedLength = upstream.open(dataSpec)
                if (traceThisOpen) {
                    Log.d(
                        TRACE_TAG,
                        "Exo open host=${dataSpec.uri.host ?: "unknown"} " +
                            "position=${dataSpec.position} length=${dataSpec.length} " +
                            "resolvedHost=${upstream.uri?.host ?: "unknown"} " +
                            "openLength=$resolvedLength " +
                            "headers=${headerNames.ifBlank { "none" }} " +
                            "contentType=${responseHeader("Content-Type")} " +
                            "contentRange=${responseHeader("Content-Range")} " +
                            "acceptRanges=${responseHeader("Accept-Ranges")}"
                    )
                }
                resolvedLength
            } catch (failure: Exception) {
                if (traceThisOpen) {
                    Log.w(
                        TRACE_TAG,
                        "Exo open failed host=${dataSpec.uri.host ?: "unknown"} " +
                            "position=${dataSpec.position} length=${dataSpec.length} " +
                            "headers=${headerNames.ifBlank { "none" }} " +
                            "error=${failure.javaClass.simpleName}"
                    )
                }
                throw failure
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = upstream.read(buffer, offset, length)
            if (traceThisOpen && !firstReadLogged && read > 0) {
                firstReadLogged = true
                Log.d(
                    TRACE_TAG,
                    "Exo firstRead host=${upstream.uri?.host ?: "unknown"} " +
                        "position=$requestPosition bytes=$read prefix=${hexPrefix(buffer, offset, read)}"
                )
            }
            return read
        }

        override fun getUri() = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()

        private fun takeTraceSlot(): Boolean {
            return takeTraceSlot(remainingTraces)
        }

        private fun responseHeader(name: String): String = upstream.responseHeaders.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?: "none"
    }

    private fun hexPrefix(buffer: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, offset + 8)
        return buildString((end - offset) * 2) {
            for (index in offset until end) append("%02x".format(buffer[index].toInt() and 0xff))
        }
    }

    private fun takeTraceSlot(remainingTraces: AtomicInteger): Boolean {
        if (!BuildConfig.DEBUG) return false
        while (true) {
            val current = remainingTraces.get()
            if (current <= 0) return false
            if (remainingTraces.compareAndSet(current, current - 1)) return true
        }
    }

    private fun describeRange(range: String?): String = when {
        range == null -> "none"
        range.equals("bytes=0-", ignoreCase = true) -> "open"
        range.startsWith("bytes=", ignoreCase = true) -> "bounded"
        else -> "other"
    }

    private const val TRACE_TAG = "PlaybackRequestTrace"
    private const val MAX_EXO_REQUEST_TRACES = 16
    private const val MAX_NETWORK_REQUEST_TRACES = 16
}

internal data class PlaybackResponseProbe(
    val status: Int,
    val contentType: String?,
    val finalHost: String,
    val initialBytes: ByteArray
)
