package com.sluggyard.tv.core.player

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackLaunchRequestTest {
    @Test
    fun decodesDisplayFieldsAndPreservesStableIdentity() {
        val request = PlaybackLaunchRequest.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https://media.example/video.m3u8",
                    "title" to "The%20Movie",
                    "contentId" to "tt1234567",
                    "contentType" to "movie",
                    "videoId" to "tt1234567",
                    "sources" to "[\"tracker:https://tracker.example/announce\"]",
                ),
            ),
        )

        assertEquals("https://media.example/video.m3u8", request.streamUrl)
        assertEquals("The Movie", request.title)
        assertEquals("tt1234567", request.contentId)
        assertEquals("movie", request.contentType)
        assertEquals("tt1234567", request.videoId)
        assertEquals("[\"tracker:https://tracker.example/announce\"]", request.sourcesJson)
        assertEquals(listOf("https://tracker.example/announce"), request.torrentTrackers)
    }

    @Test
    fun malformedDisplayEncodingFallsBackToRawValue() {
        val request = PlaybackLaunchRequest.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https://media.example/video.mkv",
                    "title" to "bad%escape",
                    "contentId" to "movie-1",
                ),
            ),
        )

        assertEquals("bad%escape", request.title)
        assertEquals("movie-1", request.contentId)
    }

    @Test
    fun readsParentIdentityFromNavArgs() {
        val request = PlaybackLaunchRequest.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https://media.example/ep.mkv",
                    "contentId" to "tt0944947:1:2",
                    "contentType" to "series",
                    "parentId" to "tt0944947",
                    "parentType" to "series",
                ),
            ),
        )

        assertEquals("tt0944947", request.parentId)
        assertEquals("series", request.parentType)
        assertEquals("tt0944947:1:2", request.contentId)
    }

    @Test
    fun trackerExtractionIgnoresNonTrackerEntriesAndMalformedJson() {
        val filtered = PlaybackLaunchRequest.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https://media.example/video.mkv",
                    "sources" to "[\"source:https://example/video\",\"tracker:udp://tracker.example:80\"]",
                ),
            ),
        )
        val malformed = PlaybackLaunchRequest.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https://media.example/video.mkv",
                    "sources" to "not-json",
                ),
            ),
        )

        assertEquals("[\"source:https://example/video\",\"tracker:udp://tracker.example:80\"]", filtered.sourcesJson)
        assertEquals(listOf("udp://tracker.example:80"), filtered.torrentTrackers)
        assertTrue(malformed.torrentTrackers.isEmpty())
    }
}
