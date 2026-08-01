package com.sluggyard.tv.ui.app.debrid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.ui.app.data.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Profile-scoped AIOStreams user session (uuid + password + encryptedPassword for the
 * Stremio manifest path). Plaintext is only held in memory after Keystore decrypt.
 */
class AioStreamsSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val profiles: ProfileRepository,
) {
    data class Session(
        val uuid: String,
        val password: String,
        val encryptedPassword: String,
        val baseUrl: String,
    )

    suspend fun load(): Session? {
        val profileId = profiles.state.first().activeProfile.id
        val preferences = dataStore.data.first()
        val uuid = preferences[uuidKey(profileId)].orEmpty().trim()
        val passwordBlob = preferences[passwordKey(profileId)].orEmpty()
        val encryptedPasswordBlob = preferences[encryptedPasswordKey(profileId)].orEmpty()
        val baseUrl = preferences[baseUrlKey(profileId)].orEmpty().trim()
        if (uuid.isBlank() || baseUrl.isBlank()) return null
        val password = withContext(Dispatchers.IO) {
            CredentialCipher.decrypt(passwordBlob).orEmpty().trim()
        }
        val encryptedPassword = withContext(Dispatchers.IO) {
            CredentialCipher.decrypt(encryptedPasswordBlob).orEmpty().trim()
        }
        if (password.isBlank() || encryptedPassword.isBlank()) return null
        return Session(uuid, password, encryptedPassword, baseUrl)
    }

    suspend fun save(session: Session) {
        val profileId = profiles.state.first().activeProfile.id
        val passwordBlob = withContext(Dispatchers.IO) {
            CredentialCipher.encrypt(session.password)
        }
        val encryptedPasswordBlob = withContext(Dispatchers.IO) {
            CredentialCipher.encrypt(session.encryptedPassword)
        }
        dataStore.edit { preferences ->
            preferences[uuidKey(profileId)] = session.uuid
            preferences[passwordKey(profileId)] = passwordBlob
            preferences[encryptedPasswordKey(profileId)] = encryptedPasswordBlob
            preferences[baseUrlKey(profileId)] = session.baseUrl.trim().trimEnd('/')
        }
    }

    suspend fun clear() {
        val profileId = profiles.state.first().activeProfile.id
        dataStore.edit { preferences ->
            preferences.remove(uuidKey(profileId))
            preferences.remove(passwordKey(profileId))
            preferences.remove(encryptedPasswordKey(profileId))
            preferences.remove(baseUrlKey(profileId))
        }
    }

    private fun uuidKey(profileId: String) =
        stringPreferencesKey("app_aiostreams_uuid_v1_$profileId")

    private fun passwordKey(profileId: String) =
        stringPreferencesKey("app_aiostreams_password_v1_$profileId")

    private fun encryptedPasswordKey(profileId: String) =
        stringPreferencesKey("app_aiostreams_enc_password_v1_$profileId")

    private fun baseUrlKey(profileId: String) =
        stringPreferencesKey("app_aiostreams_base_v1_$profileId")
}
