package com.sluggyard.tv.ui.app.debrid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.app.data.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Rewrite-owned, profile-scoped credential boundary.
 *
 * The key is never logged or put into navigation. A provider is active only when its credential
 * belongs to the currently selected rewrite profile; addon provisioning may generate the
 * provider's configured manifest URL from that credential without exposing it to Compose.
 */
class DebridCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: ProfileRepository,
) {
    val state: Flow<DebridCredentialState> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        val profileId = profileState.activeProfile.id
        val configuredServices = DebridService.entries.filterTo(linkedSetOf()) { service ->
            val blob = preferences[credentialKey(profileId, service)].orEmpty()
            blob.startsWith(ENCRYPTED_CREDENTIAL_PREFIX) &&
                CredentialCipher.decrypt(blob)?.isNotBlank() == true
        }
        DebridCredentialState(
            activeProfileId = profileId,
            configuredServices = configuredServices,
            // Prefer the saved active pick; if it was cleared while credentials remain (e.g. after
            // disconnecting the previously-active provider), fall back so Play stays on scrapers.
            activeService = preferences[activeServiceKey(profileId)]?.let { saved ->
                DebridService.entries.firstOrNull { it.name == saved }
            }?.takeIf(configuredServices::contains)
                ?: configuredServices.firstOrNull(),
        )
    }

    suspend fun keyForActiveProfile(service: DebridService): String {
        val profileId = profiles.state.first().activeProfile.id
        val blob = dataStore.data.first()[credentialKey(profileId, service)].orEmpty()
        return withContext(Dispatchers.IO) { CredentialCipher.decrypt(blob).orEmpty().trim() }
    }

    suspend fun saveForActiveProfile(service: DebridService, apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotBlank()) { "Enter an API key" }
        val profileId = profiles.state.first().activeProfile.id
        val encrypted = withContext(Dispatchers.IO) { CredentialCipher.encrypt(normalized) }
        dataStore.edit { preferences ->
            preferences[credentialKey(profileId, service)] = encrypted
            preferences[activeServiceKey(profileId)] = service.name
        }
    }

    suspend fun selectForActiveProfile(service: DebridService) {
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            require(preferences[credentialKey(profileId, service)].orEmpty().startsWith(ENCRYPTED_CREDENTIAL_PREFIX)) { "Connect ${service.displayName} first" }
            preferences[activeServiceKey(profileId)] = service.name
        }
    }

    suspend fun removeForActiveProfile(service: DebridService) {
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            preferences.remove(credentialKey(profileId, service))
            if (preferences[activeServiceKey(profileId)] == service.name) {
                preferences.remove(activeServiceKey(profileId))
                val replacement = DebridService.entries.firstOrNull { other ->
                    other != service &&
                        preferences[credentialKey(profileId, other)].orEmpty()
                            .startsWith(ENCRYPTED_CREDENTIAL_PREFIX)
                }
                if (replacement != null) {
                    preferences[activeServiceKey(profileId)] = replacement.name
                }
            }
        }
    }

    private fun credentialKey(profileId: String, service: DebridService): Preferences.Key<String> =
        stringPreferencesKey("app_debrid_v2_${profileId}_${service.name.lowercase()}")
    private fun activeServiceKey(profileId: String): Preferences.Key<String> = stringPreferencesKey("app_debrid_active_v1_$profileId")
}

private const val ENCRYPTED_CREDENTIAL_PREFIX = "enc-v1:"

data class DebridCredentialState(
    val activeProfileId: String,
    val configuredServices: Set<DebridService>,
    val activeService: DebridService?,
)

private val DebridService.displayName: String
    get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
