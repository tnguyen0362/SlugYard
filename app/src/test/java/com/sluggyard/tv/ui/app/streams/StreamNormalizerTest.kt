package com.sluggyard.tv.ui.app.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamNormalizerTest {
    @Test
    fun extractsHashFromMagnetAndTorrentSchemes() {
        assertEquals(
            "a".repeat(40),
            normalizeAddonStreamSource("magnet:?xt=urn:btih:${"a".repeat(40)}&dn=x", null).infoHash,
        )
        assertEquals(
            "b".repeat(40),
            normalizeAddonStreamSource("torrent://${"b".repeat(40)}/12", null).infoHash,
        )
    }

    @Test
    fun extractsHashFromTorrentioResolveAndQueryForms() {
        val hash = "c".repeat(40)
        val resolve = normalizeAddonStreamSource(
            "https://torrentio.strem.fun/torbox=KEY/resolve/TorBox/$hash/Name.mkv",
            null,
        )
        assertEquals(hash, resolve.infoHash)
        assertNull(resolve.playableDirectUrl)
        assertTrue(resolve.isTorrentOrDebridProxy)

        val query = normalizeAddonStreamSource(
            "https://example.test/stream?infoHash=$hash&fileIdx=1",
            null,
        )
        assertEquals(hash, query.infoHash)
    }

    @Test
    fun declaredHashWinsAndProxyUrlIsNeverTreatedAsDirect() {
        val hash = "d".repeat(40)
        val normalized = normalizeAddonStreamSource(
            "https://torrentio.strem.fun/resolve/TorBox/$hash/file.mkv",
            hash,
        )
        assertEquals(hash, normalized.infoHash)
        assertNull(normalized.playableDirectUrl)
    }
}
