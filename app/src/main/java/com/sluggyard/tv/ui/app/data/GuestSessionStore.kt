package com.sluggyard.tv.ui.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

private val GUEST_SESSION_KEY = booleanPreferencesKey("app_guest_session_v1")

/**
 * Persists whether the person using this device already chose "Continue as Guest".
 *
 * Guest mode is a first-class, ongoing usage mode (see PRODUCT.md: local playback must remain
 * useful without an account), not a one-shot bypass. Without this store, [AuthGate] had
 * no way to remember that choice, so any process death (TV low-memory kill, reboot, app update)
 * forced guests back through the full Sign In / Create Account / Continue as Guest screen even
 * though nothing about their local library or profiles was actually lost.
 */
class GuestSessionStore(private val dataStore: DataStore<Preferences>) {
    suspend fun isGuest(): Boolean = dataStore.data.first()[GUEST_SESSION_KEY] ?: false

    suspend fun setGuest(active: Boolean) {
        dataStore.edit { preferences ->
            if (active) preferences[GUEST_SESSION_KEY] = true else preferences.remove(GUEST_SESSION_KEY)
        }
    }
}
