package com.sluggyard.tv.core.sync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSyncModelsTest {
    @Test
    fun `profile settings decode nested settings json`() {
        val decoded = SupabaseSyncJson.decodeProfileSettings(
            """{"profile_id":2,"settings_json":{"language_tag":"en-US","theme_id":"dark","auto_play":false,"subtitles_enabled":false,"preferred_audio_language":"ja"},"updated_at":"2026-07-21T00:00:00Z"}""",
        )

        assertEquals(
            CloudProfileSettings(
                profileId = 2,
                languageTag = "en-US",
                themeId = "dark",
                autoPlay = false,
                subtitlesEnabled = false,
                preferredAudioLanguage = "ja",
                changedAt = duplexTimestamp(),
            ),
            decoded,
        )
    }

    @Test
    fun `all domain codecs preserve stable identity`() {
        val addon = CloudAddon(1, "https://addon.example", "Addon", true, 3, 10L)
        val plugin = CloudPlugin(1, "https://plugin.example", "Plugin", false, 4, "git", 11L)
        val collection = CloudCollection(1, "{\"items\":[]}", 12L)
        val home = CloudHomeCatalogSettings(1, "{\"rows\":[]}", 13L)

        assertEquals(addon.url, SupabaseSyncJson.decodeAddon(SupabaseSyncJson.encodeAddon(addon, "user"))?.url)
        assertEquals(plugin.url, SupabaseSyncJson.decodePlugin(SupabaseSyncJson.encodePlugin(plugin, "user"))?.url)
        assertEquals(collection.profileId, SupabaseSyncJson.decodeCollection(SupabaseSyncJson.encodeCollection(collection, "user"))?.profileId)
        assertEquals(home.settingsJson, SupabaseSyncJson.decodeHomeCatalogSettings(SupabaseSyncJson.encodeHomeCatalogSettings(home, "user"))?.settingsJson)
    }

    @Test
    fun `provider credential codec never exposes plaintext api key`() {
        val record = ProviderCredentialRecord(1, "real_debrid", "ciphertext-v1", 1, 10L)
        val encoded = SupabaseSyncJson.encodeProviderCredential(record, "user")

        assertTrue(encoded.contains("ciphertext-v1"))
        assertTrue(!encoded.contains("api_key"))
        assertEquals(record, SupabaseSyncJson.decodeProviderCredential(encoded))
    }

    @Test
    fun `missing credential ciphertext is rejected`() {
        assertNull(
            SupabaseSyncJson.decodeProviderCredential(
                """{"user_id":"user","profile_id":1,"provider":"real_debrid","updated_at_epoch_ms":10}""",
            ),
        )
    }

    @Test
    fun `mutation merge includes delete and rejects older winner`() {
        val older = SyncMutationEnvelope.create(
            ownerUserId = "user",
            domain = SyncDomain.LIBRARY,
            profileId = 1,
            recordKey = "movie",
            operation = SyncOperation.UPSERT,
            clientChangedAtEpochMs = 10L,
            schemaVersion = 1,
            payloadJson = "{}",
        )
        val newerDelete = SyncMutationEnvelope.create(
            ownerUserId = "user",
            domain = SyncDomain.LIBRARY,
            profileId = 1,
            recordKey = "movie",
            operation = SyncOperation.DELETE,
            clientChangedAtEpochMs = 20L,
            schemaVersion = 1,
            payloadJson = null,
        )

        assertEquals(listOf(newerDelete), SupabaseMergePolicy.mergeMutations(listOf(older), listOf(newerDelete)))
    }

    private fun duplexTimestamp(): Long = java.time.Instant.parse("2026-07-21T00:00:00Z").toEpochMilli()
}
