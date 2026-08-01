package com.sluggyard.tv.core.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.jsonObject

class TraktTransportTest {
    private val credentials = TraktCredentials("client", "token")

    @Test
    fun `request factory supplies required OAuth and API headers`() {
        val request = TraktRequestFactory.request(credentials, "/sync/last_activities")

        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("client", request.header("trakt-api-key"))
        assertEquals("2", request.header("trakt-api-version"))
        assertEquals("GET", request.method)
    }

    @Test
    fun `episode scrobble uses show and episode payload`() {
        val payload = TraktPayloadEncoder.scrobble(
            TraktScrobbleRequest("stop", 101.0, imdbId = "tt123", mediaType = "episode", season = 2, episode = 3),
        )

        assertEquals("100.0", payload["progress"].toString())
        assertEquals("\"tt123\"", payload["show"]!!.jsonObject["ids"]!!.jsonObject["imdb"].toString())
        assertEquals("3", payload["episode"]!!.jsonObject["number"].toString())
        assertNull(payload["movie"])
    }
}
