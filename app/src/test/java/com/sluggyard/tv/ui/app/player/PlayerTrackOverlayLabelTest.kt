package com.sluggyard.tv.ui.app.player

import com.sluggyard.tv.ui.screens.player.TrackInfo
import com.sluggyard.tv.ui.util.languageCodeToName
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTrackOverlayLabelTest {
    @Test
    fun `audio picker uses language embedded in descriptive track name`() {
        val track = TrackInfo(
            index = 0,
            name = "Japanese 2.0 Opus",
            language = null,
            codec = "opus",
            channelCount = 2,
        )

        assertEquals("${languageCodeToName("ja")} · Opus", audioTrackUiLabel(track))
    }
}
