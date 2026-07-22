package com.sluggyard.tv.core.sync.remote

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupabaseHttpTransportTest {
    @Test
    fun `transport requires https`() {
        assertThrows(IllegalArgumentException::class.java) {
            SupabaseHttpTransport(OkHttpClient(), "http://example.test", "anon-key")
        }
    }

    @Test
    fun `transport sends credentials in headers and normalizes response headers`() = runTest {
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        val request = slot<Request>()
        every { client.newCall(capture(request)) } returns call
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://example.test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(206)
            .message("Partial Content")
            .header("X-Next-Cursor", "100")
            .body("[]".toResponseBody("application/json".toMediaType()))
            .build()

        val response = SupabaseHttpTransport(client, "https://example.test/", "anon-key")
            .execute(
                path = "/rest/v1/library?select=*",
                method = "POST",
                body = "{\"content_id\":\"movie\"}",
                accessToken = "access-token",
                headers = mapOf("Prefer" to "return=minimal"),
            )

        assertEquals("https://example.test/rest/v1/library?select=*", request.captured.url.toString())
        assertEquals("anon-key", request.captured.header("apikey"))
        assertEquals("Bearer access-token", request.captured.header("Authorization"))
        assertEquals("return=minimal", request.captured.header("Prefer"))
        assertEquals("100", response.headers["x-next-cursor"])
    }

    @Test
    fun `transport maps network failures without swallowing cancellation`() = runTest {
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } throws java.io.IOException("offline")

        val response = SupabaseHttpTransport(client, "https://example.test", "anon-key")
            .execute("/rest/v1/library", "GET")

        assertEquals(0, response.code)
    }

    @Test
    fun `transport propagates cancellation`() = runTest {
        val client = mockk<OkHttpClient>()
        val call = mockk<Call>()
        every { client.newCall(any()) } returns call
        every { call.execute() } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                SupabaseHttpTransport(client, "https://example.test", "anon-key")
                    .execute("/rest/v1/library", "GET")
            }
        }
    }
}
