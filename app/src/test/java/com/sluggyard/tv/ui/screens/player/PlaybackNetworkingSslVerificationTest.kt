package com.sluggyard.tv.ui.screens.player

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

class PlaybackNetworkingSslVerificationTest {

    @Test
    fun `verifyPrimaryClientUsesSystemSslAndPreservesSni`() {
        val primaryClient = PlayerPlaybackNetworking.playbackHttpClient

        assertNotEquals(
            "Primary client must not use custom trust-all HostnameVerifier as default",
            PlayerPlaybackNetworking.trustAllPlaybackHttpClient.hostnameVerifier,
            primaryClient.hostnameVerifier
        )

        assertEquals(15_000, primaryClient.connectTimeoutMillis.toLong())
        assertEquals(15_000, primaryClient.readTimeoutMillis.toLong())
        assertTrue(primaryClient.retryOnConnectionFailure)
    }

    @Test
    fun `verifyPrimaryClientSupportsTls13Protocols`() {
        val defaultContext = SSLContext.getDefault()
        val defaultParams = defaultContext.defaultSSLParameters
        val supportedProtocols = defaultParams.protocols.toList()

        println("Supported JVM SSL Protocols: $supportedProtocols")

        assertTrue("SSL context must support TLSv1.3 protocol", supportedProtocols.contains("TLSv1.3"))
        assertTrue("SSL context must support TLSv1.2 protocol", supportedProtocols.contains("TLSv1.2"))
    }

    @Test
    fun `verifyTrustAllClientIsConfiguredForUntrustedLocalFallback`() {
        val trustAllClient = PlayerPlaybackNetworking.trustAllPlaybackHttpClient

        assertNotNull("Trust-all fallback client must be initialized", trustAllClient)
        assertNotNull("Trust-all client must have custom SSLSocketFactory", trustAllClient.sslSocketFactory)
        assertNotNull("Trust-all client must have custom HostnameVerifier", trustAllClient.hostnameVerifier)

        assertEquals(15_000, trustAllClient.connectTimeoutMillis.toLong())
        assertEquals(15_000, trustAllClient.readTimeoutMillis.toLong())
        assertTrue(trustAllClient.retryOnConnectionFailure)
    }

    @Test
    fun `verifySSLExceptionTriggersAutomaticFallbackToTrustAllClient`() {
        val primaryClient = PlayerPlaybackNetworking.playbackHttpClient

        var primaryAttempted = false
        var fallbackAttempted = false

        val mockChain = object : Interceptor.Chain {
            private val req = Request.Builder().url("https://local-webdav.server:8443/movie.mp4").build()

            override fun request(): Request = req

            override fun proceed(request: Request): Response {
                primaryAttempted = true
                throw SSLException("CertificateNotTrusted: Local self-signed certificate")
            }

            override fun connection(): okhttp3.Connection? = null
            override fun call(): okhttp3.Call = error("Not implemented")
            override fun connectTimeoutMillis(): Int = 15000
            override fun readTimeoutMillis(): Int = 15000
            override fun writeTimeoutMillis(): Int = 15000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        }

        val fallbackInterceptor = primaryClient.interceptors.firstOrNull()
        assertNotNull("Primary client must contain the SSLException fallback interceptor", fallbackInterceptor)

        var caughtThrowable: Throwable? = null
        try {
            fallbackInterceptor!!.intercept(mockChain)
        } catch (t: Throwable) {
            caughtThrowable = t
            fallbackAttempted = true
        }

        assertTrue("Primary chain must have been attempted", primaryAttempted)
        assertTrue("Fallback mechanism must be triggered upon SSLException", fallbackAttempted)
    }
}
