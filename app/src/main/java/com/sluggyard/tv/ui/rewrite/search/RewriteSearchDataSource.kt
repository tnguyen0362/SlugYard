package com.sluggyard.tv.ui.rewrite.search

import com.sluggyard.tv.core.addonprotocol.AddonManifestPolicy
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder
import com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster
import com.sluggyard.tv.ui.rewrite.rewritePosterDedupeKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Fan-out text search across every enabled addon's search-eligible catalogs.
 *
 * Mirrors [com.sluggyard.tv.ui.rewrite.home.RewriteHomeDataSource]'s addon-protocol bridging, but
 * targets catalogs that declare a "search" extra (Stremio's `extra: [{name: "search"}]` contract)
 * instead of home-eligible (no-required-extra) catalogs. A failing addon or catalog is dropped
 * from the merged result rather than failing the whole search, since a partial result set is
 * always more useful to a user typing a live query than an all-or-nothing failure.
 */
class RewriteSearchDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
) {
    suspend fun search(query: String): List<RewriteHomePoster> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        return coroutineScope {
            registrySnapshot().enabledAddons.flatMap { addon ->
                AddonManifestPolicy.searchEligibleCatalogs(addon.manifest).map { catalog ->
                    async {
                        runCatching {
                            when (
                                val result = gateway.fetchCatalog(
                                    manifestUrl = addon.configuredManifestUrl ?: addon.manifestUrl,
                                    type = catalog.type,
                                    catalogId = catalog.id,
                                    extras = mapOf("search" to trimmed),
                                )
                            ) {
                                is AddonTransportResult.Success -> StremioResponseDecoder.catalogItems(result.value)
                                    .map { item ->
                                        RewriteHomePoster(
                                            id = item.id,
                                            title = item.title,
                                            imageUrl = item.posterUrl,
                                            addonId = addon.manifest.id,
                                            contentType = item.type,
                                            summary = item.description?.takeIf { it.isNotBlank() },
                                            backdropUrl = item.backgroundUrl?.takeIf { it.isNotBlank() },
                                        )
                                    }
                                else -> emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten().distinctBy { rewritePosterDedupeKey(it.contentType, it.id) }
        }
    }
}
