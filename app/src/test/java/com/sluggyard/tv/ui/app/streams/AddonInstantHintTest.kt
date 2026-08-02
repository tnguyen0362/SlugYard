package com.sluggyard.tv.ui.app.streams

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonInstantHintTest {
    @Test
    fun detectsMeteorTorrentioInstantBadges() {
        assertTrue(looksAddonMarkedInstant("[TB⚡] 1080p WEB-DL"))
        assertTrue(looksAddonMarkedInstant("TorBox Instant 2160p"))
        assertTrue(looksAddonMarkedInstant("Cached | Remux"))
        assertFalse(looksAddonMarkedInstant("Movie.1080p.WEB-DL.x264"))
        assertFalse(looksAddonMarkedInstant("download to debrid"))
    }
}
