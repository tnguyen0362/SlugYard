package com.sluggyard.tv.ui.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Profile-scoped app preferences use two key shapes:
 * - exact: `prefix{profileId}` (home settings, active debrid pick, …)
 * - service-suffixed: `prefix{profileId}_{service}` (encrypted debrid credentials)
 *
 * Early migration only copied the exact shape, so TorBox/RD/Premiumize blobs were orphaned
 * under the old profile id after default→1 remaps — looking like the key vanished on sign-in.
 */
internal fun MutablePreferences.copyRemappedProfileScopedPreferences(remaps: Map<String, String>) {
    if (remaps.isEmpty()) return
    remaps.forEach { (fromId, toId) ->
        if (fromId == toId) return@forEach
        ExactProfilePrefixes.forEach { prefix ->
            copyIfAbsent(
                from = stringPreferencesKey("$prefix$fromId"),
                to = stringPreferencesKey("$prefix$toId"),
            )
        }
        ServiceSuffixedPrefixes.forEach { prefix ->
            copyPrefixedFamily(fromProfileId = fromId, toProfileId = toId, prefix = prefix)
        }
    }
}

/** One-shot default→1 copy used when remaps are empty but legacy "default" blobs remain. */
internal fun MutablePreferences.copyLegacyDefaultProfileScopedPreferences() {
    copyRemappedProfileScopedPreferences(mapOf("default" to "1"))
}

private fun MutablePreferences.copyIfAbsent(from: Preferences.Key<String>, to: Preferences.Key<String>) {
    val value = this[from] ?: return
    if (this[to] == null) this[to] = value
}

private fun MutablePreferences.copyPrefixedFamily(
    fromProfileId: String,
    toProfileId: String,
    prefix: String,
) {
    val fromStem = "$prefix$fromProfileId"
    val toStem = "$prefix$toProfileId"
    // Snapshot keys first — mutating while iterating Preferences keys is unsafe.
    val matches = asMap().keys.mapNotNull { key ->
        val name = key.name
        when {
            name == fromStem -> name to toStem
            name.startsWith("${fromStem}_") -> name to (toStem + name.removePrefix(fromStem))
            else -> null
        }
    }
    matches.forEach { (fromName, toName) ->
        copyIfAbsent(
            from = stringPreferencesKey(fromName),
            to = stringPreferencesKey(toName),
        )
    }
}

private val ExactProfilePrefixes = listOf(
    "app_home_settings_v2_",
    "app_library_watch_v2_",
    "app_playback_progress_v2_",
    "app_debrid_active_v1_",
)

private val ServiceSuffixedPrefixes = listOf(
    "app_debrid_v2_",
)

/**
 * If the active profile has no encrypted debrid blob for a service but another profile id
 * still holds one (orphaned by id remaps / store renames), copy it onto the active profile.
 * Never overwrites an existing active blob.
 */
internal fun MutablePreferences.recoverOrphanedDebridCredentials(activeProfileId: String) {
    if (activeProfileId.isBlank()) return
    val prefix = "app_debrid_v2_"
    val activeStem = "$prefix$activeProfileId"
    val orphansByService = linkedMapOf<String, Preferences.Key<*>>()
    asMap().keys.forEach { key ->
        val name = key.name
        if (!name.startsWith(prefix)) return@forEach
        if (name == activeStem || name.startsWith("${activeStem}_")) return@forEach
        val rest = name.removePrefix(prefix)
        val sep = rest.indexOf('_')
        if (sep <= 0 || sep >= rest.lastIndex) return@forEach
        val service = rest.substring(sep + 1).lowercase()
        if (service.isBlank()) return@forEach
        orphansByService.putIfAbsent(service, key)
    }
    orphansByService.forEach { (service, fromKey) ->
        val toKey = stringPreferencesKey("${activeStem}_$service")
        val value = this[fromKey] as? String ?: return@forEach
        if (value.isBlank()) return@forEach
        if (this[toKey] == null) this[toKey] = value
    }
    val activePickKey = stringPreferencesKey("app_debrid_active_v1_$activeProfileId")
    if (this[activePickKey] == null) {
        orphansByService.keys.firstOrNull()?.let { service ->
            // Stored as DebridService.name (TORBOX / REAL_DEBRID / …).
            this[activePickKey] = service.uppercase()
        }
    }
}
