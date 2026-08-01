package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteStreamPresentationTest {
    @Test
    fun detailLineIncludesSizeSeedsAndSource() {
        val line = RewriteStreamPresentation.detailLine(
            RewriteStreamCandidate(
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
        assertEquals("Instant", RewriteStreamPresentation.cacheLabel(StreamCacheState.CACHED))
        assertEquals("Download", RewriteStreamPresentation.cacheLabel(StreamCacheState.NOT_CACHED))
        assertNull(RewriteStreamPresentation.cacheLabel(StreamCacheState.NOT_APPLICABLE))
    }
}
