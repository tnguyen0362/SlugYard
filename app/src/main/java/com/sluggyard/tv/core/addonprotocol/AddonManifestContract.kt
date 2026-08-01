package com.sluggyard.tv.core.addonprotocol

import java.net.URI

/** Resources understood by the SlugYard Stremio-compatible addon client. */
enum class AddonResource { CATALOG, META, STREAM, SUBTITLES }

data class AddonCatalogExtra(
    val name: String,
    val required: Boolean = false,
)

data class AddonCatalogDeclaration(
    val id: String,
    val type: String,
    val displayName: String,
    val extras: List<AddonCatalogExtra> = emptyList(),
)

data class AddonBehaviorHints(
    val configurable: Boolean = false,
    val adultContent: Boolean = false,
)

/** Parsed, transport-neutral representation of an addon manifest. */
data class AddonManifestContract(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val logoUrl: String? = null,
    val backgroundUrl: String? = null,
    val resources: Set<AddonResource> = emptySet(),
    val types: Set<String> = emptySet(),
    val catalogs: List<AddonCatalogDeclaration> = emptyList(),
    val behaviorHints: AddonBehaviorHints = AddonBehaviorHints(),
)

sealed interface ManifestValidation {
    data class Accepted(
        val manifestUrl: String,
        val manifest: AddonManifestContract,
    ) : ManifestValidation

    data class Rejected(val reason: String) : ManifestValidation
}

/**
 * Validates the observable acceptance rules before an addon is persisted.
 *
 * Fetching/parsing belongs to the transport layer; this policy deliberately has no Android,
 * network, or storage dependency, making native and browser configuration surfaces consistent.
 */
object AddonManifestPolicy {
    fun validate(manifestUrl: String, manifest: AddonManifestContract?): ManifestValidation {
        val uri = runCatching { URI(manifestUrl.trim()) }.getOrNull()
            ?: return ManifestValidation.Rejected("Manifest URL is invalid")
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return ManifestValidation.Rejected("Manifest URL must use HTTP or HTTPS")
        }
        val resolvedManifest = manifest
            ?: return ManifestValidation.Rejected("Manifest could not be loaded")
        if (resolvedManifest.id.isBlank()) {
            return ManifestValidation.Rejected("Manifest is missing an id")
        }
        if (resolvedManifest.name.isBlank()) {
            return ManifestValidation.Rejected("Manifest is missing a name")
        }
        return ManifestValidation.Accepted(uri.toString(), resolvedManifest)
    }

    /** A catalog is Home-eligible only when it can be listed without required user input. */
    fun homeEligibleCatalogs(
        manifest: AddonManifestContract,
        excludedCatalogIds: Set<String>,
    ): List<AddonCatalogDeclaration> {
        if (AddonResource.CATALOG !in manifest.resources) return emptyList()
        return manifest.catalogs.filter { catalog ->
            catalog.id.isNotBlank() &&
                catalog.id !in excludedCatalogIds &&
                catalog.extras.none(AddonCatalogExtra::required)
        }
    }

    /** A catalog can serve a text search if it declares a "search" extra parameter. */
    fun searchEligibleCatalogs(manifest: AddonManifestContract): List<AddonCatalogDeclaration> {
        if (AddonResource.CATALOG !in manifest.resources) return emptyList()
        return manifest.catalogs.filter { catalog ->
            catalog.id.isNotBlank() && catalog.extras.any { it.name == "search" }
        }
    }
}
