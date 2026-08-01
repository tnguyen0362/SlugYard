package com.sluggyard.tv.core.addonprotocol

data class ManagedAddon(
    val manifestUrl: String,
    val manifest: AddonManifestContract,
    val enabled: Boolean = true,
    /** The persisted, configured manifest location when the addon is configurable. */
    val configuredManifestUrl: String? = null,
)

data class AddonRegistryState(val addons: List<ManagedAddon> = emptyList()) {
    val enabledAddons: List<ManagedAddon>
        get() = addons.filter(ManagedAddon::enabled)
}

sealed interface AddonRegistryAction {
    data class Add(val addon: ManagedAddon) : AddonRegistryAction
    data class Remove(val addonId: String) : AddonRegistryAction
    data class SetEnabled(val addonId: String, val enabled: Boolean) : AddonRegistryAction
    /** Requested user order. Missing or stale identifiers are intentionally harmless. */
    data class Reorder(val addonIds: List<String>) : AddonRegistryAction
    data class SetConfiguredManifestUrl(val addonId: String, val configuredManifestUrl: String) : AddonRegistryAction
}

/**
 * One deterministic business-rule surface for native and web addon management.
 *
 * Manifest fetching and validation occur before [AddonRegistryAction.Add]. This reducer is
 * intentionally synchronous and immutable so persistence, browser HTTP handlers, and Compose
 * view models all converge on exactly the same resulting registry state.
 */
fun reduceAddonRegistry(
    state: AddonRegistryState,
    action: AddonRegistryAction,
): AddonRegistryState = when (action) {
    is AddonRegistryAction.Add -> {
        val withoutExisting = state.addons.filterNot { it.manifest.id == action.addon.manifest.id }
        AddonRegistryState(withoutExisting + action.addon)
    }
    is AddonRegistryAction.Remove -> AddonRegistryState(
        state.addons.filterNot { it.manifest.id == action.addonId },
    )
    is AddonRegistryAction.SetEnabled -> AddonRegistryState(
        state.addons.map { addon ->
            if (addon.manifest.id == action.addonId) addon.copy(enabled = action.enabled) else addon
        },
    )
    is AddonRegistryAction.Reorder -> {
        val byId = state.addons.associateBy { it.manifest.id }
        val order = LinkedHashSet<String>()
        action.addonIds.forEach { id -> if (id in byId) order += id }
        state.addons.forEach { order += it.manifest.id }
        AddonRegistryState(order.mapNotNull(byId::get))
    }
    is AddonRegistryAction.SetConfiguredManifestUrl -> AddonRegistryState(
        state.addons.map { addon ->
            if (addon.manifest.id == action.addonId) {
                addon.copy(configuredManifestUrl = action.configuredManifestUrl)
            } else {
                addon
            }
        },
    )
}
