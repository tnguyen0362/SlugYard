package com.sluggyard.tv.core.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.model.CloudLibraryItem
import com.sluggyard.tv.core.sync.model.CloudProfile
import com.sluggyard.tv.core.sync.model.CloudProfileSettings
import com.sluggyard.tv.core.sync.model.CloudWatchProgress
import com.sluggyard.tv.core.sync.model.CloudWatchedItem
import com.sluggyard.tv.core.sync.model.CloudAddon
import com.sluggyard.tv.core.sync.model.CloudPlugin
import com.sluggyard.tv.core.sync.model.CloudCollection
import com.sluggyard.tv.core.sync.model.CloudHomeCatalogSettings
import com.sluggyard.tv.core.sync.model.ProviderCredentialRecord
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.model.SupabaseMergePolicy
import com.sluggyard.tv.core.sync.model.SupabaseSyncJson
import com.sluggyard.tv.core.sync.remote.RemoteSnapshot
import com.sluggyard.tv.core.sync.remote.SupabaseDataGateway
import com.sluggyard.tv.core.sync.remote.SyncMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class LocalSnapshot(
    val profiles: List<CloudProfile> = emptyList(),
    val library: List<CloudLibraryItem> = emptyList(),
    val progress: List<CloudWatchProgress> = emptyList(),
    val watchedItems: List<CloudWatchedItem> = emptyList(),
    val profileSettings: List<CloudProfileSettings> = emptyList(),
    val addons: List<CloudAddon> = emptyList(),
    val plugins: List<CloudPlugin> = emptyList(),
    val collections: List<CloudCollection> = emptyList(),
    val homeCatalogSettings: List<CloudHomeCatalogSettings> = emptyList(),
    val providerCredentials: List<ProviderCredentialRecord> = emptyList(),
)

data class SyncSummary(
    val pulledRecords: Int,
    val pushedMutations: Int,
    val retainedMutations: Int,
)

interface LocalSyncStore {
    suspend fun snapshot(): LocalSnapshot

    suspend fun apply(snapshot: RemoteSnapshot)

    suspend fun enqueue(mutation: SyncMutation)

    suspend fun record(mutation: SyncMutation) {
        enqueue(mutation)
    }

    suspend fun pending(): List<SyncMutation>

    suspend fun acknowledge(mutationId: String)
}

/**
 * Adapter for Rewrite-owned progress storage. The main sync snapshot remains the durable outbox
 * source, while this bridge keeps the Rewrite presentation state in step with merged records.
 */
interface ProgressSyncBridge {
    suspend fun snapshot(): List<CloudWatchProgress>

    suspend fun apply(progress: List<CloudWatchProgress>)

    suspend fun record(progress: CloudWatchProgress)

    suspend fun remove(profileId: Int, progressKey: String, changedAtEpochMs: Long)

    /** Promotes locally captured guest progress into the account-bound outbox after login. */
    suspend fun flushPending() = Unit
}

data class LibraryWatchSyncSnapshot(
    val library: List<CloudLibraryItem> = emptyList(),
    val watchedItems: List<CloudWatchedItem> = emptyList(),
)

/** Adapter for Rewrite-owned library and watched state. */
interface LibraryWatchSyncBridge {
    suspend fun snapshot(): LibraryWatchSyncSnapshot

    suspend fun apply(snapshot: LibraryWatchSyncSnapshot)
}

/**
 * Promotes guest/local rewrite debrid keys into the account vault on sign-in, and applies
 * remote provider credentials back into the rewrite credential store.
 */
interface ProviderCredentialSyncBridge {
    suspend fun snapshot(): List<ProviderCredentialRecord>

    suspend fun apply(records: List<ProviderCredentialRecord>)

    /** Push every local rewrite debrid key into the outbox after login. */
    suspend fun flushPending()

    /** Dual-write + outbox after Cloud Manager / Integrations Connect. */
    suspend fun recordLocalConnect(
        profileId: String,
        service: com.sluggyard.tv.core.streamresolution.DebridService,
        apiKey: String,
    )
}

interface RetryAwareLocalSyncStore {
    suspend fun recordAttempt(mutationId: String, attempt: Int, nextAttemptAtEpochMs: Long)
}

interface AccountBoundLocalSyncStore {
    suspend fun bindOwner(ownerUserId: String)
}

class SyncCoordinator(
    private val sessions: SupabaseSessionStore,
    private val remote: SupabaseDataGateway,
    private val local: LocalSyncStore,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 3,
    private val progress: ProgressSyncBridge? = null,
    private val libraryWatch: LibraryWatchSyncBridge? = null,
    private val providerCredentials: ProviderCredentialSyncBridge? = null,
) {
    private val synchronizeMutex = Mutex()

    suspend fun synchronize(): SyncResult<SyncSummary> = synchronizeMutex.withLock {
        synchronizeLocked()
    }

    private suspend fun synchronizeLocked(): SyncResult<SyncSummary> {
        val session = (sessions.read() as? SupabaseSessionState.Active)?.session
            ?: return SyncResult.Success(SyncSummary(0, 0, local.pending().size))
        (local as? AccountBoundLocalSyncStore)?.bindOwner(session.userId)
        progress?.flushPending()
        providerCredentials?.flushPending()

        val remoteSnapshot = when (val result = remote.pull(session.userId)) {
            is SyncResult.Success -> result.value
            is SyncResult.Failure -> return result
            SyncResult.SessionExpired -> return SyncResult.SessionExpired
        }
        val localProgress = progress?.snapshot().orEmpty()
        val localLibraryWatch = libraryWatch?.snapshot()
        val localCredentials = providerCredentials?.snapshot().orEmpty()
        val localSnapshot = local.snapshot().let { snapshot ->
            snapshot.copy(
                progress = progress?.let {
                    SupabaseMergePolicy.mergeProgress(snapshot.progress, localProgress)
                } ?: snapshot.progress,
                library = libraryWatch?.let {
                    SupabaseMergePolicy.mergeLibrary(snapshot.library, localLibraryWatch?.library.orEmpty())
                } ?: snapshot.library,
                watchedItems = libraryWatch?.let {
                    SupabaseMergePolicy.mergeWatchedItems(
                        snapshot.watchedItems,
                        localLibraryWatch?.watchedItems.orEmpty(),
                    )
                } ?: snapshot.watchedItems,
                providerCredentials = providerCredentials?.let {
                    SupabaseMergePolicy.mergeProviderCredentials(
                        snapshot.providerCredentials,
                        localCredentials,
                    )
                } ?: snapshot.providerCredentials,
            )
        }
        val pendingBeforeMerge = local.pending()
        val merged = merge(localSnapshot, remoteSnapshot)
            .withoutPendingDeletes(pendingBeforeMerge)
            .withoutStaleProgress(localProgress, remoteSnapshot.progress, pendingBeforeMerge)
        local.apply(merged.toRemoteSnapshot())
        progress?.apply(merged.progress)
        libraryWatch?.apply(
            LibraryWatchSyncSnapshot(
                library = merged.library,
                watchedItems = merged.watchedItems,
            ),
        )
        providerCredentials?.apply(merged.providerCredentials)

        var pushed = 0
        var firstFailure: SyncResult<Unit>? = null
        val pending = local.pending()
        for (mutation in pending) {
            val mutationId = mutation.stableId()
            var lastFailure: SyncResult<Unit>? = null
            for (attempt in 1..maxAttempts) {
                when (val result = remote.upsert(mutation)) {
                    is SyncResult.Success -> {
                        local.acknowledge(mutationId)
                        pushed++
                        lastFailure = null
                        break
                    }
                    is SyncResult.Failure -> {
                        lastFailure = result
                        if (!isRetryable(result.kind) || attempt == maxAttempts) break
                        (local as? RetryAwareLocalSyncStore)?.recordAttempt(
                            mutationId,
                            attempt,
                            nowEpochMs() + retryDelayMs(attempt),
                        )
                        wait(retryDelayMs(attempt))
                    }
                    SyncResult.SessionExpired -> {
                        lastFailure = SyncResult.SessionExpired
                        break
                    }
                }
            }
            if (lastFailure != null) {
                firstFailure = firstFailure ?: lastFailure
            }
        }
        firstFailure?.let { failure ->
            return when (failure) {
                is SyncResult.Failure -> SyncResult.Failure(failure.kind)
                SyncResult.SessionExpired -> SyncResult.SessionExpired
                is SyncResult.Success -> error("Successful mutation cannot remain pending")
            }
        }
        return SyncResult.Success(
            SyncSummary(
                pulledRecords = remoteSnapshot.recordCount,
                pushedMutations = pushed,
                retainedMutations = local.pending().size,
            ),
        )
    }

    private fun isRetryable(kind: SyncFailureKind): Boolean = when (kind) {
        SyncFailureKind.Network,
        SyncFailureKind.RateLimited,
        SyncFailureKind.Server,
        -> true
        else -> false
    }

    private fun retryDelayMs(attempt: Int): Long = (250L shl (attempt - 1)).coerceAtMost(2_000L)
}

private fun merge(local: LocalSnapshot, remote: RemoteSnapshot): LocalSnapshot = LocalSnapshot(
    profiles = SupabaseMergePolicy.mergeProfiles(local.profiles, remote.profiles),
    addons = SupabaseMergePolicy.mergeAddons(local.addons, remote.addons),
    plugins = SupabaseMergePolicy.mergePlugins(local.plugins, remote.plugins),
    library = SupabaseMergePolicy.mergeLibrary(local.library, remote.library),
    progress = SupabaseMergePolicy.mergeProgress(local.progress, remote.progress),
    watchedItems = SupabaseMergePolicy.mergeWatchedItems(local.watchedItems, remote.watchedItems),
    profileSettings = SupabaseMergePolicy.mergeProfileSettings(local.profileSettings, remote.profileSettings),
    collections = SupabaseMergePolicy.mergeCollections(local.collections, remote.collections),
    homeCatalogSettings = SupabaseMergePolicy.mergeHomeCatalogSettings(
        local.homeCatalogSettings,
        remote.homeCatalogSettings,
    ),
    providerCredentials = SupabaseMergePolicy.mergeProviderCredentials(
        local.providerCredentials,
        remote.providerCredentials,
    ),
)

/** Prevents a stale remote row from resurrecting a local delete before its outbox is pushed. */
private fun LocalSnapshot.withoutPendingDeletes(pending: List<SyncMutation>): LocalSnapshot =
    pending.filterIsInstance<SyncMutation.Delete>().fold(this) { snapshot, mutation ->
        val delete = mutation.value
        fun matches(profileId: Int, recordKey: String): Boolean =
            (delete.profileId == null || delete.profileId == profileId) && delete.recordKey == recordKey

        when (delete.domain) {
            SyncDomain.PROFILES -> copy(profiles = profiles.filterNot { it.profileId.toString() == delete.recordKey })
            SyncDomain.ADDONS -> copy(addons = addons.filterNot { matches(it.profileId, it.url) })
            SyncDomain.PLUGINS -> copy(plugins = plugins.filterNot { matches(it.profileId, it.url) })
            SyncDomain.LIBRARY -> copy(library = library.filterNot { matches(it.profileId, it.contentId) })
            SyncDomain.WATCH_PROGRESS -> copy(progress = progress.filterNot { matches(it.profileId, it.progressKey) })
            SyncDomain.WATCHED_ITEMS -> copy(watchedItems = watchedItems.filterNot { matches(it.profileId, it.contentId) })
            SyncDomain.COLLECTIONS -> copy(collections = collections.filterNot { it.profileId.toString() == delete.recordKey })
            SyncDomain.PROFILE_SETTINGS -> copy(profileSettings = profileSettings.filterNot { it.profileId.toString() == delete.recordKey })
            SyncDomain.HOME_CATALOG_SETTINGS -> copy(homeCatalogSettings = homeCatalogSettings.filterNot { it.profileId.toString() == delete.recordKey })
            SyncDomain.PROVIDER_CREDENTIALS -> copy(
                providerCredentials = providerCredentials.filterNot {
                    (delete.profileId == null || delete.profileId == it.profileId) && delete.recordKey == it.providerId
                },
            )
        }
    }

/** Treats an authenticated remote snapshot as authoritative for Rewrite rows already promoted. */
private fun LocalSnapshot.withoutStaleProgress(
    local: List<CloudWatchProgress>,
    remote: List<CloudWatchProgress>,
    pending: List<SyncMutation>,
): LocalSnapshot {
    if (local.isEmpty()) return this
    val remoteKeys = remote.mapTo(mutableSetOf()) { it.profileId to it.progressKey }
    val pendingKeys = pending.filterIsInstance<SyncMutation.Progress>()
        .mapTo(mutableSetOf()) { it.value.profileId to it.value.progressKey }
    val staleKeys = local
        .map { it.profileId to it.progressKey }
        .filterNot { it in remoteKeys || it in pendingKeys }
        .toSet()
    if (staleKeys.isEmpty()) return this
    return copy(progress = progress.filterNot { (it.profileId to it.progressKey) in staleKeys })
}

private fun LocalSnapshot.toRemoteSnapshot() = RemoteSnapshot(
    profiles,
    library,
    progress,
    watchedItems,
    profileSettings,
    addons,
    plugins,
    collections,
    homeCatalogSettings,
    providerCredentials,
)

private val RemoteSnapshot.recordCount: Int
    get() = profiles.size + addons.size + plugins.size + library.size + progress.size +
        watchedItems.size + collections.size + profileSettings.size + homeCatalogSettings.size + providerCredentials.size

internal fun SyncMutation.stableId(): String = when (this) {
    is SyncMutation.Profile -> "profile:${value.profileId}:${value.changedAt}"
    is SyncMutation.Addon -> "addon:${value.profileId}:${value.url}"
    is SyncMutation.Plugin -> "plugin:${value.profileId}:${value.url}"
    is SyncMutation.LibraryItem -> "library:${value.profileId}:${value.contentId}"
    is SyncMutation.Progress -> "progress:${value.profileId}:${value.progressKey}"
    is SyncMutation.WatchedItem -> "watched:${value.profileId}:${value.contentId}"
    is SyncMutation.Collection -> "collection:${value.profileId}"
    is SyncMutation.ProfileSettings -> "settings:${value.profileId}:${value.changedAt}"
    is SyncMutation.HomeCatalogSettings -> "home-settings:${value.profileId}"
    is SyncMutation.ProviderCredential -> "credential:${value.profileId}:${value.providerId}"
    is SyncMutation.Delete -> "delete:${value.mutationId}"
}

class DataStoreLocalSyncStore(
    context: Context,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    stateStore: EncryptedSyncStateStore? = null,
) : LocalSyncStore, RetryAwareLocalSyncStore, AccountBoundLocalSyncStore {
    private val encryptedStateStore: EncryptedSyncStateStore = stateStore ?: DataStoreEncryptedSyncStateStore(
        PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("slugyard_supabase_sync_v1") },
        ),
    )
    private val mutex = Mutex()
    private var ownerUserId: String? = null

    override suspend fun bindOwner(ownerUserId: String) = withContext(Dispatchers.IO) {
        require(ownerUserId.isNotBlank()) { "Sync owner cannot be blank" }
        mutex.withLock { this@DataStoreLocalSyncStore.ownerUserId = ownerUserId }
    }

    override suspend fun snapshot(): LocalSnapshot = mutex.withLock {
        readState().snapshot
    }

    override suspend fun apply(snapshot: RemoteSnapshot) = mutex.withLock {
        updateState { it.copy(snapshot = snapshot.toLocalSnapshot()) }
    }

    override suspend fun enqueue(mutation: SyncMutation) = mutex.withLock {
        updateState { state ->
            if (state.outbox.any { it.id == mutation.stableId() }) state
            else state.copy(outbox = state.outbox + PendingEntry(mutation.stableId(), mutation, 0, 0L))
        }
    }

    override suspend fun record(mutation: SyncMutation) = mutex.withLock {
        updateState { state ->
            val updatedSnapshot = state.snapshot.withLocalMutation(mutation)
            val retainedOutbox = state.outbox.filterNot { it.mutation.hasSameRecord(mutation) }
            state.copy(
                snapshot = updatedSnapshot,
                outbox = retainedOutbox + PendingEntry(mutation.stableId(), mutation, 0, 0L),
            )
        }
    }

    override suspend fun pending(): List<SyncMutation> = mutex.withLock {
        readState().outbox.filter { it.nextAttemptAtEpochMs <= nowEpochMs() }.map(PendingEntry::mutation)
    }

    override suspend fun acknowledge(mutationId: String) = mutex.withLock {
        updateState { state -> state.copy(outbox = state.outbox.filterNot { it.id == mutationId }) }
    }

    override suspend fun recordAttempt(mutationId: String, attempt: Int, nextAttemptAtEpochMs: Long) = mutex.withLock {
        updateState { state ->
            state.copy(outbox = state.outbox.map { entry ->
                if (entry.id == mutationId) entry.copy(attempts = attempt, nextAttemptAtEpochMs = nextAttemptAtEpochMs)
                else entry
            })
        }
    }

    private suspend fun readState(): StoredState = withContext(Dispatchers.IO) {
        val owner = ownerUserId ?: return@withContext StoredState.EMPTY
        val envelope = encryptedStateStore.read(owner)
        val encoded = envelope.payloadJson ?: return@withContext StoredState.EMPTY
        runCatching { StorageCodec.decode(encoded) }
            .getOrElse {
                encryptedStateStore.quarantine(owner, SyncCorruptionReason.MALFORMED)
                StoredState.EMPTY
            }
    }

    private suspend fun updateState(transform: (StoredState) -> StoredState) {
        val updated = transform(readState())
        val owner = ownerUserId ?: return
        val encoded = withContext(Dispatchers.IO) { StorageCodec.encode(updated) }
        encryptedStateStore.write(
            ownerUserId = owner,
            state = SyncStateEnvelope(
                ownerUserId = owner,
                payloadJson = encoded,
            ),
        )
    }
}

private fun LocalSnapshot.withLocalMutation(mutation: SyncMutation): LocalSnapshot = when (mutation) {
    is SyncMutation.Profile -> copy(profiles = SupabaseMergePolicy.mergeProfiles(profiles, listOf(mutation.value)))
    is SyncMutation.Addon -> copy(addons = SupabaseMergePolicy.mergeAddons(addons, listOf(mutation.value)))
    is SyncMutation.Plugin -> copy(plugins = SupabaseMergePolicy.mergePlugins(plugins, listOf(mutation.value)))
    is SyncMutation.LibraryItem -> copy(library = SupabaseMergePolicy.mergeLibrary(library, listOf(mutation.value)))
    is SyncMutation.Progress -> copy(progress = SupabaseMergePolicy.mergeProgress(progress, listOf(mutation.value)))
    is SyncMutation.WatchedItem -> copy(watchedItems = SupabaseMergePolicy.mergeWatchedItems(watchedItems, listOf(mutation.value)))
    is SyncMutation.Collection -> copy(collections = SupabaseMergePolicy.mergeCollections(collections, listOf(mutation.value)))
    is SyncMutation.ProfileSettings -> copy(profileSettings = SupabaseMergePolicy.mergeProfileSettings(profileSettings, listOf(mutation.value)))
    is SyncMutation.HomeCatalogSettings -> copy(
        homeCatalogSettings = SupabaseMergePolicy.mergeHomeCatalogSettings(homeCatalogSettings, listOf(mutation.value)),
    )
    is SyncMutation.ProviderCredential -> copy(
        providerCredentials = SupabaseMergePolicy.mergeProviderCredentials(providerCredentials, listOf(mutation.value)),
    )
    is SyncMutation.Delete -> when (mutation.value.domain) {
        SyncDomain.PROFILES -> copy(profiles = profiles.filterNot { it.profileId.toString() == mutation.value.recordKey })
        SyncDomain.ADDONS -> copy(addons = addons.filterNot { it.profileId == mutation.value.profileId && it.url == mutation.value.recordKey })
        SyncDomain.PLUGINS -> copy(plugins = plugins.filterNot { it.profileId == mutation.value.profileId && it.url == mutation.value.recordKey })
        SyncDomain.LIBRARY -> copy(library = library.filterNot { it.profileId == mutation.value.profileId && it.contentId == mutation.value.recordKey })
        SyncDomain.WATCH_PROGRESS -> copy(progress = progress.filterNot { it.profileId == mutation.value.profileId && it.progressKey == mutation.value.recordKey })
        SyncDomain.WATCHED_ITEMS -> copy(watchedItems = watchedItems.filterNot { it.profileId == mutation.value.profileId && it.contentId == mutation.value.recordKey })
        SyncDomain.COLLECTIONS -> copy(collections = collections.filterNot { it.profileId == mutation.value.profileId })
        SyncDomain.PROFILE_SETTINGS -> copy(profileSettings = profileSettings.filterNot { it.profileId == mutation.value.profileId })
        SyncDomain.HOME_CATALOG_SETTINGS -> copy(homeCatalogSettings = homeCatalogSettings.filterNot { it.profileId == mutation.value.profileId })
        SyncDomain.PROVIDER_CREDENTIALS -> copy(providerCredentials = providerCredentials.filterNot { it.profileId == mutation.value.profileId && it.providerId == mutation.value.recordKey })
    }
}

private data class PendingEntry(
    val id: String,
    val mutation: SyncMutation,
    val attempts: Int,
    val nextAttemptAtEpochMs: Long,
)

private fun SyncMutation.hasSameRecord(other: SyncMutation): Boolean =
    logicalSyncIdentity() != null && logicalSyncIdentity() == other.logicalSyncIdentity()

private fun SyncMutation.logicalSyncIdentity(): Triple<SyncDomain, Int?, String>? = when (this) {
    is SyncMutation.Profile -> Triple(SyncDomain.PROFILES, value.profileId, value.profileId.toString())
    is SyncMutation.Addon -> Triple(SyncDomain.ADDONS, value.profileId, value.url)
    is SyncMutation.Plugin -> Triple(SyncDomain.PLUGINS, value.profileId, value.url)
    is SyncMutation.LibraryItem -> Triple(SyncDomain.LIBRARY, value.profileId, value.contentId)
    is SyncMutation.Progress -> Triple(SyncDomain.WATCH_PROGRESS, value.profileId, value.progressKey)
    is SyncMutation.WatchedItem -> Triple(SyncDomain.WATCHED_ITEMS, value.profileId, value.contentId)
    is SyncMutation.Collection -> Triple(SyncDomain.COLLECTIONS, value.profileId, value.profileId.toString())
    is SyncMutation.ProfileSettings -> Triple(SyncDomain.PROFILE_SETTINGS, value.profileId, value.profileId.toString())
    is SyncMutation.HomeCatalogSettings -> Triple(SyncDomain.HOME_CATALOG_SETTINGS, value.profileId, value.profileId.toString())
    is SyncMutation.ProviderCredential -> Triple(SyncDomain.PROVIDER_CREDENTIALS, value.profileId, value.providerId)
    is SyncMutation.Delete -> Triple(value.domain, value.profileId, value.recordKey)
}

private data class StoredState(
    val snapshot: LocalSnapshot,
    val outbox: List<PendingEntry>,
) {
    companion object {
        val EMPTY = StoredState(LocalSnapshot(), emptyList())
    }
}

private object StorageCodec {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encode(state: StoredState): String = json.encodeToString(buildJsonObject {
        put("snapshot", snapshotObject(state.snapshot))
        put("outbox", buildJsonArray { state.outbox.forEach { add(entryObject(it)) } })
    })

    fun decode(payload: String): StoredState {
        val root = json.parseToJsonElement(payload).jsonObject
        val snapshot = root["snapshot"]?.jsonObject?.let(::decodeSnapshot) ?: LocalSnapshot()
        val entries = root["outbox"]?.jsonArray?.mapNotNull(::decodeEntry).orEmpty()
        return StoredState(snapshot, entries.distinctBy(PendingEntry::id))
    }

    private fun snapshotObject(snapshot: LocalSnapshot) = buildJsonObject {
        put("profiles", buildJsonArray { snapshot.profiles.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeProfile(it, ""))) } })
        put("addons", buildJsonArray { snapshot.addons.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeAddon(it, ""))) } })
        put("plugins", buildJsonArray { snapshot.plugins.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodePlugin(it, ""))) } })
        put("library", buildJsonArray { snapshot.library.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeLibraryItem(it, ""))) } })
        put("progress", buildJsonArray { snapshot.progress.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeProgress(it, ""))) } })
        put("watched", buildJsonArray { snapshot.watchedItems.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeWatchedItem(it, ""))) } })
        put("collections", buildJsonArray { snapshot.collections.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeCollection(it, ""))) } })
        put("settings", buildJsonArray { snapshot.profileSettings.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeProfileSettings(it, ""))) } })
        put("home_settings", buildJsonArray { snapshot.homeCatalogSettings.forEach { add(json.parseToJsonElement(SupabaseSyncJson.encodeHomeCatalogSettings(it, ""))) } })
        put("provider_credentials", buildJsonArray {
            snapshot.providerCredentials.forEach {
                add(json.parseToJsonElement(SupabaseSyncJson.encodeProviderCredential(it, "")))
            }
        })
    }

    private fun decodeSnapshot(root: JsonObject): LocalSnapshot? {
        fun records(name: String, decoder: (String) -> Any?): List<Any>? {
            val array = root[name]?.jsonArray ?: return emptyList()
            return array.map { decoder(it.toString()) ?: return null }
        }
        val profiles = records("profiles", SupabaseSyncJson::decodeProfile)?.filterIsInstance<CloudProfile>() ?: return null
        val addons = records("addons", SupabaseSyncJson::decodeAddon)?.filterIsInstance<CloudAddon>() ?: return null
        val plugins = records("plugins", SupabaseSyncJson::decodePlugin)?.filterIsInstance<CloudPlugin>() ?: return null
        val library = records("library", SupabaseSyncJson::decodeLibraryItem)?.filterIsInstance<CloudLibraryItem>() ?: return null
        val progress = records("progress", SupabaseSyncJson::decodeProgress)?.filterIsInstance<CloudWatchProgress>() ?: return null
        val watched = records("watched", SupabaseSyncJson::decodeWatchedItem)?.filterIsInstance<CloudWatchedItem>() ?: return null
        val collections = records("collections", SupabaseSyncJson::decodeCollection)?.filterIsInstance<CloudCollection>() ?: return null
        val settings = records("settings", SupabaseSyncJson::decodeProfileSettings)?.filterIsInstance<CloudProfileSettings>() ?: return null
        val homeSettings = records("home_settings", SupabaseSyncJson::decodeHomeCatalogSettings)
            ?.filterIsInstance<CloudHomeCatalogSettings>() ?: return null
        val providerCredentials = records("provider_credentials", SupabaseSyncJson::decodeProviderCredential)
            ?.filterIsInstance<ProviderCredentialRecord>() ?: return null
        return LocalSnapshot(
            profiles = profiles,
            library = library,
            progress = progress,
            watchedItems = watched,
            profileSettings = settings,
            addons = addons,
            plugins = plugins,
            collections = collections,
            homeCatalogSettings = homeSettings,
            providerCredentials = providerCredentials,
        )
    }

    private fun entryObject(entry: PendingEntry) = buildJsonObject {
        put("id", entry.id)
        put("attempts", entry.attempts)
        put("next_attempt_at", entry.nextAttemptAtEpochMs)
        put("mutation", mutationObject(entry.mutation))
    }

    private fun mutationObject(mutation: SyncMutation) = buildJsonObject {
        when (mutation) {
            is SyncMutation.Profile -> { put("kind", "profile"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeProfile(mutation.value, ""))) }
            is SyncMutation.Addon -> { put("kind", "addon"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeAddon(mutation.value, ""))) }
            is SyncMutation.Plugin -> { put("kind", "plugin"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodePlugin(mutation.value, ""))) }
            is SyncMutation.LibraryItem -> { put("kind", "library"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeLibraryItem(mutation.value, ""))) }
            is SyncMutation.Progress -> { put("kind", "progress"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeProgress(mutation.value, ""))) }
            is SyncMutation.WatchedItem -> { put("kind", "watched"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeWatchedItem(mutation.value, ""))) }
            is SyncMutation.Collection -> { put("kind", "collection"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeCollection(mutation.value, ""))) }
            is SyncMutation.ProfileSettings -> { put("kind", "settings"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeProfileSettings(mutation.value, ""))) }
            is SyncMutation.HomeCatalogSettings -> { put("kind", "home-settings"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeHomeCatalogSettings(mutation.value, ""))) }
            is SyncMutation.ProviderCredential -> { put("kind", "credential"); put("value", json.parseToJsonElement(SupabaseSyncJson.encodeProviderCredential(mutation.value, ""))) }
            is SyncMutation.Delete -> { put("kind", "delete"); put("value", envelopeObject(mutation.value)) }
        }
    }

    private fun envelopeObject(envelope: com.sluggyard.tv.core.sync.model.SyncMutationEnvelope) = buildJsonObject {
        put("mutation_id", envelope.mutationId)
        put("owner_user_id", envelope.ownerUserId)
        put("domain", envelope.domain.name)
        envelope.profileId?.let { put("profile_id", it) } ?: put("profile_id", JsonNull)
        put("record_key", envelope.recordKey)
        put("operation", envelope.operation.name)
        put("client_changed_at", envelope.clientChangedAtEpochMs)
        put("schema_version", envelope.schemaVersion)
        envelope.payloadJson?.let { put("payload_json", json.parseToJsonElement(it)) } ?: put("payload_json", JsonNull)
    }

    private fun decodeEntry(element: kotlinx.serialization.json.JsonElement): PendingEntry? = runCatching<PendingEntry?> {
        val root = element.jsonObject
        val mutationRoot = root["mutation"]!!.jsonObject
        val value = mutationRoot["value"]!!.toString()
        val mutation = when (mutationRoot["kind"]!!.jsonPrimitive.content) {
            "profile" -> SupabaseSyncJson.decodeProfile(value)?.let(SyncMutation::Profile)
            "addon" -> SupabaseSyncJson.decodeAddon(value)?.let(SyncMutation::Addon)
            "plugin" -> SupabaseSyncJson.decodePlugin(value)?.let(SyncMutation::Plugin)
            "library" -> SupabaseSyncJson.decodeLibraryItem(value)?.let(SyncMutation::LibraryItem)
            "progress" -> SupabaseSyncJson.decodeProgress(value)?.let(SyncMutation::Progress)
            "watched" -> SupabaseSyncJson.decodeWatchedItem(value)?.let(SyncMutation::WatchedItem)
            "collection" -> SupabaseSyncJson.decodeCollection(value)?.let(SyncMutation::Collection)
            "settings" -> SupabaseSyncJson.decodeProfileSettings(value)?.let(SyncMutation::ProfileSettings)
            "home-settings" -> SupabaseSyncJson.decodeHomeCatalogSettings(value)?.let(SyncMutation::HomeCatalogSettings)
            "credential" -> SupabaseSyncJson.decodeProviderCredential(value)?.let(SyncMutation::ProviderCredential)
            "delete" -> decodeEnvelope(mutationRoot["value"]?.jsonObject)?.let(SyncMutation::Delete)
            else -> null
        } ?: return@runCatching null
        val id = root["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val attempts = root["attempts"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
        val nextAttempt = root["next_attempt_at"]?.jsonPrimitive?.longOrNull ?: return@runCatching null
        PendingEntry(id, mutation, attempts, nextAttempt)
    }.getOrNull()

    private fun decodeEnvelope(root: JsonObject?): com.sluggyard.tv.core.sync.model.SyncMutationEnvelope? =
        root?.let { value -> runCatching {
            com.sluggyard.tv.core.sync.model.SyncMutationEnvelope(
                mutationId = value["mutation_id"]!!.jsonPrimitive.content,
                ownerUserId = value["owner_user_id"]!!.jsonPrimitive.content,
                domain = SyncDomain.valueOf(value["domain"]!!.jsonPrimitive.content),
                profileId = value["profile_id"]?.jsonPrimitive?.intOrNull,
                recordKey = value["record_key"]!!.jsonPrimitive.content,
                operation = com.sluggyard.tv.core.sync.model.SyncOperation.valueOf(value["operation"]!!.jsonPrimitive.content),
                clientChangedAtEpochMs = value["client_changed_at"]!!.jsonPrimitive.longOrNull!!,
                schemaVersion = value["schema_version"]!!.jsonPrimitive.intOrNull!!,
                payloadJson = value["payload_json"]?.takeIf { it !is JsonNull }?.toString(),
            )
        }.getOrNull() }
}

private fun RemoteSnapshot.toLocalSnapshot() = LocalSnapshot(
    profiles = profiles,
    library = library,
    progress = progress,
    watchedItems = watchedItems,
    profileSettings = profileSettings,
    addons = addons,
    plugins = plugins,
    collections = collections,
    homeCatalogSettings = homeCatalogSettings,
    providerCredentials = providerCredentials,
)
