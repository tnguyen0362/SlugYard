package com.sluggyard.tv.ui.app.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sluggyard.tv.core.streamresolution.StreamCacheState

class StreamLinkFilterPolicyTest {

    private fun candidate(
        title: String,
        sizeBytes: Long? = null,
        description: String? = null,
        filename: String? = null,
    ) = StreamCandidate(
        id = title,
        title = title,
        sourceLabel = "test",
        detailLabel = description,
        cacheState = StreamCacheState.CACHED,
        videoSizeBytes = sizeBytes,
        filename = filename,
        streamDescription = description,
    )

    @Test
    fun alwaysHidesCam3dVr() {
        val settings = StreamLinkFilterSettings()
        assertFalse(StreamLinkFilterPolicy.allows(candidate("Movie.CAMRip.1080p"), settings))
        assertFalse(StreamLinkFilterPolicy.allows(candidate("Movie.3D.HSBS.1080p"), settings))
        assertFalse(StreamLinkFilterPolicy.allows(candidate("Concert.VR180.HEVC"), settings))
    }

    @Test
    fun remuxToggle() {
        val remux = candidate("Movie.BluRay.REMUX.2160p.HEVC")
        assertTrue(StreamLinkFilterPolicy.allows(remux, StreamLinkFilterSettings(allowRemux = true)))
        assertFalse(StreamLinkFilterPolicy.allows(remux, StreamLinkFilterSettings(allowRemux = false)))
    }

    @Test
    fun maxResolutionBlocksUhdWhen1080() {
        val uhd = candidate("Movie.2160p.WEB-DL.HEVC")
        assertFalse(
            StreamLinkFilterPolicy.allows(
                uhd,
                StreamLinkFilterSettings(maxResolution = StreamLinkMaxResolution.FHD_1080),
            ),
        )
        assertTrue(
            StreamLinkFilterPolicy.allows(
                uhd,
                StreamLinkFilterSettings(maxResolution = StreamLinkMaxResolution.UHD_4K),
            ),
        )
    }

    @Test
    fun sizeCeilingFailOpenWhenUnknown() {
        val unknown = candidate("Movie.1080p.WEB-DL")
        assertTrue(
            StreamLinkFilterPolicy.allows(
                unknown,
                StreamLinkFilterSettings(maxSizeGb = 5),
            ),
        )
    }

    @Test
    fun sizeCeilingBlocksLarge() {
        val big = candidate(
            "Movie.2160p.REMUX",
            sizeBytes = 40L * 1_073_741_824L,
        )
        assertFalse(
            StreamLinkFilterPolicy.allows(big, StreamLinkFilterSettings(maxSizeGb = 15, allowRemux = true)),
        )
        assertTrue(
            StreamLinkFilterPolicy.allows(big, StreamLinkFilterSettings(maxSizeGb = null, allowRemux = true)),
        )
    }

    @Test
    fun codecRequiredFailOpenUntagged() {
        val untagged = candidate("Movie.1080p.WEB-DL")
        assertTrue(
            StreamLinkFilterPolicy.allows(
                untagged,
                StreamLinkFilterSettings(codec = StreamLinkCodecFilter.HEVC),
            ),
        )
    }

    @Test
    fun codecRequiredBlocksOther() {
        val avc = candidate("Movie.1080p.x264.WEB-DL")
        assertFalse(
            StreamLinkFilterPolicy.allows(
                avc,
                StreamLinkFilterSettings(codec = StreamLinkCodecFilter.HEVC),
            ),
        )
        assertTrue(
            StreamLinkFilterPolicy.allows(
                avc,
                StreamLinkFilterSettings(codec = StreamLinkCodecFilter.H264),
            ),
        )
    }

    @Test
    fun dolbyVisionToggle() {
        val dv = candidate("Movie.DV.HDR.2160p.HEVC")
        assertTrue(StreamLinkFilterPolicy.allows(dv, StreamLinkFilterSettings(allowDolbyVision = true)))
        assertFalse(StreamLinkFilterPolicy.allows(dv, StreamLinkFilterSettings(allowDolbyVision = false)))
    }

    @Test
    fun filterGroupsEmptiesContent() {
        val groups = listOf(
            StreamGroup(
                addonId = "a",
                addonName = "A",
                state = StreamGroupState.Content(
                    listOf(candidate("Movie.CAM.1080p"), candidate("Movie.1080p.WEB-DL.x264")),
                ),
            ),
        )
        val filtered = StreamLinkFilterPolicy.filterGroups(groups, StreamLinkFilterSettings())
        val content = filtered.single().state as StreamGroupState.Content
        assertEquals(1, content.streams.size)
        assertEquals("Movie.1080p.WEB-DL.x264", content.streams.single().title)
    }

    @Test
    fun vrDoesNotMatchBare180Or360Tokens() {
        val settings = StreamLinkFilterSettings()
        assertTrue(
            StreamLinkFilterPolicy.allows(
                candidate("Movie.Something.180.min.Cut.1080p.WEB-DL.x264"),
                settings,
            ),
        )
        assertFalse(StreamLinkFilterPolicy.allows(candidate("Concert.VR180.HEVC"), settings))
        assertFalse(StreamLinkFilterPolicy.allows(candidate("Demo.fisheye.VR"), settings))
    }

    @Test
    fun emptiedByFiltersIgnoresWhileLoading() {
        val before = listOf(
            StreamGroup(
                addonId = "a",
                addonName = "A",
                state = StreamGroupState.Content(listOf(candidate("Movie.CAM.1080p"))),
            ),
            StreamGroup(
                addonId = "b",
                addonName = "B",
                state = StreamGroupState.Loading,
            ),
        )
        val after = StreamLinkFilterPolicy.filterGroups(before, StreamLinkFilterSettings())
        assertFalse(StreamLinkFilterPolicy.emptiedByFilters(before, after))
    }
}
