package com.sluggyard.tv.ui.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileScopedPreferenceMigrationTest {
    @Test
    fun copiesServiceSuffixedDebridCredentialsWhenProfileIdRemaps() {
        val fromKey = stringPreferencesKey("app_debrid_v2_default_torbox")
        val toKey = stringPreferencesKey("app_debrid_v2_1_torbox")
        val activeFrom = stringPreferencesKey("app_debrid_active_v1_default")
        val activeTo = stringPreferencesKey("app_debrid_active_v1_1")
        val prefs = mutablePreferencesOf(
            fromKey to "enc-v1:blob",
            activeFrom to "TORBOX",
        )

        prefs.copyRemappedProfileScopedPreferences(mapOf("default" to "1"))

        assertEquals("enc-v1:blob", prefs[toKey])
        assertEquals("TORBOX", prefs[activeTo])
        // Source blobs are retained — copy-if-absent, not move.
        assertEquals("enc-v1:blob", prefs[fromKey])
    }

    @Test
    fun doesNotOverwriteExistingDestinationCredential() {
        val fromKey = stringPreferencesKey("app_debrid_v2_default_torbox")
        val toKey = stringPreferencesKey("app_debrid_v2_1_torbox")
        val prefs = mutablePreferencesOf(
            fromKey to "enc-v1:old",
            toKey to "enc-v1:keep",
        )

        prefs.copyRemappedProfileScopedPreferences(mapOf("default" to "1"))

        assertEquals("enc-v1:keep", prefs[toKey])
    }

    @Test
    fun legacyDefaultHelperCopiesExactAndSuffixedKeys() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("app_home_settings_v2_default") to "home",
            stringPreferencesKey("app_debrid_v2_default_real_debrid") to "enc-v1:rd",
        )

        prefs.copyLegacyDefaultProfileScopedPreferences()

        assertEquals("home", prefs[stringPreferencesKey("app_home_settings_v2_1")])
        assertEquals("enc-v1:rd", prefs[stringPreferencesKey("app_debrid_v2_1_real_debrid")])
        assertNull(prefs[stringPreferencesKey("app_debrid_v2_1")])
    }

    @Test
    fun recoversOrphanedTorboxCredentialOntoActiveProfile() {
        val orphan = stringPreferencesKey("app_debrid_v2_default_torbox")
        val active = stringPreferencesKey("app_debrid_v2_1_torbox")
        val prefs = mutablePreferencesOf(orphan to "enc-v1:torbox-blob")

        prefs.recoverOrphanedDebridCredentials("1")

        assertEquals("enc-v1:torbox-blob", prefs[active])
        assertEquals("TORBOX", prefs[stringPreferencesKey("app_debrid_active_v1_1")])
        assertEquals("enc-v1:torbox-blob", prefs[orphan])
    }

    @Test
    fun recoverDoesNotOverwriteActiveCredential() {
        val orphan = stringPreferencesKey("app_debrid_v2_9_torbox")
        val active = stringPreferencesKey("app_debrid_v2_1_torbox")
        val prefs = mutablePreferencesOf(
            orphan to "enc-v1:old",
            active to "enc-v1:keep",
        )

        prefs.recoverOrphanedDebridCredentials("1")

        assertEquals("enc-v1:keep", prefs[active])
    }
}
