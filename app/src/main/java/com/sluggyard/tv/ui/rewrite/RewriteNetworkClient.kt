package com.sluggyard.tv.ui.rewrite

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Network policy for the independently rendered rewrite data sources.
 *
 * A single provider failure must resolve quickly enough for the TV UI to show its other rows and
 * its retry state. This deliberately stays separate from the retained playback transport.
 */
internal object RewriteNetworkClient {
    fun create(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
}
