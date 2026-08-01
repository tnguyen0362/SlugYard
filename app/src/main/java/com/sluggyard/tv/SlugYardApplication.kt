package com.sluggyard.tv

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.data.local.CrashReportingPreferences
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism
import com.sluggyard.tv.core.sync.SupabaseSyncScheduler

import okio.Path.Companion.toOkioPath
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

@HiltAndroidApp
class SlugYardApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        initSentry()
        SupabaseSyncScheduler.schedulePeriodic(this)
    }

    /**
     * Crash/ANR reporting to our Sentry project (DSN from build config).
     * Opt-out via About → Crash reporting (default on).
     *
     * Deliberately light: no screenshots, view hierarchy, session replay, or
     * full logcat. Bounded breadcrumbs only — logging everything would tax TV
     * I/O and risk leaking tokens in URLs.
     */
    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN.takeIf { it.isNotBlank() } ?: return
        val crashReportingEnabled = getSharedPreferences(
            CrashReportingPreferences.PREFS_NAME,
            MODE_PRIVATE,
        ).getBoolean(
            CrashReportingPreferences.KEY_ENABLED,
            CrashReportingPreferences.DEFAULT_ENABLED,
        )
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.isEnabled = crashReportingEnabled
            options.environment = BuildConfig.SENTRY_ENVIRONMENT
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.sessionReplay.sessionSampleRate = 0.0
            options.sessionReplay.onErrorSampleRate = 0.0
            options.tracesSampleRate = 0.0
            options.profilesSampleRate = 0.0
            // Enough trail for "what screen / last network call", not a log dump.
            options.maxBreadcrumbs = 40
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableActivityLifecycleBreadcrumbs = true
            options.isEnableAppLifecycleBreadcrumbs = true
            options.isEnableNetworkEventBreadcrumbs = true
            options.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                // Drop noisy debug/info console crumbs; keep navigation + http + error.
                val type = breadcrumb.type?.lowercase().orEmpty()
                val category = breadcrumb.category?.lowercase().orEmpty()
                when {
                    type == "debug" || category == "console" || category == "log" -> null
                    type == "http" || category == "http" || category.contains("network") -> {
                        scrubSentryHttpBreadcrumb(breadcrumb)
                        breadcrumb
                    }
                    else -> breadcrumb
                }
            }
            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                event.request = null
                event.setUser(null)
                event
            }
        }
    }

    /** Strip query/fragment so signed debrid/addon URLs never land in Sentry breadcrumbs. */
    private fun scrubSentryHttpBreadcrumb(breadcrumb: io.sentry.Breadcrumb) {
        val keys = listOf("url", "request_url", "http.url")
        for (key in keys) {
            val raw = breadcrumb.getData(key) as? String ?: continue
            breadcrumb.setData(key, scrubUrlForCrashReports(raw))
        }
        breadcrumb.message?.takeIf { it.contains("http", ignoreCase = true) }?.let { msg ->
            breadcrumb.message = scrubUrlForCrashReports(msg)
        }
    }

    private fun scrubUrlForCrashReports(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        return runCatching {
            val uri = java.net.URI(trimmed)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                trimmed.substringBefore('?').substringBefore('#')
            } else {
                buildString {
                    append(uri.scheme).append("://").append(uri.host)
                    if (uri.port > 0) append(':').append(uri.port)
                    append(uri.path.orEmpty())
                }
            }
        }.getOrElse {
            trimmed.substringBefore('?').substringBefore('#')
        }
    }

    companion object {
        /** Shared, in-memory cookie jar for the owner-retained playback transport. */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                // Use a lean OkHttpClient for image fetching — no HTTP cache (Coil's own
                // DiskCache handles caching), no cookie jar, no logging interceptors.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    // TV boxes often have only a small heap available beside
                    // MediaCodec. A 33% image cache can starve the decoder
                    // during a hero + rail burst; keep artwork responsive
                    // without competing with video playback.
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                // Low-RAM TV boxes share flash with media cache; keep artwork disk smaller there.
                val diskBytes = if (am?.isLowRamDevice == true) {
                    96L * 1024 * 1024
                } else {
                    160L * 1024 * 1024
                }
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(diskBytes)
                    .build()
            }
            .crossfade(false)
            // EXACT makes Coil actually decode/scale to the size the poster card measures
            // instead of reusing an approximately-sized cached bitmap, which was the source of
            // visibly soft/blurry posters whenever a smaller cached decode got reused at a
            // larger card size. allowRgb565 was trading poster color depth (16-bit, visible
            // banding on the gradient-heavy skies/lighting typical of movie art) for a small
            // memory saving that isn't worth it on the app's single most prominent visual asset.
            .precision(coil3.size.Precision.EXACT)
            .allowHardware(true)
            .allowRgb565(false)
            // Three bounded decodes let the first visible rail populate faster
            // without flooding low-RAM Android TV devices.
            .bitmapFactoryMaxParallelism(3)
            .build()
    }
}
