package com.sluggyard.tv.core.addonprotocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioResponseDecoderTest {

    @Test
    fun `catalog decoder retains valid items and isolates malformed entries`() {
        val payload = objectOf(
            """{"metas":[{"id":"movie-1","type":"movie","name":"Film","poster":"https://image"},{"id":"broken","name":"Missing type"}]}""",
        )

        val items = StremioResponseDecoder.catalogItems(payload)

        assertEquals(1, items.size)
        assertEquals("Film", items.single().title)
    }

    @Test
    fun `stream decoder supports both direct and torrent streams`() {
        val payload = objectOf(
            """{"streams":[{"name":"Direct","url":"https://play"},{"name":"Torrent","infoHash":"abc","fileIdx":2},{"title":"Ignored"}]}""",
        )

        val streams = StremioResponseDecoder.streamItems(payload)

        assertEquals(2, streams.size)
        assertEquals("https://play", streams[0].directUrl)
        assertEquals("abc", streams[1].infoHash)
        assertEquals(2, streams[1].fileIndex)
    }

    @Test
    fun `stream decoder accepts WatchHub externalUrl availability rows`() {
        val payload = objectOf(
            """{"streams":[{"name":"Netflix","title":"Netflix\nHD","externalUrl":"https://www.netflix.com/title/1"},{"name":"Broken"}]}""",
        )

        val streams = StremioResponseDecoder.streamItems(payload)

        assertEquals(1, streams.size)
        assertEquals("Netflix", streams.single().sourceName)
        assertEquals("https://www.netflix.com/title/1", streams.single().externalUrl)
        assertEquals(null, streams.single().directUrl)
        assertEquals(null, streams.single().infoHash)
    }

    @Test
    fun `subtitle decoder accepts documented language aliases and drops no-url entries`() {
        val payload = objectOf("""{"subtitles":[{"lang":"en","url":"https://sub/en"},{"language":"es","url":"https://sub/es"},{"lang":"fr"}]}""")

        val subtitles = StremioResponseDecoder.subtitles(payload)

        assertEquals(listOf("en", "es"), subtitles.map(AddonSubtitleTrack::language))
        assertTrue(subtitles.all { it.url.startsWith("https://") })
    }

    private fun objectOf(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
