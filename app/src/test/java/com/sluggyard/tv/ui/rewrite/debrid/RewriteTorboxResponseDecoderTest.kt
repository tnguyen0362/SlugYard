package com.sluggyard.tv.ui.rewrite.debrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteTorboxResponseDecoderTest {

    @Test
    fun `cache decoder normalizes returned hash keys`() {
        val result = RewriteTorboxResponseDecoder.cachedHashes(payload("""{"data":{"ABCDEF":{},"0123":{}}}"""))

        assertEquals(setOf("abcdef", "0123"), (result as RewriteTorboxResult.Success).value)
    }

    @Test
    fun `torrent decoder accepts either documented identifier field`() {
        assertEquals(7, (RewriteTorboxResponseDecoder.torrentId(payload("""{"data":{"torrent_id":7}}""")) as RewriteTorboxResult.Success).value)
        assertEquals(8, (RewriteTorboxResponseDecoder.torrentId(payload("""{"data":{"id":8}}""")) as RewriteTorboxResult.Success).value)
    }

    @Test
    fun `file decoder ignores malformed entries without discarding valid files`() {
        val result = RewriteTorboxResponseDecoder.files(payload("""{"data":{"files":[{"id":3,"short_name":"Season/file.mkv","mimetype":"video/x-matroska","size":42},{"name":"missing-id"}]}}"""))

        val files = (result as RewriteTorboxResult.Success).value
        assertEquals(listOf(RewriteTorboxFile(3, "file.mkv", "video/x-matroska", 42)), files)
    }

    @Test
    fun `invalid envelopes are surfaced rather than treated as empty success`() {
        assertTrue(RewriteTorboxResponseDecoder.cachedHashes(payload("""{"success":true}""")) is RewriteTorboxResult.InvalidResponse)
        assertTrue(RewriteTorboxResponseDecoder.downloadUrl(payload("""{"data":""}""")) is RewriteTorboxResult.InvalidResponse)
    }

    private fun payload(raw: String) = Json.parseToJsonElement(raw).jsonObject
}
