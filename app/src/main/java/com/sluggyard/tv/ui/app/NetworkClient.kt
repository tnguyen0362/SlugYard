package com.sluggyard.tv.ui.app

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Network policy for the independently rendered rewrite data sources.
 *
 * A single provider failure must resolve quickly enough for the TV UI to show its other rows and
 * its retry state. This deliberately stays separate from the retained playback transport.
 */
internal object NetworkClient {
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // Long enough for AIOStreams (Torz/SeaDex/AnimeTosho + TorBox cache) within the
            // 20s AIO fanout budget; first-byte idle must not die at the old 15s read cap.
            .readTimeout(22, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** One connection pool for the Rewrite addon and provider fan-out. */
    fun create(): OkHttpClient = sharedClient
}
