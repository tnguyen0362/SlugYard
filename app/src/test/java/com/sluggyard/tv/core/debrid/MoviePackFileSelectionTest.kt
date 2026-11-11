package com.sluggyard.tv.core.debrid

import com.sluggyard.tv.core.streamresolution.DebridFileHint
import com.sluggyard.tv.core.streamresolution.DebridManualResolver
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.streamresolution.ManualResolutionResult
import com.sluggyard.tv.core.streamresolution.ManualStreamResolutionCoordinator
import com.sluggyard.tv.core.streamresolution.ManualStreamSelection
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.data.remote.dto.TorboxTorrentFileDto
import com.sluggyard.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for "asked for Twilight, played New Moon". The addon picked file 'Twilight 1' of
 * a five-movie saga pack; the hint was dropped before file selection, no season/episode exist
 * for a movie, and the selector fell through to "largest video" which is New Moon.
 *
 * Fixture mirrors the real TorBox listing captured on the TV (ids are NOT in torrent order).
 */
class MoviePackFileSelectionTest {
    private val selector = TorboxFileSelector()

    private val sagaPack = listOf(
        file(4, "Twilight 1 (2008) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv", 3_966_480_091),
        file(3, "Twilight 2 New Moon (2009) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv", 4_478_325_927),
        file(2, "Twilight 3 Eclipse (2010) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv", 3_596_075_859),
        file(1, "Twilight 4 Breaking Dawn parte 1 (2011) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv", 3_957_008_809),
        file(0, "Twilight 5 Breaking Dawn parte 2 (2012) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv", 3_471_912_099),
    )

    @Test
    fun `addon filename selects the first movie from a saga pack`() {
        val selected = selector.selectFile(
            files = sagaPack,
            resolve = resolve(
                fileIdx = null,
                filename = "Twilight 1 (2008) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv",
            ),
            season = null,
            episode = null,
        )
        assertEquals(4, selected?.id)
    }

    @Test
    fun `filename hint beats a misleading torrent file index on reversed provider ids`() {
        // Torrentio sends the torrent-order index (0) but TorBox numbers this pack in reverse,
        // so id 0 is Breaking Dawn 2. The filename has to win.
        val selected = selector.selectFile(
            files = sagaPack,
            resolve = resolve(
                fileIdx = 0,
                filename = "Twilight 1 (2008) AC3 5.1 ITA.ENG 2160p H265 HDR10 sub ita.eng Sp33dy94-MIRCrew.mkv",
            ),
            season = null,
            episode = null,
        )
        assertEquals(4, selected?.id)
    }

    @Test
    fun `saga pack without any file hint refuses to guess the largest movie`() {
        val selected = selector.selectFile(
            files = sagaPack,
            resolve = resolve(fileIdx = null, filename = null),
            season = null,
            episode = null,
        )
        assertNull(selected)
    }

    @Test
    fun `generic short hint cannot match an arbitrary pack file by containment`() {
        val selected = selector.selectFile(
            files = sagaPack,
            resolve = resolve(fileIdx = null, filename = "Twilight.mkv"),
            season = null,
            episode = null,
        )
        assertNull(selected)
    }

    @Test
    fun `exact name match beats an earlier file that merely contains the hint`() {
        val selected = selector.selectFile(
            files = listOf(
                file(1, "The Movie Name 2008 1080p BluRay Extended Cut.mkv", 9_000),
                file(2, "The Movie Name 2008 1080p BluRay.mkv", 8_000),
            ),
            resolve = resolve(fileIdx = null, filename = "The Movie Name 2008 1080p BluRay.mkv"),
            season = null,
            episode = null,
        )
        assertEquals(2, selected?.id)
    }

    @Test
    fun `single movie with a small sample still picks the movie`() {
        val selected = selector.selectFile(
            files = listOf(
                file(1, "sample.mkv", 50_000_000),
                file(2, "The.Movie.2008.1080p.mkv", 8_000_000_000),
            ),
            resolve = resolve(fileIdx = null, filename = null),
            season = null,
            episode = null,
        )
        assertEquals(2, selected?.id)
    }

    @Test
    fun `coordinator passes addon filename down and reports the file actually resolved`() = runBlocking {
        val resolver = RecordingResolver(
            returned = ResolvedPlaybackSource(
                url = "https://resolved",
                sourceId = "hash",
                filename = "Twilight 1 (2008).mkv",
                videoSizeBytes = 123L,
            ),
        )
        val result = ManualStreamResolutionCoordinator(setOf(resolver)).prepare(
            ManualStreamSelection(
                id = "src",
                directUrl = null,
                infoHash = "hash",
                fileIndex = null,
                filename = "Twilight 1 (2008).mkv",
                videoSizeBytes = 999L,
            ),
            DebridService.TORBOX,
        )
        assertEquals(DebridFileHint("Twilight 1 (2008).mkv", 999L), resolver.lastHint)
        val source = (result as ManualResolutionResult.Ready).source
        assertEquals("Twilight 1 (2008).mkv", source.filename)
        // Size comes from the provider's chosen file, not the addon's advertised number.
        assertEquals(123L, source.videoSizeBytes)
    }

    @Test
    fun `coordinator does not relabel a differently resolved file with the addon name`() = runBlocking {
        val resolver = RecordingResolver(
            returned = ResolvedPlaybackSource(
                url = "https://resolved",
                sourceId = "hash",
                filename = "Twilight 2 New Moon (2009).mkv",
                videoSizeBytes = 456L,
            ),
        )
        val result = ManualStreamResolutionCoordinator(setOf(resolver)).prepare(
            ManualStreamSelection(
                id = "src",
                directUrl = null,
                infoHash = "hash",
                fileIndex = null,
                filename = "Twilight 1 (2008).mkv",
            ),
            DebridService.TORBOX,
        )
        val source = (result as ManualResolutionResult.Ready).source
        assertNotNull(source.filename)
        assertEquals("Twilight 2 New Moon (2009).mkv", source.filename)
    }

    private class RecordingResolver(private val returned: ResolvedPlaybackSource) : DebridManualResolver {
        override val service = DebridService.TORBOX
        var lastHint: DebridFileHint? = null

        override suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource = returned

        override suspend fun resolve(
            infoHash: String,
            fileIndex: Int?,
            season: Int?,
            episode: Int?,
            hint: DebridFileHint?,
        ): ResolvedPlaybackSource {
            lastHint = hint
            return returned
        }
    }

    private fun file(id: Int, name: String, size: Long) = TorboxTorrentFileDto(
        id = id,
        name = name,
        shortName = null,
        absolutePath = null,
        mimeType = null,
        size = size,
    )

    private fun resolve(fileIdx: Int?, filename: String?) = StreamClientResolve(
        type = null,
        infoHash = "hash",
        fileIdx = fileIdx,
        magnetUri = null,
        sources = null,
        torrentName = null,
        filename = filename,
        mediaType = null,
        mediaId = null,
        mediaOnlyId = null,
        title = null,
        season = null,
        episode = null,
        service = null,
        serviceIndex = null,
        serviceExtension = null,
        isCached = null,
    )
}
