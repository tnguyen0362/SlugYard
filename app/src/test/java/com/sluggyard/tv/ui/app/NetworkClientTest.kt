package com.sluggyard.tv.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClientTest {

    @Test
    fun `community source client has bounded independent request timeouts`() {
        val client = NetworkClient.create()

        assertEquals(8_000, client.connectTimeoutMillis)
        assertEquals(22_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
        assertEquals(25_000, client.callTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
    }
}
