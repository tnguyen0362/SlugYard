package com.sluggyard.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.domain.model.DebridSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val debridSettingsDataStore: DebridSettingsDataStore
) {
    private companion object {
        const val FEATURE_NAME = "addon_preferences"
        const val MANIFEST_SUFFIX = "/manifest.json"
    }

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    private fun store(profileId: Int = effectiveProfileId()) =
        factory.get(profileId, FEATURE_NAME)

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        if (activeProfile?.usesPrimaryAddons == true) 1 else activeProfileId
    }.distinctUntilChanged()

    private val gson = Gson()
    private val orderedUrlsKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val legacyUrlsKey = stringSetPreferencesKey("installed_addon_urls")
    private val userSetNamesKey = stringPreferencesKey("addon_user_set_names")
    private val addonEnabledStatesKey = stringPreferencesKey("installed_addon_enabled_states")
    // URLs provisioned by the one-key setup flow. Keep these separate from
    // user addons so a key rotation can replace them atomically.
    private val managedContentSourceUrlsKey = stringPreferencesKey("managed_content_source_urls")

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            path.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    val installedAddonUrls: Flow<List<String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE_NAME).data.map { prefs ->
            val json = prefs[orderedUrlsKey]
            if (json != null) parseUrlList(json) else {
                val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
                legacySet.toList()
            }
        }
    }

    val addonEnabledStates: Flow<Map<String, Boolean>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE_NAME).data.map { prefs ->
            prefs[addonEnabledStatesKey]?.let(::parseEnabledStateMap).orEmpty()
        }
    }

    suspend fun ensureMigrated() {
        val ds = store()
        val prefs = ds.data.first()
        if (prefs[orderedUrlsKey] == null) {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            ds.edit { preferences ->
                preferences[orderedUrlsKey] = gson.toJson(legacySet.toList())
                preferences.remove(legacyUrlsKey)
            }
        }
    }

    private fun isPrimaryAddonLocked(): Boolean {
        val active = profileManager.activeProfile
        return active != null && !active.isPrimary && active.usesPrimaryAddons
    }

    suspend fun addAddon(url: String) {
        if (isPrimaryAddonLocked()) return
        store().edit { prefs ->
            val current = getCurrentList(prefs)
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true) }) return@edit
            prefs[orderedUrlsKey] = gson.toJson(current + normalizedUrl)
            val states = getCurrentEnabledStates(prefs).toMutableMap()
            states[normalizedUrl] = true
            prefs[addonEnabledStatesKey] = gson.toJson(states)
        }
    }

    suspend fun removeAddon(url: String) {
        if (isPrimaryAddonLocked()) return
        store().edit { prefs ->
            val current = getCurrentList(prefs).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)

            val indexToRemove = current.indexOfFirst {
                canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true)
            }
            if (indexToRemove != -1) current.removeAt(indexToRemove)
            prefs[orderedUrlsKey] = gson.toJson(current)
            val states = getCurrentEnabledStates(prefs).toMutableMap()
            states.remove(normalizedUrl)
            prefs[addonEnabledStatesKey] = gson.toJson(states)
        }
    }

    suspend fun setAddonOrder(urls: List<String>) {
        if (isPrimaryAddonLocked()) return
        store().edit { prefs ->
            val orderedUrls = urls.map(::canonicalizeUrl)
            prefs[orderedUrlsKey] = gson.toJson(orderedUrls)
            val currentStates = getCurrentEnabledStates(prefs)
            prefs[addonEnabledStatesKey] = gson.toJson(
                orderedUrls.associateWith { url -> currentStates[url] ?: true }
            )
        }
    }

    suspend fun setAddonEnabled(url: String, enabled: Boolean) {
        if (isPrimaryAddonLocked()) return
        store().edit { prefs ->
            val states = getCurrentEnabledStates(prefs).toMutableMap()
            states[canonicalizeUrl(url)] = enabled
            prefs[addonEnabledStatesKey] = gson.toJson(states)
        }
    }

    suspend fun setAddonEnabledStates(states: Map<String, Boolean>) {
        if (isPrimaryAddonLocked()) return
        store().edit { prefs ->
            prefs[addonEnabledStatesKey] = gson.toJson(
                states.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun getCurrentList(prefs: Preferences): List<String> {
        val json = prefs[orderedUrlsKey]
        return if (json != null) parseUrlList(json) else {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.toList()
        }
    }

    private fun parseUrlList(json: String): List<String> = try {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson(json, type) ?: getDefaultAddons().toList()
    } catch (_: Exception) {
        getDefaultAddons().toList()
    }

    val userSetNames: Flow<Map<String, String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE_NAME).data.map { prefs ->
            val json = prefs[userSetNamesKey]
            if (json != null) parseNameMap(json) else emptyMap()
        }
    }

    suspend fun setUserSetNames(names: Map<String, String>) {
        store().edit { prefs ->
            prefs[userSetNamesKey] = gson.toJson(
                names.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun parseNameMap(json: String): Map<String, String> = try {
        val type = object : TypeToken<Map<String, String>>() {}.type
        val parsed: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
        parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun getCurrentEnabledStates(prefs: Preferences): Map<String, Boolean> {
        val json = prefs[addonEnabledStatesKey] ?: return emptyMap()
        return parseEnabledStateMap(json)
    }

    private fun parseEnabledStateMap(json: String): Map<String, Boolean> = try {
        val type = object : TypeToken<Map<String, Boolean>>() {}.type
        val parsed: Map<String, Boolean> = gson.fromJson(json, type) ?: emptyMap()
        parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun getDefaultAddons(): Set<String> =
        com.sluggyard.tv.core.addon.SlugYardBundledAddons.INFRASTRUCTURE_ADDONS.toSet()

    /**
     * Add content source addons with debrid credentials.
     * Called when debrid credentials are saved.
     */
    suspend fun addContentSourceAddons(debridSettings: DebridSettings) {
        if (isPrimaryAddonLocked()) return
        val contentUrls = com.sluggyard.tv.core.addon.SlugYardBundledAddons
            .buildConfiguredContentSourceUrls(debridSettings)

        store().edit { prefs ->
            val current = getCurrentList(prefs).toMutableList()
            val states = getCurrentEnabledStates(prefs).toMutableMap()

            // Remove the previously generated config (and the pre-tracking
            // legacy managed sources) before adding the current credential.
            val managed = prefs[managedContentSourceUrlsKey]
                ?.let(::parseUrlList)
                .orEmpty()
                .ifEmpty { current.filter(com.sluggyard.tv.core.addon.SlugYardBundledAddons::isContentSourceAddon) }
                .map(::canonicalizeUrl)
                .toSet()
            current.removeAll { canonicalizeUrl(it) in managed }
            managed.forEach(states::remove)

            contentUrls.forEach { url ->
                val normalizedUrl = canonicalizeUrl(url)
                if (!current.any { canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true) }) {
                    current.add(normalizedUrl)
                    states[normalizedUrl] = true
                }
            }

            prefs[orderedUrlsKey] = gson.toJson(current)
            prefs[addonEnabledStatesKey] = gson.toJson(states)
            prefs[managedContentSourceUrlsKey] = gson.toJson(contentUrls.map(::canonicalizeUrl))
        }
    }
}