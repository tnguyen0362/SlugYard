package com.sluggyard.tv.ui.app.debrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdditionalDebridResponseDecoderTest {
    private val json = Json

    @Test
    fun `premiumize ignores malformed file entries and maps playable links`() {
        val result = PremiumizeResponseDecoder.files(payload("""{
            "status":"success","content":[
              {"path":"Season 01/Episode.mkv","size":123,"link":"https://cdn.example/episode"},
              {"path":"missing-link.mkv"}
            ]
        }"""))

        assertEquals(
            listOf(PremiumizeFile("Season 01/Episode.mkv", "https://cdn.example/episode", 123)),
            assertIs<TorboxResult.Success<List<PremiumizeFile>>>(result).value,
        )
    }

    @Test
    fun `real debrid maps files and download links without treating missing optional fields as fatal`() {
        val result = RealDebridResponseDecoder.torrent(payload("""{
            "id":"abc", "status":"downloaded",
            "files":[{"id":4,"path":"/Episode.mkv","bytes":456},{"path":"missing-id"}],
            "links":["https://host.example/link",""]
        }"""))

        assertEquals(
            RealDebridTorrent("abc", "downloaded", listOf(RealDebridFile(4, "/Episode.mkv", 456)), listOf("https://host.example/link")),
            assertIs<TorboxResult.Success<RealDebridTorrent>>(result).value,
        )
    }

    @Test
    fun `real debrid requires a torrent id`() {
        assertIs<TorboxResult.InvalidResponse>(RealDebridResponseDecoder.torrent(payload("""{"files":[]}""")))
    }

    @Test
    fun `torbox download request includes token query required by TorBox requestdl`() {
        val path = TorboxRequestPaths.downloadLink(apiKey = "secret-key", torrentId = 42, fileId = 7)

        assertEquals(
            "v1/api/torrents/requestdl?token=secret-key&torrent_id=42&file_id=7&zip_link=false&redirect=false&append_name=false",
            path,
        )
        kotlin.test.assertTrue(path.contains("token=secret-key"))
    }

    private fun payload(value: String) = json.parseToJsonElement(value).jsonObject
}
