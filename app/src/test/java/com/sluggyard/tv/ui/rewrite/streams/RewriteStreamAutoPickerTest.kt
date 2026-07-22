package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RewriteStreamAutoPickerTest {
    @Test
    fun directStreamsWinOverCachedAndTorrentCandidates() {
        val direct = candidate("direct", directUrl = "https://stream.test/video")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")

        assertEquals(direct, selectRewriteAutoPlayCandidate(groups(direct, cached)))
    }

    @Test
    fun cachedTorrentWinsOverUnknownFallback() {
        val unknown = candidate("unknown", cacheState = StreamCacheState.UNKNOWN, infoHash = "unknown-hash")
        val cached = candidate("cached", cacheState = StreamCacheState.CACHED, infoHash = "cached-hash")

        assertEquals(cached, selectRewriteAutoPlayCandidate(groups(unknown, cached)))
    }

    @Test
    fun checkingAndNotCachedCandidatesAreNotAutoSelected() {
        val checking = candidate("checking", cacheState = StreamCacheState.CHECKING, infoHash = "checking-hash")
        val notCached = candidate("not-cached", cacheState = StreamCacheState.NOT_CACHED, infoHash = "not-cached-hash")

        assertNull(selectRewriteAutoPlayCandidate(groups(checking, notCached)))
    }

    private fun groups(vararg candidates: RewriteStreamCandidate) = listOf(
        RewriteStreamGroup("source", "Source", RewriteStreamGroupState.Content(candidates.toList())),
    )

    private fun candidate(
        id: String,
        directUrl: String? = null,
        infoHash: String? = null,
        cacheState: StreamCacheState = StreamCacheState.NOT_APPLICABLE,
    ) = RewriteStreamCandidate(id, id, "Source", null, cacheState, directUrl, infoHash)
}
