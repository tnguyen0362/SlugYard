package com.sluggyard.tv.data.repository

import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.AddonResource
import com.sluggyard.tv.domain.model.ContentType
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMergeUtilsTest {

    private fun stream(
        name: String? = "name",
        title: String? = "title",
        url: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        externalUrl: String? = null,
        ytId: String? = null,
        addonName: String = "addon",
        clientResolve: StreamClientResolve? = null
    ): Stream = Stream(
        name = name,
        title = title,
        description = null,
        url = url,
        ytId = ytId,
        infoHash = infoHash,
        fileIdx = fileIdx,
        externalUrl = externalUrl,
        behaviorHints = null,
        addonName = addonName,
        addonLogo = null,
        clientResolve = clientResolve
    )

    @Test
    fun `dedupKey is case-insensitive on infoHash and includes fileIdx`() {
        val a = stream(infoHash = "ABC123", fileIdx = 0)
        val b = stream(infoHash = "abc123", fileIdx = 0)
        assertEquals(StreamMergeUtils.dedupKey(a), StreamMergeUtils.dedupKey(b))
    }

    @Test
    fun `dedupKey distinguishes different fileIdx for the same infoHash`() {
        val a = stream(infoHash = "abc123", fileIdx = 0)
        val b = stream(infoHash = "abc123", fileIdx = 1)
        assertFalse(StreamMergeUtils.dedupKey(a) == StreamMergeUtils.dedupKey(b))
    }

    @Test
    fun `dedupKey falls back to url when there is no infoHash`() {
        val a = stream(url = "https://example.com/a")
        assertEquals("https://example.com/a", StreamMergeUtils.dedupKey(a))
    }

    @Test
    fun `dedupKey falls back to addonName-name-title as a last resort`() {
        val a = stream(name = "N", title = "T", addonName = "Torrentio")
        assertEquals("Torrentio:N:T", StreamMergeUtils.dedupKey(a))
    }

    @Test
    fun `mergeStreams overwrites existing entries with incoming entries sharing a dedup key`() {
        val existing = listOf(stream(infoHash = "abc", fileIdx = 0, name = "old"))
        val incoming = listOf(stream(infoHash = "abc", fileIdx = 0, name = "new"))

        val merged = StreamMergeUtils.mergeStreams(existing, incoming)

        assertEquals(1, merged.size)
        assertEquals("new", merged.single().name)
    }

    @Test
    fun `mergeStreams keeps distinct entries from both lists`() {
        val existing = listOf(stream(infoHash = "abc", fileIdx = 0))
        val incoming = listOf(stream(infoHash = "def", fileIdx = 0))

        val merged = StreamMergeUtils.mergeStreams(existing, incoming)

        assertEquals(2, merged.size)
    }

    private fun streamAddon(
        resourceTypes: List<String> = listOf("movie"),
        resourceIdPrefixes: List<String>? = null,
        addonIdPrefixes: List<String> = emptyList()
    ): Addon = Addon(
        id = "addon",
        name = "addon",
        displayName = "Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE),
        resources = listOf(
            AddonResource(name = "stream", types = resourceTypes, idPrefixes = resourceIdPrefixes)
        ),
        idPrefixes = addonIdPrefixes
    )

    @Test
    fun `supportsStreamResource is true when the addon declares no idPrefixes at all`() {
        val addon = streamAddon()
        assertTrue(StreamMergeUtils.supportsStreamResource(addon, "movie", "tt1"))
    }

    @Test
    fun `supportsStreamResource respects resource-level idPrefixes over addon-level`() {
        val addon = streamAddon(resourceIdPrefixes = listOf("kitsu:"), addonIdPrefixes = listOf("tt"))
        assertFalse(StreamMergeUtils.supportsStreamResource(addon, "movie", "tt1"))
        assertTrue(StreamMergeUtils.supportsStreamResource(addon, "movie", "kitsu:1"))
    }

    @Test
    fun `supportsStreamResource is false when the type does not match`() {
        val addon = streamAddon(resourceTypes = listOf("series"))
        assertFalse(StreamMergeUtils.supportsStreamResource(addon, "movie", "tt1"))
    }

    @Test
    fun `deriveInlineMetaId drops trailing season and episode from an imdb id`() {
        assertEquals("tt1234567", StreamMergeUtils.deriveInlineMetaId("tt1234567:1:5"))
    }

    @Test
    fun `deriveInlineMetaId keeps the prefix segment for a kitsu id`() {
        assertEquals("kitsu:12345", StreamMergeUtils.deriveInlineMetaId("kitsu:12345:2"))
    }

    @Test
    fun `deriveInlineMetaId keeps the prefix segment for a mal id with season and episode`() {
        assertEquals("mal:63375", StreamMergeUtils.deriveInlineMetaId("mal:63375:1:5"))
    }

    @Test
    fun `deriveInlineMetaId returns the id unchanged when it has no trailing numeric segments`() {
        assertEquals("tt1234567", StreamMergeUtils.deriveInlineMetaId("tt1234567"))
    }
}
