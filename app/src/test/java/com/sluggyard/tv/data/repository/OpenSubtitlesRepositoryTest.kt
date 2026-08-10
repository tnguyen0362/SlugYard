package com.sluggyard.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSubtitlesRepositoryTest {
    @Test
    fun `empty languages omit the API language filter`() {
        assertNull(openSubtitlesLanguageQuery(emptyList()))
    }

    @Test
    fun `languages are sent as a comma separated filter`() {
        assertEquals("eng,jpn", openSubtitlesLanguageQuery(listOf("eng", "jpn")))
    }
}
