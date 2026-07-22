package com.sluggyard.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.sluggyard.tv.core.network.IPv4FirstDns
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
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
        defaultHeaders.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.let { (_, authValue) ->
                builder.addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (request.header("Authorization") == null) {
                        chain.proceed(request.newBuilder().header("Authorization", authValue).build())
                    } else {
                        chain.proceed(request)
                    }
                }
            }
        val client = builder.let { ExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }.build()
        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
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
}