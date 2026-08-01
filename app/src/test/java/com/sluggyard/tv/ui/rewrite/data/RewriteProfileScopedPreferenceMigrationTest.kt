package com.sluggyard.tv.ui.rewrite.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewriteProfileScopedPreferenceMigrationTest {
    @Test
    fun copiesServiceSuffixedDebridCredentialsWhenProfileIdRemaps() {
        val fromKey = stringPreferencesKey("rewrite_debrid_v2_default_torbox")
        val toKey = stringPreferencesKey("rewrite_debrid_v2_1_torbox")
        val activeFrom = stringPreferencesKey("rewrite_debrid_active_v1_default")
        val activeTo = stringPreferencesKey("rewrite_debrid_active_v1_1")
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
        val fromKey = stringPreferencesKey("rewrite_debrid_v2_default_torbox")
        val toKey = stringPreferencesKey("rewrite_debrid_v2_1_torbox")
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
            stringPreferencesKey("rewrite_home_settings_v2_default") to "home",
            stringPreferencesKey("rewrite_debrid_v2_default_real_debrid") to "enc-v1:rd",
        )

        prefs.copyLegacyDefaultProfileScopedPreferences()

        assertEquals("home", prefs[stringPreferencesKey("rewrite_home_settings_v2_1")])
        assertEquals("enc-v1:rd", prefs[stringPreferencesKey("rewrite_debrid_v2_1_real_debrid")])
        assertNull(prefs[stringPreferencesKey("rewrite_debrid_v2_1")])
    }
}
