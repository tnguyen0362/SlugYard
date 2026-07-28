package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.addonprotocol.AddonBehaviorHints
import com.sluggyard.tv.core.addonprotocol.AddonCatalogDeclaration
import com.sluggyard.tv.core.addonprotocol.AddonCatalogExtra
import com.sluggyard.tv.core.addonprotocol.AddonManifestContract
import com.sluggyard.tv.core.addonprotocol.AddonRegistryAction
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonResource
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.addonprotocol.reduceAddonRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val rewriteAddonRegistryKey = stringPreferencesKey("rewrite_addon_registry_v1")

interface RewriteAddonRegistry {
    val state: Flow<AddonRegistryState>
    suspend fun dispatch(action: AddonRegistryAction)
    /** Adds backend-curated manifests without exposing a user mutation surface. */
    suspend fun ensureCurated(addons: List<ManagedAddon>)
}

/**
 * The rewrite-owned persistence boundary for addon configuration.
 *
 * DataStore updates decode, reduce, and encode inside one edit transaction, so simultaneous native
 * and local-web mutations cannot independently overwrite each other with stale snapshots.
 */
class RewriteAddonRegistryStore(
    private val dataStore: DataStore<Preferences>,
) : RewriteAddonRegistry {
    private val runtimeConfiguredUrls = MutableStateFlow<Map<String, String>>(emptyMap())

    override val state: Flow<AddonRegistryState> = combine(dataStore.data, runtimeConfiguredUrls) { preferences, runtime ->
        val persisted = preferences[rewriteAddonRegistryKey]
            ?.let(RewriteAddonRegistryCodec::decode)
            ?.let(::sanitizeCuratedRegistry)
            ?: AddonRegistryState()
        AddonRegistryState(persisted.addons.map { addon ->
            addon.copy(configuredManifestUrl = runtime[addon.manifestUrl])
        })
    }

    override suspend fun dispatch(action: AddonRegistryAction) {
        dataStore.edit { preferences ->
            val current = preferences[rewriteAddonRegistryKey]
                ?.let(RewriteAddonRegistryCodec::decode)
                ?.let(::sanitizeCuratedRegistry)
                ?: AddonRegistryState()
            preferences[rewriteAddonRegistryKey] = RewriteAddonRegistryCodec.encode(
                sanitizeCuratedRegistry(reduceAddonRegistry(current, action)),
            )
        }
    }

    override suspend fun ensureCurated(addons: List<ManagedAddon>) {
        runtimeConfiguredUrls.value = addons
            .filter { it.manifestUrl in SlugYardCommunitySourcePolicy.bootstrapManifestUrls }
            .mapNotNull { addon -> addon.configuredManifestUrl?.let { addon.manifestUrl to it } }
            .toMap()
        if (addons.isEmpty()) return
        dataStore.edit { preferences ->
            val current = preferences[rewriteAddonRegistryKey]
                ?.let(RewriteAddonRegistryCodec::decode)
                ?.let(::sanitizeCuratedRegistry)
                ?: AddonRegistryState()
            val curated = addons.filter { addon ->
                addon.manifestUrl in SlugYardCommunitySourcePolicy.bootstrapManifestUrls
            }
            val byUrl = curated.associateBy(ManagedAddon::manifestUrl)
            val updated = current.addons.mapNotNull { existing ->
                byUrl[existing.manifestUrl]?.copy(enabled = existing.enabled) ?: existing.takeIf {
                    it.manifestUrl in SlugYardCommunitySourcePolicy.bootstrapManifestUrls
                }
            }
            val additions = curated.filter { candidate -> current.addons.none { it.manifestUrl == candidate.manifestUrl } }
            val next = sanitizeCuratedRegistry(AddonRegistryState(updated + additions))
            if (next != current) {
                preferences[rewriteAddonRegistryKey] = RewriteAddonRegistryCodec.encode(
                    next,
                )
            }
        }
    }
}

/** Enforces the locked-down backend even if preferences were edited or left by an older build. */
internal fun sanitizeCuratedRegistry(state: AddonRegistryState): AddonRegistryState =
    AddonRegistryState(
        state.addons
            .filter { it.manifestUrl in SlugYardCommunitySourcePolicy.bootstrapManifestUrls }
            .map { addon ->
                addon.copy(
                    // Configured manifest URLs may contain provider tokens. They are runtime-only;
                    // credentials remain in the encrypted provider store and are rehydrated by
                    // community provisioning on app start.
                    configuredManifestUrl = null,
                )
            },
    )

object RewriteAddonRegistryCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: AddonRegistryState): String = json.encodeToString(
        StoredRegistry(
            addons = state.addons.map { addon ->
                StoredAddon(
                    manifestUrl = addon.manifestUrl,
                    configuredManifestUrl = null,
                    enabled = addon.enabled,
                    manifest = addon.manifest.toStored(),
                )
            },
        ),
    )

    fun decode(raw: String): AddonRegistryState = decodeOrNull(raw) ?: AddonRegistryState()
    fun decodeOrNull(raw: String): AddonRegistryState? = runCatching {
        json.decodeFromString<StoredRegistry>(raw).toDomain()
    }.getOrNull()

    private fun AddonManifestContract.toStored() = StoredManifest(
        id = id,
        name = name,
        version = version,
        description = description,
        logoUrl = logoUrl,
        backgroundUrl = backgroundUrl,
        resources = resources.map(AddonResource::name),
        types = types.toList(),
        catalogs = catalogs.map { catalog ->
            StoredCatalog(catalog.id, catalog.type, catalog.displayName, catalog.extras.map { StoredExtra(it.name, it.required) })
        },
        configurable = behaviorHints.configurable,
        adultContent = behaviorHints.adultContent,
    )

    private fun StoredRegistry.toDomain() = AddonRegistryState(addons.map { stored ->
        ManagedAddon(
            manifestUrl = stored.manifestUrl,
            configuredManifestUrl = stored.configuredManifestUrl,
            enabled = stored.enabled,
            manifest = AddonManifestContract(
                id = stored.manifest.id,
                name = stored.manifest.name,
                version = stored.manifest.version,
                description = stored.manifest.description,
                logoUrl = stored.manifest.logoUrl,
                backgroundUrl = stored.manifest.backgroundUrl,
                resources = stored.manifest.resources.mapNotNull { raw ->
                    AddonResource.entries.firstOrNull { it.name == raw }
                }.toSet(),
                types = stored.manifest.types.toSet(),
                catalogs = stored.manifest.catalogs.map { catalog ->
                    AddonCatalogDeclaration(
                        id = catalog.id,
                        type = catalog.type,
                        displayName = catalog.displayName,
                        extras = catalog.extras.map { AddonCatalogExtra(it.name, it.required) },
                    )
                },
                behaviorHints = AddonBehaviorHints(stored.manifest.configurable, stored.manifest.adultContent),
            ),
        )
    })
}

@Serializable
private data class StoredRegistry(val addons: List<StoredAddon> = emptyList())

@Serializable
private data class StoredAddon(
    val manifestUrl: String,
    val configuredManifestUrl: String? = null,
    val enabled: Boolean = true,
    val manifest: StoredManifest,
)

@Serializable
private data class StoredManifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val logoUrl: String? = null,
    val backgroundUrl: String? = null,
    val resources: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<StoredCatalog> = emptyList(),
    val configurable: Boolean = false,
    val adultContent: Boolean = false,
)

@Serializable
private data class StoredCatalog(
    val id: String,
    val type: String,
    val displayName: String,
    val extras: List<StoredExtra> = emptyList(),
)

@Serializable
private data class StoredExtra(val name: String, val required: Boolean = false)
