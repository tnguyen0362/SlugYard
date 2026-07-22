package com.sluggyard.tv.core.sync.adapter

import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.model.SyncMutationEnvelope
import com.sluggyard.tv.core.sync.model.SyncOperation
import com.sluggyard.tv.core.sync.model.ProviderCredentialCiphertextCodec
import com.sluggyard.tv.core.sync.model.ProviderCredentialRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Defines the identity and timestamp rules for one Supabase sync domain.
 * Adapters deliberately operate on wire JSON so local stores can keep their
 * existing models without leaking persistence details into the sync protocol.
 */
interface SyncDomainAdapter {
    val domain: SyncDomain

    fun recordKey(record: JsonObject): String?

    fun profileId(record: JsonObject): Int? = record["profile_id"]?.jsonPrimitive?.intOrNull

    fun changedAt(record: JsonObject): Long? = sequenceOf(
        "client_changed_at",
        "updated_at_epoch_ms",
        "last_watched",
        "watched_at",
        "added_at",
    ).firstNotNullOfOrNull { key -> record[key]?.jsonPrimitive?.longOrNull }

    fun envelope(
        ownerUserId: String,
        operation: SyncOperation,
        payload: String?,
        record: JsonObject,
    ): SyncMutationEnvelope? {
        val key = recordKey(record)?.takeIf(String::isNotBlank) ?: return null
        val changedAt = changedAt(record) ?: return null
        return SyncMutationEnvelope.create(
            ownerUserId = ownerUserId,
            domain = domain,
            profileId = profileId(record),
            recordKey = key,
            operation = operation,
            clientChangedAtEpochMs = changedAt,
            payloadJson = payload,
        )
    }
}

abstract class KeyedSyncDomainAdapter(
    final override val domain: SyncDomain,
    private val identityField: String,
) : SyncDomainAdapter {
    override fun recordKey(record: JsonObject): String? =
        record[identityField]?.jsonPrimitive?.contentOrNull
}

class ProfileSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.PROFILES, "profile_index")

class AddonSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.ADDONS, "url")

class PluginSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.PLUGINS, "url")

class LibrarySyncAdapter : KeyedSyncDomainAdapter(SyncDomain.LIBRARY, "content_id")

class WatchProgressSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.WATCH_PROGRESS, "progress_key")

class WatchedItemsSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.WATCHED_ITEMS, "content_id")

class CollectionsSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.COLLECTIONS, "profile_id")

class ProfileSettingsSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.PROFILE_SETTINGS, "profile_id")

class HomeCatalogSettingsSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.HOME_CATALOG_SETTINGS, "profile_id")

class ProviderCredentialSyncAdapter : KeyedSyncDomainAdapter(SyncDomain.PROVIDER_CREDENTIALS, "provider") {
    fun fromPlaintext(
        profileId: Int?,
        providerId: String,
        plaintext: String,
        changedAtEpochMs: Long,
    ): ProviderCredentialRecord = ProviderCredentialRecord(
        profileId = profileId,
        providerId = providerId,
        ciphertext = ProviderCredentialCiphertextCodec.encrypt(providerId, plaintext),
        schemaVersion = 1,
        changedAtEpochMs = changedAtEpochMs,
    )

    fun toPlaintext(record: ProviderCredentialRecord): String {
        require(record.schemaVersion == 1) { "Unsupported provider credential ciphertext version" }
        return ProviderCredentialCiphertextCodec.decrypt(record.providerId, record.ciphertext)
    }
}

object SyncDomainAdapters {
    val all: List<SyncDomainAdapter> = listOf(
        ProfileSyncAdapter(),
        AddonSyncAdapter(),
        PluginSyncAdapter(),
        LibrarySyncAdapter(),
        WatchProgressSyncAdapter(),
        WatchedItemsSyncAdapter(),
        CollectionsSyncAdapter(),
        ProfileSettingsSyncAdapter(),
        HomeCatalogSettingsSyncAdapter(),
        ProviderCredentialSyncAdapter(),
    )

    private val byDomain = all.associateBy(SyncDomainAdapter::domain)

    fun forDomain(domain: SyncDomain): SyncDomainAdapter =
        byDomain[domain] ?: error("No sync adapter for $domain")
}
