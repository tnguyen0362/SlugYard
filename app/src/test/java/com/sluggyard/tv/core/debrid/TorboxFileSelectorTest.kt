package com.sluggyard.tv.core.debrid

import com.sluggyard.tv.data.remote.dto.TorboxTorrentFileDto
import com.sluggyard.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Test

class TorboxFileSelectorTest {
    private val selector = TorboxFileSelector()

    @Test
    fun `selects file by torbox file id first`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "wrong.mkv", size = 20),
                file(id = 9, name = "right.mkv", size = 10)
            ),
            resolve = resolve(fileIdx = 9),
            season = null,
            episode = null
        )

        assertEquals(9, selected?.id)
    }

    @Test
    fun `falls back to filename match`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "sample.mkv", size = 300),
                file(id = 2, name = "show.s01e02.1080p.mkv", size = 200)
            ),
            resolve = resolve(fileIdx = 88, filename = "show.s01e02.1080p.mkv"),
            season = null,
            episode = null
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `falls back to episode pattern before largest video`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "show.s01e01.mkv", size = 800),
                file(id = 2, name = "show.s01e02.mkv", size = 300)
            ),
            resolve = resolve(fileIdx = null),
            season = 1,
            episode = 2
        )

        assertEquals(2, selected?.id)
    }

    @Test
    fun `matches dash-numbered episode before requested index and ignores creditless special`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 18, name = "show (Creditless ED 01).mkv", size = 400),
                file(id = 12, name = "show - 01 [Multi-Sub].mkv", size = 3_600),
                file(id = 28, name = "show - Especial 01.mkv", size = 100),
            ),
            resolve = resolve(fileIdx = 0),
            season = 1,
            episode = 1,
        )

        assertEquals(12, selected?.id)
    }

    @Test
    fun `does not use requested index when episode file is ambiguous`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 0, name = "show (Creditless ED 01).mkv", size = 400),
                file(id = 1, name = "show (Creditless OP 01).mkv", size = 300),
            ),
            resolve = resolve(fileIdx = 0),
            season = 1,
            episode = 1,
        )

        assertEquals(null, selected)
    }

    @Test
    fun `falls back to largest playable video`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 1, name = "small.txt", size = 900),
                file(id = 2, name = "small.mkv", size = 200),
                file(id = 3, name = "large.mp4", size = 500)
            ),
            resolve = resolve(fileIdx = null),
            season = null,
            episode = null
        )

        assertEquals(3, selected?.id)
    }

    @Test
    fun `does not pick title ending in episode digit over absolute pack number`() {
        // Regression: S01E04 selected "…028 - Hidden Inventory 4" because bare token "4" matched.
        val selected = selector.selectFile(
            files = listOf(
                file(id = 44, name = "Jujutsu Kaisen - 028 - Hidden Inventory 4.mkv", size = 900),
                file(id = 12, name = "Jujutsu Kaisen - 004 - Curse Womb Must Die.mkv", size = 800),
                file(id = 1, name = "Jujutsu Kaisen - 001 - Ryomen Sukuna.mkv", size = 700),
            ),
            resolve = resolve(fileIdx = null, season = 1, episode = 4),
            season = 1,
            episode = 4,
        )

        assertEquals(12, selected?.id)
    }

    @Test
    fun `prefers listing filename over loose episode digit in title`() {
        val selected = selector.selectFile(
            files = listOf(
                file(id = 44, name = "Jujutsu Kaisen - 028 - Hidden Inventory 4.mkv", size = 900),
                file(id = 12, name = "Jujutsu Kaisen - 004 - Curse Womb Must Die.mkv", size = 800),
            ),
            resolve = resolve(
                fileIdx = null,
                season = 1,
                episode = 4,
                filename = "Jujutsu Kaisen - 004 - Curse Womb Must Die.mkv",
            ),
            season = 1,
            episode = 4,
        )

        assertEquals(12, selected?.id)
    }

    private fun file(id: Int, name: String, size: Long): TorboxTorrentFileDto = TorboxTorrentFileDto(
        id = id,
        name = name,
        shortName = null,
        absolutePath = null,
        mimeType = null,
        size = size
    )

    private fun resolve(
        fileIdx: Int?,
        filename: String? = null,
        season: Int = 1,
        episode: Int = 2,
    ): StreamClientResolve = StreamClientResolve(
        type = "debrid",
        infoHash = "hash",
        fileIdx = fileIdx,
        magnetUri = "magnet:?xt=urn:btih:hash",
        sources = null,
        torrentName = "show",
        filename = filename,
        mediaType = "series",
        mediaId = "tt1:1:2",
        mediaOnlyId = "tt1",
        title = "show",
        season = season,
        episode = episode,
        service = "torbox",
        serviceIndex = 0,
        serviceExtension = null,
        isCached = true
    )
}
