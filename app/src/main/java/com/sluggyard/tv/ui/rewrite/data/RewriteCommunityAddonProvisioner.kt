package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.addonprotocol.AddonManifestPolicy
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.aggregation.AddonFanoutResult
import com.sluggyard.tv.core.aggregation.AddonFanoutTask
import com.sluggyard.tv.core.aggregation.boundedAddonFanout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CommunityProvisioningReport(
    val installedCount: Int,
    val unavailableCount: Int,
)

/**
 * First-run backend provisioning for the single SlugYard Community integration.
 *
 * There is intentionally no URL parameter: callers cannot turn this into a generic addon
 * installer. Each manifest is allowlisted before it is fetched and persisted.
 */
class RewriteCommunityAddonProvisioner(
    private val gateway: StremioAddonGateway,
    private val registry: RewriteAddonRegistry,
) {
    private val provisionMutex = Mutex()

    suspend fun provision(
        configured: RewriteConfiguredAddonUrls? = null,
        debridConfigured: Boolean = configured != null,
    ): CommunityProvisioningReport {
        return provisionMutex.withLock {
            provisionLocked(configured, debridConfigured)
        }
    }

    /** Removes curated community manifests from the rewrite registry (Install can restore them). */
    suspend fun uninstall(): CommunityProvisioningReport {
        return provisionMutex.withLock {
            registry.clearCurated()
            CommunityProvisioningReport(installedCount = 0, unavailableCount = 0)
        }
    }

    private suspend fun provisionLocked(
        configured: RewriteConfiguredAddonUrls?,
        debridConfigured: Boolean,
    ): CommunityProvisioningReport {
        val registryUrls = SlugYardCommunitySourcePolicy.provisionManifestUrls(debridConfigured)
        val requests = registryUrls.map { registryUrl ->
            val requestUrl = when {
                registryUrl.contains("torrentio", ignoreCase = true) -> configured?.torrentioManifestUrl
                registryUrl.contains("mediafusion", ignoreCase = true) -> configured?.mediaFusionManifestUrl
                else -> null
            } ?: registryUrl
            ManifestRequest(registryUrl, requestUrl)
        }
        val curated = buildList {
            boundedAddonFanout(
                tasks = requests.mapIndexed { index, request ->
                    AddonFanoutTask(index.toString()) {
                        when (val response = gateway.fetchManifest(request.requestUrl)) {
                            is AddonTransportResult.Success -> when (val validation = AddonManifestPolicy.validate(request.requestUrl, response.value)) {
                                is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Accepted ->
                                    ManagedAddon(
                                        manifestUrl = request.registryUrl,
                                        configuredManifestUrl = request.requestUrl.takeUnless { it == request.registryUrl },
                                        manifest = validation.manifest,
                                    )
                                is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Rejected -> null
                            }
                            else -> null
                        }
                    }
                },
                maxConcurrent = requests.size.coerceAtMost(4).coerceAtLeast(1),
            ).collect { result ->
                if (result is AddonFanoutResult.Success) {
                    result.value?.let(::add)
                }
            }
        }
        registry.ensureCurated(
            addons = curated,
            retainManifestUrls = registryUrls.toSet(),
        )
        return CommunityProvisioningReport(
            installedCount = curated.size,
            unavailableCount = requests.size - curated.size,
        )
    }

    private data class ManifestRequest(
        val registryUrl: String,
        val requestUrl: String,
    )
}
