package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.addonprotocol.AddonManifestPolicy
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.PlayFlixCommunitySourcePolicy
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
 * First-run backend provisioning for the single PlayFlix Community integration.
 *
 * There is intentionally no URL parameter: callers cannot turn this into a generic addon
 * installer. Each manifest is allowlisted before it is fetched and persisted.
 */
class RewriteCommunityAddonProvisioner(
    private val gateway: StremioAddonGateway,
    private val registry: RewriteAddonRegistry,
) {
    suspend fun provision(): CommunityProvisioningReport {
        val urls = PlayFlixCommunitySourcePolicy.bootstrapManifestUrls
        val curated = buildList {
            boundedAddonFanout(
                tasks = urls.mapIndexed { index, manifestUrl ->
                    AddonFanoutTask(index.toString()) {
                        when (val response = gateway.fetchManifest(manifestUrl)) {
                            is AddonTransportResult.Success -> when (val validation = AddonManifestPolicy.validate(manifestUrl, response.value)) {
                                is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Accepted ->
                                    ManagedAddon(validation.manifestUrl, validation.manifest)
                                is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Rejected -> null
                            }
                            else -> null
                        }
                    }
                },
                maxConcurrent = urls.size.coerceAtMost(4).coerceAtLeast(1),
            ).collect { result ->
                if (result is AddonFanoutResult.Success) {
                    result.value?.let(::add)
                }
            }
        }
        registry.ensureCurated(curated)
        return CommunityProvisioningReport(
            installedCount = curated.size,
            unavailableCount = PlayFlixCommunitySourcePolicy.bootstrapManifestUrls.size - curated.size,
        )
    }
}
