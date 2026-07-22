package com.sluggyard.tv.ui.rewrite.debrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RewriteAdditionalDebridResponseDecoderTest {
    private val json = Json

    @Test
    fun `premiumize ignores malformed file entries and maps playable links`() {
        val result = RewritePremiumizeResponseDecoder.files(payload("""{
            "status":"success","content":[
              {"path":"Season 01/Episode.mkv","size":123,"link":"https://cdn.example/episode"},
              {"path":"missing-link.mkv"}
            ]
        }"""))

        assertEquals(
            listOf(RewritePremiumizeFile("Season 01/Episode.mkv", "https://cdn.example/episode", 123)),
            assertIs<RewriteTorboxResult.Success<List<RewritePremiumizeFile>>>(result).value,
        )
    }

    @Test
    fun `real debrid maps files and download links without treating missing optional fields as fatal`() {
        val result = RewriteRealDebridResponseDecoder.torrent(payload("""{
            "id":"abc", "status":"downloaded",
            "files":[{"id":4,"path":"/Episode.mkv","bytes":456},{"path":"missing-id"}],
            "links":["https://host.example/link",""]
        }"""))

        assertEquals(
            RewriteRealDebridTorrent("abc", "downloaded", listOf(RewriteRealDebridFile(4, "/Episode.mkv", 456)), listOf("https://host.example/link")),
            assertIs<RewriteTorboxResult.Success<RewriteRealDebridTorrent>>(result).value,
        )
    }

    @Test
    fun `real debrid requires a torrent id`() {
        assertIs<RewriteTorboxResult.InvalidResponse>(RewriteRealDebridResponseDecoder.torrent(payload("""{"files":[]}""")))
    }

    @Test
    fun `torbox download request keeps credentials out of the URL`() {
        val path = RewriteTorboxRequestPaths.downloadLink(torrentId = 42, fileId = 7)

        assertEquals(
            "v1/api/torrents/requestdl?torrent_id=42&file_id=7&zip_link=false&redirect=false&append_name=false",
            path,
        )
        kotlin.test.assertFalse(path.contains("token", ignoreCase = true))
    }

    private fun payload(value: String) = json.parseToJsonElement(value).jsonObject
}
