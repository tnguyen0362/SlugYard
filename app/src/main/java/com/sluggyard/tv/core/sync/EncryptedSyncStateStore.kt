package com.sluggyard.tv.core.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sluggyard.tv.core.sync.model.SyncSchemaVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SyncStateEnvelope(
    val ownerUserId: String,
    val schemaVersion: Int = SyncSchemaVersion.CURRENT,
    val mutations: List<com.sluggyard.tv.core.sync.model.SyncMutationEnvelope> = emptyList(),
    val tombstones: List<com.sluggyard.tv.core.sync.model.SyncTombstone> = emptyList(),
    val cursors: List<com.sluggyard.tv.core.sync.model.SyncCursor> = emptyList(),
    val payloadJson: String? = null,
) {
    init {
        require(ownerUserId.isNotBlank()) { "Sync state owner cannot be blank" }
        SyncSchemaVersion.requireSupported(schemaVersion)
        require(mutations.all { it.ownerUserId == ownerUserId }) {
            "Sync mutation owner does not match envelope owner"
        }
        require(tombstones.all { it.ownerUserId == ownerUserId }) {
            "Sync tombstone owner does not match envelope owner"
        }
        require(cursors.all { it.ownerUserId == ownerUserId }) {
            "Sync cursor owner does not match envelope owner"
        }
    }
}

enum class SyncCorruptionReason {
    MALFORMED,
    WRONG_OWNER,
    LEGACY_UNOWNED,
    UNSUPPORTED_SCHEMA,
    DECRYPTION_FAILED,
}

interface EncryptedSyncStateStore {
    suspend fun read(ownerUserId: String): SyncStateEnvelope

    suspend fun write(ownerUserId: String, state: SyncStateEnvelope)

    suspend fun quarantine(ownerUserId: String, reason: SyncCorruptionReason)

    suspend fun clearActive(ownerUserId: String)
}

interface SyncStateCipher {
    fun encrypt(payload: String): String

    fun decrypt(blob: String): String?
}

internal val SYNC_STATE_ACTIVE_KEY = stringPreferencesKey("encrypted_state_v2")
internal val SYNC_STATE_LEGACY_KEY = stringPreferencesKey("encrypted_state")
internal val SYNC_STATE_QUARANTINE_KEY = stringPreferencesKey("encrypted_state_quarantine_v1")

class DataStoreEncryptedSyncStateStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SyncStateCipher = AndroidSyncStateCipher,
) : EncryptedSyncStateStore {
    private val mutex = Mutex()

    override suspend fun read(ownerUserId: String): SyncStateEnvelope = withContext(Dispatchers.IO) {
        requireOwner(ownerUserId)
        mutex.withLock {
            val preferences = dataStore.data.first()
            val active = preferences[SYNC_STATE_ACTIVE_KEY]
            val legacy = preferences[SYNC_STATE_LEGACY_KEY]
            val blob = active ?: legacy ?: return@withLock emptyState(ownerUserId)
            if (active == null && legacy != null) {
                quarantineLocked(ownerUserId, SyncCorruptionReason.LEGACY_UNOWNED)
                return@withLock emptyState(ownerUserId)
            }

            val payload = cipher.decrypt(blob)
            if (payload == null) {
                quarantineLocked(ownerUserId, SyncCorruptionReason.DECRYPTION_FAILED)
                return@withLock emptyState(ownerUserId)
            }
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            if (root == null) {
                quarantineLocked(ownerUserId, SyncCorruptionReason.MALFORMED)
                return@withLock emptyState(ownerUserId)
            }
            val storedOwner = root[OWNER_KEY]?.jsonPrimitive?.contentOrNull
            if (storedOwner == null) {
                quarantineLocked(ownerUserId, SyncCorruptionReason.LEGACY_UNOWNED)
                return@withLock emptyState(ownerUserId)
            }
            if (storedOwner != ownerUserId) {
                quarantineLocked(ownerUserId, SyncCorruptionReason.WRONG_OWNER)
                return@withLock emptyState(ownerUserId)
            }
            val state = decode(root)
            if (state == null) {
                val reason = if (root[SCHEMA_KEY]?.jsonPrimitive?.intOrNull != SyncSchemaVersion.CURRENT) {
                    SyncCorruptionReason.UNSUPPORTED_SCHEMA
                } else {
                    SyncCorruptionReason.MALFORMED
                }
                quarantineLocked(ownerUserId, reason)
                emptyState(ownerUserId)
            } else {
                state
            }
        }
    }

    override suspend fun write(ownerUserId: String, state: SyncStateEnvelope) {
        requireOwner(ownerUserId)
        require(state.ownerUserId == ownerUserId) {
            "Sync state owner does not match authenticated user"
        }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val preferences = dataStore.data.first()
                val currentBlob = preferences[SYNC_STATE_ACTIVE_KEY] ?: preferences[SYNC_STATE_LEGACY_KEY]
                val currentOwner = currentBlob?.let(::storedOwner)
                if (currentBlob != null && currentOwner != ownerUserId) {
                    val reason = when {
                        preferences[SYNC_STATE_LEGACY_KEY] != null -> SyncCorruptionReason.LEGACY_UNOWNED
                        currentOwner == null -> SyncCorruptionReason.MALFORMED
                        else -> SyncCorruptionReason.WRONG_OWNER
                    }
                    quarantineLocked(ownerUserId, reason)
                }
                val blob = cipher.encrypt(Codec.encode(state))
                dataStore.edit {
                    it[SYNC_STATE_ACTIVE_KEY] = blob
                    it.remove(SYNC_STATE_LEGACY_KEY)
                    it.remove(SYNC_STATE_QUARANTINE_KEY)
                }
            }
        }
    }

    override suspend fun quarantine(ownerUserId: String, reason: SyncCorruptionReason) =
        withContext(Dispatchers.IO) {
            requireOwner(ownerUserId)
            mutex.withLock { quarantineLocked(ownerUserId, reason) }
        }

    override suspend fun clearActive(ownerUserId: String) = withContext(Dispatchers.IO) {
        requireOwner(ownerUserId)
        mutex.withLock {
            val preferences = dataStore.data.first()
            val blob = preferences[SYNC_STATE_ACTIVE_KEY] ?: preferences[SYNC_STATE_LEGACY_KEY]
            if (blob == null) return@withLock
            val storedOwner = storedOwner(blob)
            when {
                storedOwner == null && preferences[SYNC_STATE_LEGACY_KEY] != null ->
                    quarantineLocked(ownerUserId, SyncCorruptionReason.LEGACY_UNOWNED)
                storedOwner == null -> quarantineLocked(ownerUserId, SyncCorruptionReason.MALFORMED)
                storedOwner != ownerUserId -> quarantineLocked(ownerUserId, SyncCorruptionReason.WRONG_OWNER)
                else -> dataStore.edit {
                    it.remove(SYNC_STATE_ACTIVE_KEY)
                    it.remove(SYNC_STATE_LEGACY_KEY)
                }
            }
        }
    }

    private suspend fun quarantineLocked(ownerUserId: String, reason: SyncCorruptionReason) {
        val preferences = dataStore.data.first()
        val blob = preferences[SYNC_STATE_ACTIVE_KEY] ?: preferences[SYNC_STATE_LEGACY_KEY]
        dataStore.edit {
            if (blob != null) {
                it[SYNC_STATE_QUARANTINE_KEY] = "$ownerUserId|${reason.name}|$blob"
            }
            it.remove(SYNC_STATE_ACTIVE_KEY)
            it.remove(SYNC_STATE_LEGACY_KEY)
        }
    }

    private fun storedOwner(blob: String): String? = cipher.decrypt(blob)
        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?.get(OWNER_KEY)
        ?.jsonPrimitive
        ?.contentOrNull

    private fun emptyState(ownerUserId: String) = SyncStateEnvelope(ownerUserId)

    private companion object {
        const val OWNER_KEY = "owner_user_id"
        const val SCHEMA_KEY = "schema_version"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun requireOwner(ownerUserId: String) {
            require(ownerUserId.isNotBlank()) { "Sync state owner cannot be blank" }
        }

        fun decode(root: kotlinx.serialization.json.JsonObject): SyncStateEnvelope? = runCatching {
            val owner = root[OWNER_KEY]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val schemaVersion = root[SCHEMA_KEY]?.jsonPrimitive?.intOrNull ?: return@runCatching null
            SyncSchemaVersion.requireSupported(schemaVersion)
            SyncStateEnvelope(
                ownerUserId = owner,
                schemaVersion = schemaVersion,
                mutations = root["mutations"]?.jsonArray?.mapNotNull(Codec::decodeMutation).orEmpty(),
                tombstones = root["tombstones"]?.jsonArray?.mapNotNull(Codec::decodeTombstone).orEmpty(),
                cursors = root["cursors"]?.jsonArray?.mapNotNull(Codec::decodeCursor).orEmpty(),
                payloadJson = root["payload_json"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrNull()
    }
}

private object Codec {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(state: SyncStateEnvelope): String = buildJsonObject {
        put("owner_user_id", state.ownerUserId)
        put("schema_version", state.schemaVersion)
        put("mutations", buildJsonArray { state.mutations.forEach { add(encodeMutation(it)) } })
        put("tombstones", buildJsonArray { state.tombstones.forEach { add(encodeTombstone(it)) } })
        put("cursors", buildJsonArray { state.cursors.forEach { add(encodeCursor(it)) } })
        if (state.payloadJson != null) put("payload_json", state.payloadJson) else put("payload_json", JsonNull)
    }.toString()

    fun decodeMutation(element: kotlinx.serialization.json.JsonElement) = runCatching {
        val root = element.jsonObject
        val owner = root["owner_user_id"]!!.jsonPrimitive.content
        val domain = com.sluggyard.tv.core.sync.model.SyncDomain.entries.first {
            it.name == root["domain"]!!.jsonPrimitive.content
        }
        val operation = com.sluggyard.tv.core.sync.model.SyncOperation.entries.first {
            it.name == root["operation"]!!.jsonPrimitive.content
        }
        com.sluggyard.tv.core.sync.model.SyncMutationEnvelope(
            mutationId = root["mutation_id"]!!.jsonPrimitive.content,
            ownerUserId = owner,
            domain = domain,
            profileId = root["profile_id"]?.jsonPrimitive?.intOrNull,
            recordKey = root["record_key"]!!.jsonPrimitive.content,
            operation = operation,
            clientChangedAtEpochMs = root["client_changed_at_epoch_ms"]!!.jsonPrimitive.longOrNull!!,
            schemaVersion = root["schema_version"]!!.jsonPrimitive.intOrNull!!,
            payloadJson = root["payload_json"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    fun decodeTombstone(element: kotlinx.serialization.json.JsonElement) = runCatching {
        val root = element.jsonObject
        com.sluggyard.tv.core.sync.model.SyncTombstone(
            ownerUserId = root["owner_user_id"]!!.jsonPrimitive.content,
            domain = com.sluggyard.tv.core.sync.model.SyncDomain.entries.first {
                it.name == root["domain"]!!.jsonPrimitive.content
            },
            profileId = root["profile_id"]?.jsonPrimitive?.intOrNull,
            recordKey = root["record_key"]!!.jsonPrimitive.content,
            clientChangedAtEpochMs = root["client_changed_at_epoch_ms"]!!.jsonPrimitive.longOrNull!!,
            mutationId = root["mutation_id"]!!.jsonPrimitive.content,
            schemaVersion = root["schema_version"]!!.jsonPrimitive.intOrNull!!,
        )
    }.getOrNull()

    fun decodeCursor(element: kotlinx.serialization.json.JsonElement) = runCatching {
        val root = element.jsonObject
        com.sluggyard.tv.core.sync.model.SyncCursor(
            ownerUserId = root["owner_user_id"]!!.jsonPrimitive.content,
            domain = com.sluggyard.tv.core.sync.model.SyncDomain.entries.first {
                it.name == root["domain"]!!.jsonPrimitive.content
            },
            profileId = root["profile_id"]?.jsonPrimitive?.intOrNull,
            cursorValue = root["cursor_value"]!!.jsonPrimitive.longOrNull!!,
            clientChangedAtEpochMs = root["client_changed_at_epoch_ms"]!!.jsonPrimitive.longOrNull!!,
            schemaVersion = root["schema_version"]!!.jsonPrimitive.intOrNull!!,
        )
    }.getOrNull()

    private fun encodeMutation(value: com.sluggyard.tv.core.sync.model.SyncMutationEnvelope) = buildJsonObject {
        put("mutation_id", value.mutationId)
        put("owner_user_id", value.ownerUserId)
        put("domain", value.domain.name)
        if (value.profileId != null) put("profile_id", value.profileId) else put("profile_id", JsonNull)
        put("record_key", value.recordKey)
        put("operation", value.operation.name)
        put("client_changed_at_epoch_ms", value.clientChangedAtEpochMs)
        put("schema_version", value.schemaVersion)
        if (value.payloadJson != null) put("payload_json", value.payloadJson) else put("payload_json", JsonNull)
    }

    private fun encodeTombstone(value: com.sluggyard.tv.core.sync.model.SyncTombstone) = buildJsonObject {
        put("owner_user_id", value.ownerUserId)
        put("domain", value.domain.name)
        if (value.profileId != null) put("profile_id", value.profileId) else put("profile_id", JsonNull)
        put("record_key", value.recordKey)
        put("client_changed_at_epoch_ms", value.clientChangedAtEpochMs)
        put("mutation_id", value.mutationId)
        put("schema_version", value.schemaVersion)
    }

    private fun encodeCursor(value: com.sluggyard.tv.core.sync.model.SyncCursor) = buildJsonObject {
        put("owner_user_id", value.ownerUserId)
        put("domain", value.domain.name)
        if (value.profileId != null) put("profile_id", value.profileId) else put("profile_id", JsonNull)
        put("cursor_value", value.cursorValue)
        put("client_changed_at_epoch_ms", value.clientChangedAtEpochMs)
        put("schema_version", value.schemaVersion)
    }
}

private object AndroidSyncStateCipher : SyncStateCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "slugyard.supabase.sync.state.v2"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc-v2:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val keyLock = Any()

    override fun encrypt(payload: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        return PREFIX + Base64.encodeToString(
            cipher.iv + cipher.doFinal(payload.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
    }

    override fun decrypt(blob: String): String? {
        if (!blob.startsWith(PREFIX)) return null
        return runCatching {
            val payload = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
            require(payload.size > IV_BYTES) { "Malformed sync state blob" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)),
                )
            }
            cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
