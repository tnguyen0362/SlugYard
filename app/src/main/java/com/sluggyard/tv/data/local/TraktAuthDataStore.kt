package com.sluggyard.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.sluggyard.tv.data.remote.dto.trakt.TraktTokenResponseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TRAKT_ACCESS_TOKEN_MAX_LIFETIME_SECONDS = 86_400

internal fun normalizeTraktTokenLifetimeSeconds(expiresIn: Int): Int {
    if (expiresIn <= 0) return TRAKT_ACCESS_TOKEN_MAX_LIFETIME_SECONDS
    return expiresIn.coerceAtMost(TRAKT_ACCESS_TOKEN_MAX_LIFETIME_SECONDS)
}

data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val userSlug: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresAt: Long? = null,
    val pollInterval: Int? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE_NAME = "trakt_auth_store"
    }

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val tokenTypeKey = stringPreferencesKey("token_type")
    private val createdAtKey = longPreferencesKey("created_at")
    private val expiresInKey = intPreferencesKey("expires_in")

    private val usernameKey = stringPreferencesKey("username")
    private val userSlugKey = stringPreferencesKey("user_slug")

    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE_NAME)

    private fun readState(prefs: androidx.datastore.preferences.core.Preferences): TraktAuthState =
        TraktAuthState(
            accessToken = prefs[accessTokenKey],
            refreshToken = prefs[refreshTokenKey],
            tokenType = prefs[tokenTypeKey],
            createdAt = prefs[createdAtKey],
            expiresIn = prefs[expiresInKey]?.let(::normalizeTraktTokenLifetimeSeconds),
            username = prefs[usernameKey],
            userSlug = prefs[userSlugKey],
            deviceCode = prefs[deviceCodeKey],
            userCode = prefs[userCodeKey],
            verificationUrl = prefs[verificationUrlKey],
            expiresAt = prefs[expiresAtKey],
            pollInterval = prefs[pollIntervalKey]
        )

    val state: Flow<TraktAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { readState(it) }
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated

    /** Direct read of auth state for the given profile, bypassing flatMapLatest. */
    suspend fun getCurrentState(profileId: Int = profileManager.activeProfileId.value): TraktAuthState {
        val prefs = store(profileId).data.first()
        return readState(prefs)
    }

    suspend fun saveToken(token: TraktTokenResponseDto) {
        store().edit { prefs ->
            prefs[accessTokenKey] = token.accessToken
            prefs[refreshTokenKey] = token.refreshToken
            prefs[tokenTypeKey] = token.tokenType
            prefs[createdAtKey] = token.createdAt
            prefs[expiresInKey] = normalizeTraktTokenLifetimeSeconds(token.expiresIn)
        }
    }

    suspend fun saveUser(username: String?, userSlug: String?) {
        store().edit { prefs ->
            if (username.isNullOrBlank()) prefs.remove(usernameKey) else prefs[usernameKey] = username
            if (userSlug.isNullOrBlank()) prefs.remove(userSlugKey) else prefs[userSlugKey] = userSlug
        }
    }

    suspend fun saveSyncedAuthState(
        state: TraktAuthState,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { prefs ->
            if (!state.isAuthenticated) {
                prefs.remove(accessTokenKey)
                prefs.remove(refreshTokenKey)
                prefs.remove(tokenTypeKey)
                prefs.remove(createdAtKey)
                prefs.remove(expiresInKey)
                prefs.remove(usernameKey)
                prefs.remove(userSlugKey)
                prefs.remove(deviceCodeKey)
                prefs.remove(userCodeKey)
                prefs.remove(verificationUrlKey)
                prefs.remove(expiresAtKey)
                prefs.remove(pollIntervalKey)
                return@edit
            }

            prefs[accessTokenKey] = state.accessToken.orEmpty()
            prefs[refreshTokenKey] = state.refreshToken.orEmpty()
            prefs[tokenTypeKey] = state.tokenType ?: "bearer"
            prefs[createdAtKey] = state.createdAt ?: (System.currentTimeMillis() / 1000L)
            prefs[expiresInKey] = normalizeTraktTokenLifetimeSeconds(
                state.expiresIn ?: TRAKT_ACCESS_TOKEN_MAX_LIFETIME_SECONDS
            )

            if (state.username.isNullOrBlank()) prefs.remove(usernameKey) else prefs[usernameKey] = state.username
            if (state.userSlug.isNullOrBlank()) prefs.remove(userSlugKey) else prefs[userSlugKey] = state.userSlug

            prefs.remove(deviceCodeKey)
            prefs.remove(userCodeKey)
            prefs.remove(verificationUrlKey)
            prefs.remove(expiresAtKey)
            prefs.remove(pollIntervalKey)
        }
    }

    suspend fun saveDeviceFlow(data: TraktDeviceCodeResponseDto) {
        val now = System.currentTimeMillis()
        store().edit { prefs ->
            prefs[deviceCodeKey] = data.deviceCode
            prefs[userCodeKey] = data.userCode
            prefs[verificationUrlKey] = data.verificationUrl
            prefs[expiresAtKey] = now + (data.expiresIn * 1000L)
            prefs[pollIntervalKey] = data.interval
        }
    }

    suspend fun updatePollInterval(seconds: Int) {
        store().edit { prefs -> prefs[pollIntervalKey] = seconds }
    }

    suspend fun clearDeviceFlow() {
        store().edit { prefs ->
            prefs.remove(deviceCodeKey)
            prefs.remove(userCodeKey)
            prefs.remove(verificationUrlKey)
            prefs.remove(expiresAtKey)
            prefs.remove(pollIntervalKey)
        }
    }

    suspend fun clearAuth() {
        store().edit { prefs ->
            prefs.remove(accessTokenKey)
            prefs.remove(refreshTokenKey)
            prefs.remove(tokenTypeKey)
            prefs.remove(createdAtKey)
            prefs.remove(expiresInKey)
            prefs.remove(usernameKey)
            prefs.remove(userSlugKey)
            prefs.remove(deviceCodeKey)
            prefs.remove(userCodeKey)
            prefs.remove(verificationUrlKey)
            prefs.remove(expiresAtKey)
            prefs.remove(pollIntervalKey)
        }
    }
}