package com.sluggyard.tv.ui.app.data

import android.util.Log
import com.sluggyard.tv.core.addonprotocol.AddonManifestPolicy
import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.addonprotocol.StremioAddonGateway
import com.sluggyard.tv.core.aggregation.AddonFanoutResult
import com.sluggyard.tv.core.aggregation.AddonFanoutTask
import com.sluggyard.tv.core.aggregation.boundedAddonFanout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

data class CommunityProvisioningReport(
    val installedCount: Int,
    val unavailableCount: Int,
    val unavailableReasons: List<String> = emptyList(),
)

private const val COMMUNITY_PROVISION_TAG = "AddonProvision"

/**
 * First-run backend provisioning for the single SlugYard Community integration.
 *
 * There is intentionally no URL parameter: callers cannot turn this into a generic addon
 * installer. Each manifest is allowlisted before it is fetched and persisted.
 */
class CommunityAddonProvisioner(
    private val gateway: StremioAddonGateway,
    private val registry: AddonRegistry,
) {
    private val provisionMutex = Mutex()

    suspend fun provision(
        configured: ConfiguredAddonUrls? = null,
        debridConfigured: Boolean = configured != null,
    ): CommunityProvisioningReport {
        return provisionMutex.withLock {
            registry.ensurePlayFlixNotDefaultInstalled()
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

    /**
     * Installs a single allowlisted community manifest.
     * Entries in [SlugYardCommunitySourcePolicy.optionalCommunityManifestUrls] (if any)
     * are preserved across later provision passes.
     */
    suspend fun installAllowlisted(
        registryUrl: String,
        configuredRequestUrl: String? = null,
    ): ManagedAddon {
        return provisionMutex.withLock {
            val normalized = registryUrl.trim()
            require(normalized in (SlugYardCommunitySourcePolicy.bootstrapManifestUrls.toSet())) {
                "Addon URL is not in the curated allowlist"
            }
            if (SlugYardCommunitySourcePolicy.isOptionalCommunityManifest(normalized)) {
                registry.ensurePlayFlixNotDefaultInstalled()
            }
            val requestUrl = configuredRequestUrl?.takeIf { it.isNotBlank() } ?: normalized
            when (val response = gateway.fetchManifest(requestUrl)) {
                is AddonTransportResult.Success -> when (
                    val validation = AddonManifestPolicy.validate(requestUrl, response.value)
                ) {
                    is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Accepted -> {
                        val addon = ManagedAddon(
                            manifestUrl = normalized,
                            configuredManifestUrl = requestUrl.takeUnless { it == normalized },
                            manifest = displayAwareManifest(normalized, validation.manifest),
                        )
                        registry.dispatch(
                            com.sluggyard.tv.core.addonprotocol.AddonRegistryAction.Add(addon),
                        )
                        registry.bindConfiguredManifestUrl(
                            manifestUrl = normalized,
                            configuredManifestUrl = addon.configuredManifestUrl,
                        )
                        addon
                    }
                    is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Rejected ->
                        error(validation.reason)
                }
                is AddonTransportResult.HttpFailure ->
                    error("Could not load addon (HTTP ${response.statusCode})")
                is AddonTransportResult.NetworkFailure ->
                    error("Could not load addon (network failure)")
                is AddonTransportResult.MalformedResponse ->
                    error("Could not load addon (malformed manifest)")
            }
        }
    }

    suspend fun uninstallByManifestUrl(manifestUrl: String) {
        provisionMutex.withLock {
            val current = registry.state.first()
            val match = current.addons.firstOrNull {
                it.manifestUrl.equals(manifestUrl.trim(), ignoreCase = true) ||
                    (
                        SlugYardCommunitySourcePolicy.isPlayFlixManifest(it.manifestUrl) &&
                            SlugYardCommunitySourcePolicy.isPlayFlixManifest(manifestUrl)
                        )
            } ?: return@withLock
            registry.dispatch(
                com.sluggyard.tv.core.addonprotocol.AddonRegistryAction.Remove(match.manifest.id),
            )
            registry.bindConfiguredManifestUrl(match.manifestUrl, null)
        }
    }

    /** Use upstream branding (MediaFusion, Torrentio, …) — PlayFlix is the app name only. */
    private fun displayAwareManifest(
        @Suppress("UNUSED_PARAMETER") registryUrl: String,
        manifest: com.sluggyard.tv.core.addonprotocol.AddonManifestContract,
    ) = manifest

    private suspend fun provisionLocked(
        configured: ConfiguredAddonUrls?,
        debridConfigured: Boolean,
    ): CommunityProvisioningReport {
        // Never request a keyless content-source manifest just because a stale provider selection
        // exists. Content sources are eligible only when their generated URLs are available.
        val hasConfiguredContentSources = debridConfigured && configured != null
        val registryUrls = SlugYardCommunitySourcePolicy.provisionManifestUrls(hasConfiguredContentSources)
        val unavailableReasons = mutableListOf<String>()
        val requests = registryUrls.mapNotNull { registryUrl ->
            val configuredUrl = when {
                registryUrl.contains("torrentio", ignoreCase = true) -> configured?.torrentioManifestUrl
                registryUrl.contains("mediafusion", ignoreCase = true) -> configured?.mediaFusionManifestUrl
                registryUrl.contains("comet", ignoreCase = true) -> configured?.cometManifestUrl
                registryUrl.contains("meteor", ignoreCase = true) -> configured?.meteorManifestUrl
                SlugYardCommunitySourcePolicy.isAioStreamsManifest(registryUrl) ->
                    configured?.aioStreamsManifestUrl
                else -> null
            }
            // AIOStreams public stub has empty resources; only install when a UUID manifest exists.
            if (SlugYardCommunitySourcePolicy.isAioStreamsManifest(registryUrl) && configuredUrl == null) {
                unavailableReasons += "$registryUrl: AIOStreams configuration unavailable"
                Log.w(
                    COMMUNITY_PROVISION_TAG,
                    "unavailable registry=$registryUrl reason=AIOStreams configuration unavailable",
                )
                return@mapNotNull null
            }
            ManifestRequest(registryUrl, configuredUrl ?: registryUrl)
        }
        val curated = buildList {
            boundedAddonFanout(
                tasks = requests.mapIndexed { index, request ->
                    AddonFanoutTask(index.toString()) {
                        when (val response = gateway.fetchManifest(request.requestUrl)) {
                            is AddonTransportResult.Success -> when (val validation = AddonManifestPolicy.validate(request.requestUrl, response.value)) {
                                is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Accepted ->
                                    ProvisionedAddon(
                                        registryUrl = request.registryUrl,
                                        requestHost = request.requestHost(),
                                        addon = ManagedAddon(
                                            manifestUrl = request.registryUrl,
                                            configuredManifestUrl = request.requestUrl.takeUnless { it == request.registryUrl },
                                            manifest = validation.manifest,
                                        ),
                                    )
                                is com.sluggyard.tv.core.addonprotocol.ManifestValidation.Rejected ->
                                    ProvisionedAddon(
                                        registryUrl = request.registryUrl,
                                        requestHost = request.requestHost(),
                                        reason = validation.reason,
                                    )
                            }
                            is AddonTransportResult.HttpFailure -> ProvisionedAddon(
                                registryUrl = request.registryUrl,
                                requestHost = request.requestHost(),
                                reason = "HTTP ${response.statusCode}",
                            )
                            is AddonTransportResult.NetworkFailure -> ProvisionedAddon(
                                registryUrl = request.registryUrl,
                                requestHost = request.requestHost(),
                                reason = "network failure",
                            )
                            is AddonTransportResult.MalformedResponse -> ProvisionedAddon(
                                registryUrl = request.registryUrl,
                                requestHost = request.requestHost(),
                                reason = "malformed manifest",
                            )
                        }
                    }
                },
                maxConcurrent = requests.size.coerceAtMost(4).coerceAtLeast(1),
            ).collect { result ->
                if (result is AddonFanoutResult.Success) {
                    result.value.addon?.let(::add)
                    result.value.reason?.let { reason ->
                        unavailableReasons += "${result.value.registryUrl}: $reason"
                        Log.w(
                            COMMUNITY_PROVISION_TAG,
                            "unavailable registry=${result.value.registryUrl} " +
                                "requestHost=${result.value.requestHost} reason=$reason",
                        )
                    }
                } else if (result is AddonFanoutResult.Failure) {
                    val request = requests.getOrNull(result.key.toIntOrNull() ?: -1)
                    val registryUrl = request?.registryUrl ?: "manifest"
                    val reason = result.cause::class.simpleName ?: "provisioning failure"
                    unavailableReasons += "$registryUrl: $reason"
                    Log.w(
                        COMMUNITY_PROVISION_TAG,
                        "unavailable registry=$registryUrl " +
                            "requestHost=${request?.requestHost() ?: "unknown"} reason=$reason",
                    )
                }
            }
        }
        registry.ensureCurated(
            addons = curated,
            retainManifestUrls = registryUrls.toSet(),
        )
        return CommunityProvisioningReport(
            installedCount = curated.size,
            unavailableCount = registryUrls.size - curated.size,
            unavailableReasons = unavailableReasons.toList(),
        )
    }

    private data class ManifestRequest(
        val registryUrl: String,
        val requestUrl: String,
    ) {
        fun requestHost(): String = runCatching { URI(requestUrl).host }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "unknown"
    }

    private data class ProvisionedAddon(
        val registryUrl: String,
        val requestHost: String,
        val addon: ManagedAddon? = null,
        val reason: String? = null,
    )
}
