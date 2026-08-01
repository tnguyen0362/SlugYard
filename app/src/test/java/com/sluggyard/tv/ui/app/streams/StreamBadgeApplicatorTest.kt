package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState
import com.sluggyard.tv.core.streams.StreamBadgeFilter
import com.sluggyard.tv.core.streams.StreamBadgeImport
import com.sluggyard.tv.core.streams.StreamBadgeRules
import com.sluggyard.tv.core.streams.StreamBadgeRulesParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamBadgeApplicatorTest {
    @Test
    fun matchesXperienceStyleResolutionAndHdrFromFilename() {
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(
                        StreamBadgeFilter(
                            id = "r-4k",
                            name = "4K",
                            pattern = "(?i)^(?=.*(?:2160[pi]?|4k|uhd|ultra[\\s._-]?hd))(?!.*(?:1080[pi]?|720[pi]?))",
                            imageURL = "https://cdn.example/4k.webp",
                        ),
                        StreamBadgeFilter(
                            id = "v-dv",
                            name = "Dolby Vision",
                            pattern = "(?i)^(?=.*\\b(?:dv|dovi|dolby[\\s._-]?vision)\\b)(?!.*\\batmos\\b)",
                            imageURL = "https://cdn.example/dv.webp",
                        ),
                    ),
                    isActive = true,
                ),
            ),
        )
        val groups = listOf(
            StreamGroup(
                addonId = "comet",
                addonName = "Comet",
                state = StreamGroupState.Content(
                    listOf(
                        StreamCandidate(
                            id = "1",
                            title = "Movie",
                            sourceLabel = "Comet",
                            detailLabel = null,
                            cacheState = StreamCacheState.CACHED,
                            filename = "Movie.2024.2160p.DV.WEB-DL.mkv",
                        ),
                    ),
                ),
            ),
        )

        val applied = StreamBadgeApplicator.apply(groups, rules)
        val badges = (applied.single().state as StreamGroupState.Content).streams.single().badges
        assertEquals(listOf("4K", "Dolby Vision"), badges.map { it.name })
    }

    @Test
    fun parsesRealXperiencePayloadShape() {
        val payload = """
            {"groups":[{"id":"gr","name":"Resolution"}],"filters":[
              {"type":"filter","id":"r-1080","name":"1080p","pattern":"(?i)\\b1080[pi]?\\b",
               "imageURL":"https://cdn.xperience-app.com/badges/xp_white/1080p.webp","isEnabled":true,"groupId":"gr"}
            ]}
        """.trimIndent()
        val imported = StreamBadgeRulesParser.parse("https://xperience-app.com/badges/test.json", payload)
        assertTrue(imported.filters.any { it.name == "1080p" })
    }
}
