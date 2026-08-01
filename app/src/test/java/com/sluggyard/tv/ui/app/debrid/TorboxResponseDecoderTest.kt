package com.sluggyard.tv.ui.app.debrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorboxResponseDecoderTest {

    @Test
    fun `cache decoder normalizes returned hash keys`() {
        val result = TorboxResponseDecoder.cachedHashes(payload("""{"data":{"ABCDEF":{},"0123":{}}}"""))

        assertEquals(setOf("abcdef", "0123"), (result as TorboxResult.Success).value)
    }

    @Test
    fun `torrent decoder accepts either documented identifier field`() {
        assertEquals(7, (TorboxResponseDecoder.torrentId(payload("""{"data":{"torrent_id":7}}""")) as TorboxResult.Success).value)
        assertEquals(8, (TorboxResponseDecoder.torrentId(payload("""{"data":{"id":8}}""")) as TorboxResult.Success).value)
    }

    @Test
    fun `file decoder ignores malformed entries without discarding valid files`() {
        val result = TorboxResponseDecoder.files(payload("""{"data":{"files":[{"id":3,"short_name":"Season/file.mkv","mimetype":"video/x-matroska","size":42},{"name":"missing-id"}]}}"""))

        val files = (result as TorboxResult.Success).value
        assertEquals(listOf(TorboxFile(3, "file.mkv", "video/x-matroska", 42)), files)
    }

    @Test
    fun `cloud list decoder maps torrent rows`() {
        val result = TorboxResponseDecoder.cloudItems(
            payload(
                """{"data":[{"id":9,"name":"Movie.mkv","size":2048,"download_state":"completed"},{"name":"missing-id"}]}""",
            ),
        )

        val items = (result as TorboxResult.Success).value
        assertEquals(
            listOf(TorboxCloudItem(9, "Movie.mkv", 2048, "completed")),
            items,
        )
    }

    @Test
    fun `invalid envelopes are surfaced rather than treated as empty success`() {
        assertTrue(TorboxResponseDecoder.cachedHashes(payload("""{"success":true}""")) is TorboxResult.InvalidResponse)
        assertTrue(TorboxResponseDecoder.downloadUrl(payload("""{"data":""}""")) is TorboxResult.InvalidResponse)
        assertTrue(TorboxResponseDecoder.cloudItems(payload("""{"data":{}}""")) is TorboxResult.InvalidResponse)
    }

    private fun payload(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
