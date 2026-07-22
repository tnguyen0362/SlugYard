package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class RewriteHomeSettings(
    val hideUnreleased: Boolean = false,
    val excludedCatalogKeys: Set<String> = emptySet(),
    val catalogOrderKeys: List<String> = emptyList(),
) {
    fun excludes(addonId: String, catalogId: String): Boolean = catalogKey(addonId, catalogId) in excludedCatalogKeys
    fun savedCatalogOrder(): List<HomeCatalogKey> = catalogOrderKeys
        .asSequence()
        .mapNotNull(::parseCatalogKey)
        .distinct()
        .toList()

    companion object { fun catalogKey(addonId: String, catalogId: String) = "$addonId\u0000$catalogId" }
}

interface RewriteHomeSettingsRepository {
    val settings: Flow<RewriteHomeSettings>
    suspend fun setHideUnreleased(enabled: Boolean)
    suspend fun setCatalogVisible(addonId: String, catalogId: String, visible: Boolean)
    suspend fun setCatalogOrder(order: List<HomeCatalogKey>)
}

private fun rewriteHomeSettingsKey(profileId: String) = stringPreferencesKey("rewrite_home_settings_v2_$profileId")

class RewriteHomeSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: RewriteProfileRepository,
) : RewriteHomeSettingsRepository {
    override val settings: Flow<RewriteHomeSettings> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        preferences[rewriteHomeSettingsKey(profileState.activeProfile.id)]?.let(RewriteHomeSettingsCodec::decode) ?: RewriteHomeSettings()
    }

    override suspend fun setHideUnreleased(enabled: Boolean) = mutate { it.copy(hideUnreleased = enabled) }

    override suspend fun setCatalogVisible(addonId: String, catalogId: String, visible: Boolean) = mutate { current ->
        val key = RewriteHomeSettings.catalogKey(addonId, catalogId)
        current.copy(excludedCatalogKeys = current.excludedCatalogKeys.toMutableSet().also { keys ->
            if (visible) keys -= key else keys += key
        })
    }

    override suspend fun setCatalogOrder(order: List<HomeCatalogKey>) = mutate { current ->
        current.copy(catalogOrderKeys = order.distinct().map { RewriteHomeSettings.catalogKey(it.addonId, it.catalogId) })
    }

    private suspend fun mutate(transform: (RewriteHomeSettings) -> RewriteHomeSettings) {
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            val key = rewriteHomeSettingsKey(profileId)
            val current = preferences[key]?.let(RewriteHomeSettingsCodec::decode) ?: RewriteHomeSettings()
            preferences[key] = RewriteHomeSettingsCodec.encode(transform(current))
        }
    }
}

object RewriteHomeSettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(settings: RewriteHomeSettings) = json.encodeToString(
        StoredHomeSettings(settings.hideUnreleased, settings.excludedCatalogKeys.sorted(), settings.catalogOrderKeys),
    )
    fun decode(raw: String): RewriteHomeSettings = runCatching {
        json.decodeFromString<StoredHomeSettings>(raw).let {
            RewriteHomeSettings(it.hideUnreleased, it.excludedCatalogKeys.toSet(), it.catalogOrderKeys)
        }
    }.getOrDefault(RewriteHomeSettings())
}

@Serializable
private data class StoredHomeSettings(
    val hideUnreleased: Boolean = false,
    val excludedCatalogKeys: List<String> = emptyList(),
    val catalogOrderKeys: List<String> = emptyList(),
)

private fun parseCatalogKey(raw: String): HomeCatalogKey? {
    val separator = raw.indexOf('\u0000')
    if (separator <= 0 || separator == raw.lastIndex) return null
    return HomeCatalogKey(raw.substring(0, separator), raw.substring(separator + 1))
}
