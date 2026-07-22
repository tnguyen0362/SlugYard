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
    suspend fun provision(configured: RewriteConfiguredAddonUrls? = null): CommunityProvisioningReport {
        val requests = SlugYardCommunitySourcePolicy.bootstrapManifestUrls.map { registryUrl ->
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
        registry.ensureCurated(curated)
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
