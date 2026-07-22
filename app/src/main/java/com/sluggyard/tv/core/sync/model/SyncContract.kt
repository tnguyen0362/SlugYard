package com.sluggyard.tv.core.sync.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class SyncDomain {
    PROFILES,
    ADDONS,
    PLUGINS,
    LIBRARY,
    WATCH_PROGRESS,
    WATCHED_ITEMS,
    COLLECTIONS,
    PROFILE_SETTINGS,
    HOME_CATALOG_SETTINGS,
    PROVIDER_CREDENTIALS,
}

enum class SyncOperation {
    UPSERT,
    DELETE,
    ;

    fun encode(): String = name

    companion object {
        fun decode(value: String): SyncOperation? = entries.firstOrNull {
            it.name == value.trim().uppercase(Locale.ROOT)
        }
    }
}

data class SyncMutationEnvelope(
    val mutationId: String,
    val ownerUserId: String,
    val domain: SyncDomain,
    val profileId: Int?,
    val recordKey: String,
    val operation: SyncOperation,
    val clientChangedAtEpochMs: Long,
    val schemaVersion: Int,
    val payloadJson: String?,
) {
    init {
        SyncSchemaVersion.requireSupported(schemaVersion)
        require(
            mutationId == SyncMutationId.forMutation(
                ownerUserId = ownerUserId,
                domain = domain,
                profileId = profileId,
                recordKey = recordKey,
                clientChangedAtEpochMs = clientChangedAtEpochMs,
                operation = operation,
            ),
        ) {
            "Mutation ID does not match its canonical identity"
        }
    }

    companion object {
        fun create(
            ownerUserId: String,
            domain: SyncDomain,
            profileId: Int?,
            recordKey: String,
            operation: SyncOperation,
            clientChangedAtEpochMs: Long,
            schemaVersion: Int = SyncSchemaVersion.CURRENT,
            payloadJson: String?,
        ): SyncMutationEnvelope = SyncMutationEnvelope(
            mutationId = SyncMutationId.forMutation(
                ownerUserId = ownerUserId,
                domain = domain,
                profileId = profileId,
                recordKey = recordKey,
                clientChangedAtEpochMs = clientChangedAtEpochMs,
                operation = operation,
            ),
            ownerUserId = ownerUserId,
            domain = domain,
            profileId = profileId,
            recordKey = recordKey,
            operation = operation,
            clientChangedAtEpochMs = clientChangedAtEpochMs,
            schemaVersion = schemaVersion,
            payloadJson = payloadJson,
        )
    }
}

data class SyncRecordIdentity(
    val ownerUserId: String,
    val domain: SyncDomain,
    val profileId: Int?,
    val recordKey: String,
)

data class SyncTombstone(
    val ownerUserId: String,
    val domain: SyncDomain,
    val profileId: Int?,
    val recordKey: String,
    val clientChangedAtEpochMs: Long,
    val mutationId: String,
    val schemaVersion: Int,
) {
    init {
        SyncSchemaVersion.requireSupported(schemaVersion)
    }

    val identity: SyncRecordIdentity
        get() = SyncRecordIdentity(ownerUserId, domain, profileId, recordKey)

    companion object {
        fun from(envelope: SyncMutationEnvelope): SyncTombstone {
            require(envelope.operation == SyncOperation.DELETE) {
                "Only delete mutations can become tombstones"
            }
            return SyncTombstone(
                ownerUserId = envelope.ownerUserId,
                domain = envelope.domain,
                profileId = envelope.profileId,
                recordKey = envelope.recordKey,
                clientChangedAtEpochMs = envelope.clientChangedAtEpochMs,
                mutationId = envelope.mutationId,
                schemaVersion = envelope.schemaVersion,
            )
        }
    }
}

data class SyncRetryMetadata(
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long? = null,
    val lastError: String? = null,
) {
    init {
        require(attemptCount >= 0) { "Retry attempt count cannot be negative" }
    }
}

data class SyncCursor(
    val ownerUserId: String,
    val domain: SyncDomain,
    val profileId: Int?,
    val cursorValue: Long = 0L,
    val clientChangedAtEpochMs: Long = 0L,
    val schemaVersion: Int = SyncSchemaVersion.CURRENT,
) {
    init {
        SyncSchemaVersion.requireSupported(schemaVersion)
    }
}

@JvmInline
value class SyncSchemaVersion(val value: Int) {
    val isSupported: Boolean
        get() = value == CURRENT

    companion object {
        const val CURRENT: Int = 1

        fun isSupported(version: Int): Boolean = version == CURRENT

        fun requireSupported(version: Int): SyncSchemaVersion {
            if (!isSupported(version)) throw UnsupportedSyncSchemaVersionException(version)
            return SyncSchemaVersion(version)
        }
    }
}

class UnsupportedSyncSchemaVersionException(
    val version: Int,
) : IllegalArgumentException("Unsupported sync schema version: $version")

object SyncMutationId {
    // Canonical encoding is the concatenation of UTF-8-byte-length-prefixed
    // owner, domain, optional profile, record key, timestamp, and operation.
    fun forMutation(
        ownerUserId: String,
        domain: SyncDomain,
        profileId: Int?,
        recordKey: String,
        clientChangedAtEpochMs: Long,
        operation: SyncOperation,
    ): String {
        val canonical = listOf(
            ownerUserId,
            domain.name,
            profileId?.toString().orEmpty(),
            recordKey,
            clientChangedAtEpochMs.toString(),
            operation.encode(),
        ).joinToString(separator = "") { value ->
            val utf8ByteLength = value.toByteArray(StandardCharsets.UTF_8).size
            "$utf8ByteLength:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
    }
}
