package com.sluggyard.tv.data.repository

import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.AddonResource
import com.sluggyard.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaCandidateSelectorTest {

    private fun metaAddon(
        id: String,
        idPrefixes: List<String> = emptyList(),
        resourceName: String = "meta",
        resourceIdPrefixes: List<String>? = null
    ): Addon = Addon(
        id = id,
        name = id,
        displayName = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = emptyList(),
        types = listOf(ContentType.SERIES),
        resources = listOf(
            AddonResource(name = resourceName, types = listOf("series"), idPrefixes = resourceIdPrefixes)
        ),
        idPrefixes = idPrefixes
    )

    @Test
    fun `inferCanonicalType returns a known type unchanged`() {
        assertEquals("movie", MetaCandidateSelector.inferCanonicalType("movie", "tt1"))
    }

    @Test
    fun `inferCanonicalType infers the type from a series-shaped id`() {
        assertEquals("series", MetaCandidateSelector.inferCanonicalType("unknown", "tt1:series:1"))
    }

    @Test
    fun `inferCanonicalType falls back to the given type when nothing matches`() {
        assertEquals("weird", MetaCandidateSelector.inferCanonicalType("weird", "tt1"))
    }

    @Test
    fun `supportsMetaId is true when the addon declares no idPrefixes at all`() {
        val addon = metaAddon("no-prefix")
        assertEquals(true, MetaCandidateSelector.supportsMetaId(addon, "kitsu:123"))
    }

    @Test
    fun `supportsMetaId respects resource-level idPrefixes over addon-level`() {
        val addon = metaAddon("resource-scoped", idPrefixes = listOf("tt"), resourceIdPrefixes = listOf("kitsu:"))
        assertEquals(false, MetaCandidateSelector.supportsMetaId(addon, "tt1"))
        assertEquals(true, MetaCandidateSelector.supportsMetaId(addon, "kitsu:1"))
    }

    @Test
    fun `selectPrioritizedCandidates prefers the first addon matching both type and id prefix`() {
        val nonMeta = metaAddon("non-meta", resourceName = "catalog")
        val cinemeta = metaAddon("cinemeta", idPrefixes = listOf("tt"))

        val candidates = MetaCandidateSelector.selectPrioritizedCandidates(
            addons = listOf(nonMeta, cinemeta),
            requestedType = "series",
            inferredType = "series",
            id = "tt1"
        )

        assertEquals(cinemeta to "series", candidates.first())
    }

    @Test
    fun `selectPrioritizedCandidates falls back to idPrefix-less addons when no id match exists`() {
        val kitsuOnly = metaAddon("kitsu", idPrefixes = listOf("kitsu:"))
        val agnostic = metaAddon("agnostic")

        val candidates = MetaCandidateSelector.selectPrioritizedCandidates(
            addons = listOf(kitsuOnly, agnostic),
            requestedType = "series",
            inferredType = "series",
            id = "tt1"
        )

        assertEquals(listOf(agnostic to "series"), candidates)
    }

    @Test
    fun `selectPrimaryCandidate returns the first addon supporting the requested type`() {
        val nonMeta = metaAddon("non-meta", resourceName = "catalog")
        val primary = metaAddon("primary")

        val candidate = MetaCandidateSelector.selectPrimaryCandidate(
            addons = listOf(nonMeta, primary),
            requestedType = "series",
            inferredType = "series"
        )

        assertEquals(primary to "series", candidate)
    }

    @Test
    fun `selectPrimaryCandidate returns null when no addon exposes a meta resource`() {
        val nonMeta = metaAddon("non-meta", resourceName = "catalog")

        val candidate = MetaCandidateSelector.selectPrimaryCandidate(
            addons = listOf(nonMeta),
            requestedType = "series",
            inferredType = "series"
        )

        assertNull(candidate)
    }
}
