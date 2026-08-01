package com.sluggyard.tv.ui.app.details

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsResolvedDisplayTitleTest {
    @Test
    fun `prefers real title over generic episode label`() {
        val episode = DetailsEpisode(
            id = "e1",
            number = 1,
            title = "Pilot",
            releaseLabel = null,
            thumbnailUrl = null,
            watched = false,
            description = "A long synopsis that should not win.",
        )
        assertEquals("Pilot", episode.resolvedDisplayTitle())
    }

    @Test
    fun `falls back past generic Episode N to description line`() {
        val episode = DetailsEpisode(
            id = "e2",
            number = 2,
            title = "Episode 2",
            releaseLabel = null,
            thumbnailUrl = null,
            watched = false,
            displayNumber = 2,
            description = "The Heist\nMore plot details follow.",
        )
        assertEquals("The Heist", episode.resolvedDisplayTitle())
    }

    @Test
    fun `falls back to Episode displayNumber when title and description are empty`() {
        val episode = DetailsEpisode(
            id = "e3",
            number = 7,
            title = "  ",
            releaseLabel = null,
            thumbnailUrl = null,
            watched = false,
            displayNumber = 3,
            description = null,
        )
        assertEquals("Episode 3", episode.resolvedDisplayTitle())
    }
}
