package com.sluggyard.tv

import android.app.Application
import android.os.Build
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
     * Crash/ANR reporting. The Sentry SDK, gradle plugin, and proguard-mapping upload were fully
     * retained through the rewrite (see build.gradle.kts) but nothing ever called
     * SentryAndroid.init() -- `io.sentry.auto-init` is explicitly disabled in the manifest, so
     * without this call crash reporting was silently a no-op the whole time. Scrubs request/user
     * PII before send, matching the safe-context guarantee described in strings.xml's Sentry copy.
     */
    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN.takeIf { it.isNotBlank() } ?: return
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = BuildConfig.SENTRY_ENVIRONMENT
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                event.request = null
                event.setUser(null)
                event
            }
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
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
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
