package com.sluggyard.tv.ui.app.data

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

private val addonRegistryKey = stringPreferencesKey("app_addon_registry_v1")
private val playFlixDefaultOffKey = stringPreferencesKey("playflix_not_default_installed_v1")

interface AddonRegistry {
    val state: Flow<AddonRegistryState>
    suspend fun dispatch(action: AddonRegistryAction)
    /**
     * Adds backend-curated manifests without exposing a user mutation surface.
     *
     * [retainManifestUrls] is the allowlisted set that should remain after this pass.
     * Manifests outside that set are dropped (e.g. torrent sources when debrid is off).
     * Manifests inside the set that fail to fetch this run are kept if already installed.
     * Optional community addons (PlayFlix) are preserved when already installed and never
     * auto-added by this pass.
     */
    suspend fun ensureCurated(
        addons: List<ManagedAddon>,
        retainManifestUrls: Set<String> = SlugYardCommunitySourcePolicy.bootstrapManifestUrls.toSet(),
    )
    /** Removes all allowlisted community manifests (Install restores them via provisioning). */
    suspend fun clearCurated()
    /**
     * One-time strip of PlayFlix if an older build auto-provisioned it. After this marker is set,
     * only an explicit Community install keeps PlayFlix.
     */
    suspend fun ensurePlayFlixNotDefaultInstalled()
    /** Runtime-only configured manifest URL (may embed provider tokens). */
    suspend fun bindConfiguredManifestUrl(manifestUrl: String, configuredManifestUrl: String?)
}

/**
 * The rewrite-owned persistence boundary for addon configuration.
 *
 * DataStore updates decode, reduce, and encode inside one edit transaction, so simultaneous native
 * and local-web mutations cannot independently overwrite each other with stale snapshots.
 */
class AddonRegistryStore(
    private val dataStore: DataStore<Preferences>,
) : AddonRegistry {
    private val runtimeConfiguredUrls = MutableStateFlow<Map<String, String>>(emptyMap())

    override val state: Flow<AddonRegistryState> = combine(dataStore.data, runtimeConfiguredUrls) { preferences, runtime ->
        val persisted = preferences[addonRegistryKey]
            ?.let(AddonRegistryCodec::decode)
            ?.let(::sanitizeCuratedRegistry)
            ?: AddonRegistryState()
        AddonRegistryState(persisted.addons.map { addon ->
            addon.copy(configuredManifestUrl = runtime[addon.manifestUrl])
        })
    }

    override suspend fun dispatch(action: AddonRegistryAction) {
        dataStore.edit { preferences ->
            val current = preferences[addonRegistryKey]
                ?.let(AddonRegistryCodec::decode)
                ?.let(::sanitizeCuratedRegistry)
                ?: AddonRegistryState()
            preferences[addonRegistryKey] = AddonRegistryCodec.encode(
                sanitizeCuratedRegistry(reduceAddonRegistry(current, action)),
            )
        }
    }

    override suspend fun ensureCurated(
        addons: List<ManagedAddon>,
        retainManifestUrls: Set<String>,
    ) {
        val retain = retainManifestUrls.intersect(SlugYardCommunitySourcePolicy.bootstrapManifestUrls.toSet())
            .filterNot(SlugYardCommunitySourcePolicy::isOptionalCommunityManifest)
            .toSet()
        val refreshedConfigured = addons
            .filter { it.manifestUrl in retain }
            .mapNotNull { addon -> addon.configuredManifestUrl?.let { addon.manifestUrl to it } }
            .toMap()
        // Keep prior configured URLs for retained manifests that failed to refresh this pass.
        // Also keep PlayFlix configured URL when the optional addon stays installed.
        val preservedOptionalConfigured = runtimeConfiguredUrls.value
            .filterKeys(SlugYardCommunitySourcePolicy::isOptionalCommunityManifest)
        runtimeConfiguredUrls.value = runtimeConfiguredUrls.value
            .filterKeys { it in retain }
            .plus(refreshedConfigured)
            .plus(preservedOptionalConfigured)
        dataStore.edit { preferences ->
            val current = preferences[addonRegistryKey]
                ?.let(AddonRegistryCodec::decode)
                ?.let(::sanitizeCuratedRegistry)
                ?: AddonRegistryState()
            val optionalInstalled = current.addons.filter {
                SlugYardCommunitySourcePolicy.isOptionalCommunityManifest(it.manifestUrl)
            }
            val curated = addons.filter { addon ->
                addon.manifestUrl in retain &&
                    !SlugYardCommunitySourcePolicy.isOptionalCommunityManifest(addon.manifestUrl)
            }
            val byUrl = curated.associateBy(ManagedAddon::manifestUrl)
            val updated = current.addons.mapNotNull { existing ->
                when {
                    SlugYardCommunitySourcePolicy.isOptionalCommunityManifest(existing.manifestUrl) -> null
                    byUrl[existing.manifestUrl] != null ->
                        byUrl.getValue(existing.manifestUrl).copy(enabled = existing.enabled)
                    existing.manifestUrl in retain -> existing
                    else -> null
                }
            }
            val additions = curated.filter { candidate ->
                updated.none { it.manifestUrl == candidate.manifestUrl }
            }
            val next = sanitizeCuratedRegistry(
                AddonRegistryState(updated + additions + optionalInstalled),
            )
            if (next != current) {
                preferences[addonRegistryKey] = AddonRegistryCodec.encode(next)
            }
        }
    }

    override suspend fun clearCurated() {
        runtimeConfiguredUrls.value = emptyMap()
        dataStore.edit { preferences ->
            preferences.remove(addonRegistryKey)
        }
    }

    override suspend fun ensurePlayFlixNotDefaultInstalled() {
        // Only strip + clear configured URLs on the one-time migration. Running this on every
        // provision() must not wipe a user-installed PlayFlix debrid-configured manifest URL.
        var ranMigration = false
        dataStore.edit { preferences ->
            if (preferences[playFlixDefaultOffKey] == "1") return@edit
            ranMigration = true
            val current = preferences[addonRegistryKey]
                ?.let(AddonRegistryCodec::decode)
                ?.let(::sanitizeCuratedRegistry)
                ?: AddonRegistryState()
            val stripped = current.addons.filterNot {
                SlugYardCommunitySourcePolicy.isPlayFlixManifest(it.manifestUrl)
            }
            if (stripped.size != current.addons.size) {
                preferences[addonRegistryKey] = AddonRegistryCodec.encode(
                    sanitizeCuratedRegistry(AddonRegistryState(stripped)),
                )
            }
            preferences[playFlixDefaultOffKey] = "1"
        }
        if (ranMigration) {
            runtimeConfiguredUrls.value = runtimeConfiguredUrls.value
                .filterKeys { !SlugYardCommunitySourcePolicy.isPlayFlixManifest(it) }
        }
    }

    override suspend fun bindConfiguredManifestUrl(
        manifestUrl: String,
        configuredManifestUrl: String?,
    ) {
        val key = manifestUrl.trim()
        runtimeConfiguredUrls.value = if (configuredManifestUrl.isNullOrBlank()) {
            runtimeConfiguredUrls.value - key
        } else {
            runtimeConfiguredUrls.value + (key to configuredManifestUrl.trim())
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

object AddonRegistryCodec {
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
