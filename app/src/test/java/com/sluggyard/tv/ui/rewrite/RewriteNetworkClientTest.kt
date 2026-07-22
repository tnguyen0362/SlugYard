package com.sluggyard.tv.ui.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteNetworkClientTest {

    @Test
    fun `community source client has bounded independent request timeouts`() {
        val client = RewriteNetworkClient.create()

        assertEquals(8_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
        assertEquals(20_000, client.callTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
    }
}
