package com.sluggyard.tv.ui.rewrite.home

import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioMetadataDecoder

/**
 * Non-blocking metadata upgrade for the Home hero.
 *
 * Catalog data is always sufficient to render a usable base hero. This source is intentionally
 * best-effort: an unavailable origin, unsupported meta endpoint, or malformed response simply
 * returns null, allowing the base hero to remain on screen unchanged.
 */
class RewriteHeroEnrichmentDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
) {
    suspend fun enrich(hero: RewriteHero): RewriteHeroEnrichment? {
        val addon = registrySnapshot().enabledAddons.firstOrNull { it.manifest.id == hero.addonId }
            ?: return null
        if (AddonResource.META !in addon.manifest.resources) return null

        val response = gateway.fetchMeta(
            manifestUrl = addon.configuredManifestUrl ?: addon.manifestUrl,
            type = hero.contentType,
            id = hero.id,
        )
        val metadata = (response as? AddonTransportResult.Success)
            ?.value
            ?.let(StremioMetadataDecoder::decode)
            ?.let { it as? AddonTransportResult.Success }
            ?.value
            ?: return null

        val descriptorLabel = buildList {
            metadata.releaseInfo?.takeIf(String::isNotBlank)?.let(::add)
            metadata.genres.take(2).filter(String::isNotBlank).joinToString(" · ")
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }.joinToString("  ·  ").takeIf(String::isNotBlank)

        return RewriteHeroEnrichment(
            backdropUrl = metadata.backgroundUrl?.takeIf(String::isNotBlank),
            summary = metadata.description?.takeIf(String::isNotBlank),
            descriptorTag = descriptorLabel,
        ).takeIf { it.backdropUrl != null || it.summary != null || it.descriptorTag != null }
    }
}

data class RewriteHeroEnrichment(
    val backdropUrl: String? = null,
    val summary: String? = null,
    val descriptorTag: String? = null,
)

fun RewriteHomeState.withHeroEnrichment(enrichment: RewriteHeroEnrichment?): RewriteHomeState {
    val baseHero = hero ?: return this
    val upgrade = enrichment ?: return this
    return copy(
        hero = baseHero.copy(
            backdropUrl = upgrade.backdropUrl ?: baseHero.backdropUrl,
            summary = upgrade.summary ?: baseHero.summary,
            descriptorTag = upgrade.descriptorTag ?: baseHero.descriptorTag,
        ),
    )
}
