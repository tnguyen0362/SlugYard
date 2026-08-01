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
