package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPresentationTest {
    @Test
    fun detailLineIncludesSizeSeedsAndSource() {
        val line = StreamPresentation.detailLine(
            StreamCandidate(
                id = "1",
                title = "Show.S01E01.1080p",
                sourceLabel = "Torrentio",
                detailLabel = "WEB-DL",
                cacheState = StreamCacheState.CACHED,
                videoSizeBytes = 1_500_000_000L,
                seeders = 42,
            ),
        )
        assertTrue(line.contains("Torrentio"))
        assertTrue(line.contains("GB"))
        assertTrue(line.contains("42 seeds"))
        assertTrue(line.contains("WEB-DL"))
    }

    @Test
    fun cacheLabelMapsInstantAndDownload() {
        assertEquals("Instant", StreamPresentation.cacheLabel(StreamCacheState.CACHED))
        assertEquals("Download", StreamPresentation.cacheLabel(StreamCacheState.NOT_CACHED))
        assertNull(StreamPresentation.cacheLabel(StreamCacheState.NOT_APPLICABLE))
    }
}
