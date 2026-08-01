package com.sluggyard.tv.ui.app.debrid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.debrid.DebridProviders
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.core.sync.ProviderCredentialSyncBridge
import com.sluggyard.tv.core.sync.SyncMutationRecorder
import com.sluggyard.tv.core.sync.adapter.ProviderCredentialSyncAdapter
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.model.ProviderCredentialRecord
import com.sluggyard.tv.core.sync.remote.SyncMutation
import com.sluggyard.tv.data.local.DebridSettingsDataStore
import kotlinx.coroutines.flow.first

/**
 * Keeps rewrite-owned debrid keys aligned with the account credential vault.
 *
 * Guest-entered TorBox/RD/Premiumize keys live in [DebridCredentialStore]. Sign-in used to
 * sync progress/library but never promoted those keys — so Connect looked empty after auth even
 * though the encrypted blob was still on disk under a profile id.
 */
class DataStoreProviderCredentialSyncBridge(
    private val dataStore: DataStore<Preferences>,
    private val mutationRecorder: SyncMutationRecorder,
    private val debridSettings: DebridSettingsDataStore,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : ProviderCredentialSyncBridge {
    private val adapter = ProviderCredentialSyncAdapter()

    override suspend fun snapshot(): List<ProviderCredentialRecord> =
        localPlainCredentials().mapNotNull { (profileId, service, plaintext) ->
            val providerId = service.providerId() ?: return@mapNotNull null
            adapter.fromPlaintext(
                profileId = profileId,
                providerId = providerId,
                plaintext = plaintext,
                changedAtEpochMs = nowEpochMs(),
            )
        }

    override suspend fun apply(records: List<ProviderCredentialRecord>) {
        records.forEach { record ->
            val targetProfileId = record.profileId ?: return@forEach
            val service = serviceForProviderId(record.providerId) ?: return@forEach
            val plaintext = runCatching { adapter.toPlaintext(record) }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            saveCredential(targetProfileId.toString(), service, plaintext)
            // Dual-write legacy store for the *same* profile as the rewrite key — never active.
            dualWriteLegacyApiKey(targetProfileId, record.providerId, plaintext)
        }
    }

    override suspend fun flushPending() {
        snapshot().forEach { record ->
            mutationRecorder.record(SyncMutation.ProviderCredential(record))
        }
    }

    /** Dual-write after Cloud Manager / Integrations Connect while signed in. */
    override suspend fun recordLocalConnect(profileId: String, service: DebridService, apiKey: String) {
        val providerId = service.providerId() ?: return
        val normalized = apiKey.trim()
        if (normalized.isBlank()) return
        val profileInt = legacyDualWriteProfileId(profileId)
        // Skip legacy dual-write when profileId is not an Int — never fall back to active.
        if (profileInt != null) {
            dualWriteLegacyApiKey(profileInt, providerId, normalized)
        }
        val record = adapter.fromPlaintext(
            profileId = profileInt,
            providerId = providerId,
            plaintext = normalized,
            changedAtEpochMs = nowEpochMs(),
        )
        when (mutationRecorder.record(SyncMutation.ProviderCredential(record))) {
            is SyncResult.Success -> Unit
            else -> Unit // Guest / offline — rewrite store already has the key locally.
        }
    }

    private suspend fun dualWriteLegacyApiKey(profileId: Int, providerId: String, plaintext: String) {
        debridSettings.setProviderApiKey(providerId, plaintext, profileId = profileId)
    }

    private suspend fun localPlainCredentials(): List<Triple<Int, DebridService, String>> {
        val prefs = dataStore.data.first()
        return prefs.asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (!name.startsWith(CREDENTIAL_PREFIX)) return@mapNotNull null
            val rest = name.removePrefix(CREDENTIAL_PREFIX)
            val sep = rest.indexOf('_')
            if (sep <= 0) return@mapNotNull null
            val profileId = rest.substring(0, sep).toIntOrNull() ?: return@mapNotNull null
            val serviceName = rest.substring(sep + 1)
            val service = DebridService.entries.firstOrNull { it.name.equals(serviceName, ignoreCase = true) }
                ?: return@mapNotNull null
            val blob = value as? String ?: return@mapNotNull null
            val plaintext = CredentialCipher.decrypt(blob)?.trim().orEmpty()
            if (plaintext.isEmpty()) return@mapNotNull null
            Triple(profileId, service, plaintext)
        }
    }

    private suspend fun saveCredential(profileId: String, service: DebridService, apiKey: String) {
        val encrypted = CredentialCipher.encrypt(apiKey.trim())
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("$CREDENTIAL_PREFIX${profileId}_${service.name.lowercase()}")] = encrypted
            val activeKey = stringPreferencesKey("app_debrid_active_v1_$profileId")
            if (preferences[activeKey].isNullOrBlank()) {
                preferences[activeKey] = service.name
            }
        }
    }

    companion object {
        private const val CREDENTIAL_PREFIX = "app_debrid_v2_"
    }
}

private fun DebridService.providerId(): String? = when (this) {
    DebridService.TORBOX -> DebridProviders.TORBOX_ID
    DebridService.PREMIUMIZE -> DebridProviders.PREMIUMIZE_ID
    DebridService.REAL_DEBRID -> DebridProviders.REAL_DEBRID_ID
}

private fun serviceForProviderId(providerId: String): DebridService? = when (providerId) {
    DebridProviders.TORBOX_ID -> DebridService.TORBOX
    DebridProviders.PREMIUMIZE_ID -> DebridService.PREMIUMIZE
    DebridProviders.REAL_DEBRID_ID -> DebridService.REAL_DEBRID
    else -> null
}

/**
 * Resolves the Int profile id for a legacy [DebridSettingsDataStore] dual-write.
 *
 * Returns null when the target cannot be mapped — callers must **not** fall back to the
 * active profile (that was the cross-profile leak).
 */
internal fun legacyDualWriteProfileId(targetProfileId: String?): Int? =
    targetProfileId?.toIntOrNull()
