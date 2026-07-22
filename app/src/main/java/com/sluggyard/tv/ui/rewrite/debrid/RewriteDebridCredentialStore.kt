package com.sluggyard.tv.ui.rewrite.debrid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileRepository
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
class RewriteDebridCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: RewriteProfileRepository,
) {
    val state: Flow<RewriteDebridCredentialState> = combine(dataStore.data, profiles.state) { preferences, profileState ->
        val profileId = profileState.activeProfile.id
        RewriteDebridCredentialState(
            activeProfileId = profileId,
            configuredServices = DebridService.entries.filterTo(linkedSetOf()) { service ->
                preferences[credentialKey(profileId, service)].orEmpty().startsWith(ENCRYPTED_CREDENTIAL_PREFIX)
            },
            activeService = preferences[activeServiceKey(profileId)]?.let { saved ->
                DebridService.entries.firstOrNull { it.name == saved }
            }?.takeIf { service -> preferences[credentialKey(profileId, service)].orEmpty().startsWith(ENCRYPTED_CREDENTIAL_PREFIX) },
        )
    }

    suspend fun keyForActiveProfile(service: DebridService): String {
        val profileId = profiles.state.first().activeProfile.id
        val blob = dataStore.data.first()[credentialKey(profileId, service)].orEmpty()
        return withContext(Dispatchers.IO) { RewriteCredentialCipher.decrypt(blob).orEmpty().trim() }
    }

    suspend fun saveForActiveProfile(service: DebridService, apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotBlank()) { "Enter an API key" }
        val profileId = profiles.state.first().activeProfile.id
        val encrypted = withContext(Dispatchers.IO) { RewriteCredentialCipher.encrypt(normalized) }
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

    private fun credentialKey(profileId: String, service: DebridService): Preferences.Key<String> =
        stringPreferencesKey("rewrite_debrid_v2_${profileId}_${service.name.lowercase()}")
    private fun activeServiceKey(profileId: String): Preferences.Key<String> = stringPreferencesKey("rewrite_debrid_active_v1_$profileId")
}

private const val ENCRYPTED_CREDENTIAL_PREFIX = "enc-v1:"

data class RewriteDebridCredentialState(
    val activeProfileId: String,
    val configuredServices: Set<DebridService>,
    val activeService: DebridService?,
)

private val DebridService.displayName: String
    get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
