package com.sluggyard.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authSessionNoticeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_session_notice_store"
)

enum class StartupAuthNotice {
    TRAKT
}

@Singleton
class AuthSessionNoticeDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val hadTraktAuthKey = booleanPreferencesKey("had_trakt_auth")
    private val traktExplicitLogoutKey = booleanPreferencesKey("trakt_explicit_logout")
    private val pendingTraktNoticeKey = booleanPreferencesKey("pending_trakt_notice")

    val pendingNotice: Flow<StartupAuthNotice?> =
        context.authSessionNoticeDataStore.data.map { prefs ->
            if (prefs[pendingTraktNoticeKey] == true) StartupAuthNotice.TRAKT else null
        }

    suspend fun markTraktAuthenticated() {
        context.authSessionNoticeDataStore.edit { prefs ->
            prefs[hadTraktAuthKey] = true
            prefs[traktExplicitLogoutKey] = false
            prefs[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markTraktExplicitLogout() {
        context.authSessionNoticeDataStore.edit { prefs ->
            prefs[hadTraktAuthKey] = false
            prefs[traktExplicitLogoutKey] = true
            prefs[pendingTraktNoticeKey] = false
        }
    }

    suspend fun markUnexpectedTraktLogoutIfNeeded(): Boolean {
        var didMark = false
        context.authSessionNoticeDataStore.edit { prefs ->
            val hadAuth = prefs[hadTraktAuthKey] == true
            val explicitLogout = prefs[traktExplicitLogoutKey] == true
            if (hadAuth && !explicitLogout) {
                prefs[pendingTraktNoticeKey] = true
                didMark = true
            }
            prefs[hadTraktAuthKey] = false
            prefs[traktExplicitLogoutKey] = false
        }
        return didMark
    }

    suspend fun consumeNotice(notice: StartupAuthNotice) {
        context.authSessionNoticeDataStore.edit { prefs ->
            when (notice) {
                StartupAuthNotice.TRAKT -> prefs[pendingTraktNoticeKey] = false
            }
        }
    }
}