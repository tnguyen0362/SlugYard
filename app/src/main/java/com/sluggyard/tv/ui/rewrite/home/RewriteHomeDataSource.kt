package com.sluggyard.tv.ui.rewrite.home

import android.os.SystemClock
import com.sluggyard.tv.core.addonprotocol.AddonManifestPolicy
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.addonprotocol.StremioResponseDecoder
import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import com.sluggyard.tv.core.aggregation.ReleaseVisibilityPolicy
import com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettings
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics

/**
 * Bridges the clean addon protocol models to the rewritten Home controller.
 *
 * Registry persistence is injected as a snapshot provider so the eventual DataStore implementation
 * is independent from both Compose and network transport. A per-catalog failure becomes a thrown
 * task failure, which [loadRewriteHomeCatalogs] renders in that row only.
 */
class RewriteHomeDataSource(
    private val registrySnapshot: suspend () -> AddonRegistryState,
    private val gateway: StremioAddonGateway,
    private val settingsSnapshot: suspend () -> RewriteHomeSettings = { RewriteHomeSettings() },
    private val tmdbCatalogRequests: suspend () -> List<RewriteCatalogRequest> = { emptyList() },
) {
    suspend fun catalogRequests(excludedCatalogIds: Set<String> = emptySet()): List<RewriteCatalogRequest> {
        val settings = settingsSnapshot()
        val addonRequests = registrySnapshot().enabledAddons.flatMap { addon ->
            AddonManifestPolicy.homeEligibleCatalogs(addon.manifest, excludedCatalogIds)
                .filterNot { catalog -> settings.excludes(addon.manifest.id, catalog.id) }
                .map { catalog ->
                    RewriteCatalogRequest(
                        key = HomeCatalogKey(addon.manifest.id, catalog.id),
                        title = catalog.displayName,
                        load = {
                            val startedAt = SystemClock.elapsedRealtime()
                            ExperimentalDiagnostics.event(
                                "home",
                                "catalog_load_started",
                                mapOf(
                                    "addon" to addon.manifest.id,
                                    "catalog" to catalog.id,
                                    "type" to catalog.type,
                                ),
                            )
                            when (val result = gateway.fetchCatalog(
                                manifestUrl = addon.configuredManifestUrl ?: addon.manifestUrl,
                                type = catalog.type,
                                catalogId = catalog.id,
                            )) {
                                is AddonTransportResult.Success -> {
                                    val posters = StremioResponseDecoder.catalogItems(result.value)
                                        .filter { item -> ReleaseVisibilityPolicy.isVisible(item.releaseInfo, settings.hideUnreleased) }
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
                                    ExperimentalDiagnostics.event(
                                        "home",
                                        "catalog_load_completed",
                                        mapOf(
                                            "addon" to addon.manifest.id,
                                            "catalog" to catalog.id,
                                            "resultCount" to posters.size,
                                            "durationMs" to SystemClock.elapsedRealtime() - startedAt,
                                        ),
                                    )
                                    posters
                                }
                                else -> {
                                    ExperimentalDiagnostics.event(
                                        "home",
                                        "catalog_load_failed",
                                        mapOf(
                                            "addon" to addon.manifest.id,
                                            "catalog" to catalog.id,
                                            "result" to result.describe(),
                                            "durationMs" to SystemClock.elapsedRealtime() - startedAt,
                                        ),
                                    )
                                    throw AddonCatalogLoadException(addon, catalog.id, result)
                                }
                            }
                        },
                    )
                }
        }
        val tmdbRequests = runCatching { tmdbCatalogRequests() }.getOrDefault(emptyList())
        return mergeHomeCatalogRequests(addonRequests, tmdbRequests)
    }
}

class AddonCatalogLoadException(
    addon: ManagedAddon,
    catalogId: String,
    result: AddonTransportResult<*>,
) : RuntimeException("${addon.manifest.name}/$catalogId failed: ${result.describe()}")

private fun AddonTransportResult<*>.describe(): String = when (this) {
    is AddonTransportResult.HttpFailure -> "HTTP $statusCode"
    is AddonTransportResult.NetworkFailure -> "network failure"
    is AddonTransportResult.MalformedResponse -> message
    is AddonTransportResult.Success -> "unexpected success"
}
