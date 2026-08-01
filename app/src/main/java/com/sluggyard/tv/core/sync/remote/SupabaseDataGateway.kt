package com.sluggyard.tv.core.sync.remote

import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSession
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.adapter.SyncDomainAdapters
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.model.SyncMutationEnvelope
import com.sluggyard.tv.core.sync.model.SyncOperation
import com.sluggyard.tv.core.sync.model.SyncSchemaVersion
import com.sluggyard.tv.core.sync.model.CloudLibraryItem
import com.sluggyard.tv.core.sync.model.CloudProfile
import com.sluggyard.tv.core.sync.model.CloudProfileSettings
import com.sluggyard.tv.core.sync.model.CloudWatchProgress
import com.sluggyard.tv.core.sync.model.CloudWatchedItem
import com.sluggyard.tv.core.sync.model.SupabaseSyncJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RemoteSnapshot(
    val profiles: List<CloudProfile>,
    val library: List<CloudLibraryItem>,
    val progress: List<CloudWatchProgress>,
    val watchedItems: List<CloudWatchedItem>,
    val profileSettings: List<CloudProfileSettings>,
    val addons: List<com.sluggyard.tv.core.sync.model.CloudAddon> = emptyList(),
    val plugins: List<com.sluggyard.tv.core.sync.model.CloudPlugin> = emptyList(),
    val collections: List<com.sluggyard.tv.core.sync.model.CloudCollection> = emptyList(),
    val homeCatalogSettings: List<com.sluggyard.tv.core.sync.model.CloudHomeCatalogSettings> = emptyList(),
    val providerCredentials: List<com.sluggyard.tv.core.sync.model.ProviderCredentialRecord> = emptyList(),
)

sealed interface SyncMutation {
    data class Profile(val value: CloudProfile) : SyncMutation
    data class Addon(val value: com.sluggyard.tv.core.sync.model.CloudAddon) : SyncMutation
    data class Plugin(val value: com.sluggyard.tv.core.sync.model.CloudPlugin) : SyncMutation
    data class LibraryItem(val value: CloudLibraryItem) : SyncMutation
    data class Progress(val value: CloudWatchProgress) : SyncMutation
    data class WatchedItem(val value: CloudWatchedItem) : SyncMutation
    data class Collection(val value: com.sluggyard.tv.core.sync.model.CloudCollection) : SyncMutation
    data class ProfileSettings(val value: CloudProfileSettings) : SyncMutation
    data class HomeCatalogSettings(val value: com.sluggyard.tv.core.sync.model.CloudHomeCatalogSettings) : SyncMutation
    data class ProviderCredential(val value: com.sluggyard.tv.core.sync.model.ProviderCredentialRecord) : SyncMutation
    data class Delete(val value: SyncMutationEnvelope) : SyncMutation {
        init {
            require(value.operation == SyncOperation.DELETE) { "Delete mutation must use DELETE operation" }
        }
    }
}

data class DeleteMutation(
    val table: SyncTable,
    val profileId: Int,
    val stableId: String,
    val clientChangedAtEpochMs: Long = System.currentTimeMillis(),
)

enum class SyncTable(
    val tableName: String,
    val identityColumn: String,
) {
    PROFILES("profiles", "profile_index"),
    LIBRARY("library", "content_id"),
    WATCH_PROGRESS("watch_progress", "progress_key"),
    WATCHED_ITEMS("watched_items", "content_id"),
    PROFILE_SETTINGS("profile_settings", "profile_id"),
    ADDONS("addons", "url"),
    PLUGINS("plugins", "url"),
    COLLECTIONS("collections", "profile_id"),
    HOME_CATALOG_SETTINGS("home_catalog_settings", "profile_id"),
    PROVIDER_CREDENTIALS("provider_credentials", "provider"),

    ;

    companion object {
        fun fromDomain(domain: SyncDomain): SyncTable? = when (domain) {
            SyncDomain.PROFILES -> PROFILES
            SyncDomain.ADDONS -> ADDONS
            SyncDomain.PLUGINS -> PLUGINS
            SyncDomain.LIBRARY -> LIBRARY
            SyncDomain.WATCH_PROGRESS -> WATCH_PROGRESS
            SyncDomain.WATCHED_ITEMS -> WATCHED_ITEMS
            SyncDomain.COLLECTIONS -> COLLECTIONS
            SyncDomain.PROFILE_SETTINGS -> PROFILE_SETTINGS
            SyncDomain.HOME_CATALOG_SETTINGS -> HOME_CATALOG_SETTINGS
            SyncDomain.PROVIDER_CREDENTIALS -> PROVIDER_CREDENTIALS
        }
    }

    val domain: SyncDomain
        get() = when (this) {
            PROFILES -> SyncDomain.PROFILES
            ADDONS -> SyncDomain.ADDONS
            PLUGINS -> SyncDomain.PLUGINS
            LIBRARY -> SyncDomain.LIBRARY
            WATCH_PROGRESS -> SyncDomain.WATCH_PROGRESS
            WATCHED_ITEMS -> SyncDomain.WATCHED_ITEMS
            COLLECTIONS -> SyncDomain.COLLECTIONS
            PROFILE_SETTINGS -> SyncDomain.PROFILE_SETTINGS
            HOME_CATALOG_SETTINGS -> SyncDomain.HOME_CATALOG_SETTINGS
            PROVIDER_CREDENTIALS -> SyncDomain.PROVIDER_CREDENTIALS
        }
}

interface SupabaseDataGateway {
    suspend fun pull(userId: String): SyncResult<RemoteSnapshot>

    suspend fun upsert(mutation: SyncMutation): SyncResult<Unit>

    suspend fun delete(mutation: DeleteMutation): SyncResult<Unit>

    suspend fun pullPage(
        domain: SyncDomain,
        ownerUserId: String,
        cursor: String?,
    ): SyncResult<SupabasePage> = SyncResult.Failure(SyncFailureKind.Configuration)

    suspend fun pullEvents(
        domain: SyncDomain,
        ownerUserId: String,
        afterCursor: Long,
    ): SyncResult<EventPage> = SyncResult.Failure(SyncFailureKind.Configuration)

    suspend fun applyMutation(mutation: SyncMutationEnvelope): SyncResult<MutationDisposition> =
        SyncResult.Failure(SyncFailureKind.Configuration)

    suspend fun delete(mutation: SyncMutationEnvelope): SyncResult<MutationDisposition> =
        SyncResult.Failure(SyncFailureKind.Configuration)
}

class DefaultSupabaseDataGateway(
    private val transport: SupabaseHttpTransport,
    private val sessions: SupabaseSessionStore,
    private val auth: SupabaseAuthGateway,
    private val credentialVault: SupabaseCredentialVaultGateway? = null,
) : SupabaseDataGateway {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun pullPage(
        domain: SyncDomain,
        ownerUserId: String,
        cursor: String?,
    ): SyncResult<SupabasePage> {
        if (domain == SyncDomain.PROVIDER_CREDENTIALS) {
            return credentialVault?.pull(ownerUserId, cursor)
                ?: SyncResult.Failure(SyncFailureKind.Configuration)
        }
        return withSession(ownerUserId) { session ->
            val table = SyncTable.fromDomain(domain)
                ?: return@withSession SyncResult.Failure(SyncFailureKind.Configuration)
            val offset = when {
                cursor == null -> 0
                else -> cursor.toIntOrNull()?.takeIf { it >= 0 }
                    ?: return@withSession SyncResult.Failure(SyncFailureKind.Configuration)
            }
            val path = buildString {
                append("/rest/v1/${table.tableName}?select=*&user_id=eq.")
                append(encode(ownerUserId))
                append("&order=${table.identityColumn}.asc&limit=$PAGE_SIZE&offset=$offset")
            }
            when (val response = requestWithRefreshResult(session) { active ->
                transport.execute(path = path, method = "GET", accessToken = active.accessToken)
            }) {
                is SyncResult.Success -> pageResult(domain, response.value)
                is SyncResult.Failure -> response
                SyncResult.SessionExpired -> SyncResult.SessionExpired
            }
        }
    }

    override suspend fun pullEvents(
        domain: SyncDomain,
        ownerUserId: String,
        afterCursor: Long,
    ): SyncResult<EventPage> {
        if (afterCursor < 0) return SyncResult.Failure(SyncFailureKind.Configuration)
        return withSession(ownerUserId) { session ->
            val table = when (domain) {
                SyncDomain.WATCH_PROGRESS -> "watch_progress_events"
                SyncDomain.WATCHED_ITEMS -> "watched_items_events"
                else -> return@withSession SyncResult.Failure(SyncFailureKind.Configuration)
            }
            val path = "/rest/v1/$table?select=*&user_id=eq.${encode(ownerUserId)}&event_id=gt.$afterCursor&order=event_id.asc&limit=$PAGE_SIZE"
            when (val response = requestWithRefreshResult(session) { active ->
                transport.execute(path = path, method = "GET", accessToken = active.accessToken)
            }) {
                is SyncResult.Success -> {
                    val body = response.value.body ?: return@withSession SyncResult.Failure(SyncFailureKind.Decode)
                    val events = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull()
                        ?: return@withSession SyncResult.Failure(SyncFailureKind.Decode)
                    val nextCursor = (response.value.headers["x-next-cursor"]?.toLongOrNull()
                        ?: events.lastOrNull()?.jsonObject?.get("event_id")?.jsonPrimitive?.longOrNull
                        )?.takeIf { it > afterCursor }
                    SyncResult.Success(EventPage(domain, events, nextCursor))
                }
                is SyncResult.Failure -> response
                SyncResult.SessionExpired -> SyncResult.SessionExpired
            }
        }
    }

    override suspend fun applyMutation(
        mutation: SyncMutationEnvelope,
    ): SyncResult<MutationDisposition> {
        if (mutation.ownerUserId.isBlank()) return SyncResult.Failure(SyncFailureKind.InvalidInput)
        return currentSession { session ->
            if (mutation.ownerUserId != session.userId) {
                return@currentSession SyncResult.Failure(SyncFailureKind.Forbidden)
            }
            if (mutation.domain == SyncDomain.PROVIDER_CREDENTIALS) {
                return@currentSession credentialVault?.apply(mutation)
                    ?: SyncResult.Failure(SyncFailureKind.Configuration)
            }
            val body = buildJsonObject {
                put("p_domain", mutation.domain.name)
                if (mutation.profileId != null) put("p_profile_id", mutation.profileId) else put("p_profile_id", JsonNull)
                put("p_record_key", mutation.recordKey)
                put("p_operation", mutation.operation.name)
                put("p_client_changed_at", mutation.clientChangedAtEpochMs)
                put("p_mutation_id", mutation.mutationId)
                put("p_schema_version", mutation.schemaVersion)
                if (mutation.payloadJson != null) {
                    put("p_payload_json", json.parseToJsonElement(mutation.payloadJson))
                } else {
                    put("p_payload_json", JsonNull)
                }
            }.toString()
            val path = "/rest/v1/rpc/apply_sync_mutation"
            when (val response = requestWithRefreshResult(session) { active ->
                transport.execute(path = path, method = "POST", body = body, accessToken = active.accessToken)
            }) {
                is SyncResult.Success -> mutationResult(response.value)
                is SyncResult.Failure -> response
                SyncResult.SessionExpired -> SyncResult.SessionExpired
            }
        }
    }

    override suspend fun delete(mutation: SyncMutationEnvelope): SyncResult<MutationDisposition> {
        require(mutation.operation == SyncOperation.DELETE) { "Delete gateway requires a delete mutation" }
        return applyMutation(mutation)
    }

    override suspend fun pull(userId: String): SyncResult<RemoteSnapshot> = withSession(userId) { session ->
        val profiles = when (val result = get(SyncTable.PROFILES, userId, session, SupabaseSyncJson::decodeProfiles)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val library = when (val result = get(SyncTable.LIBRARY, userId, session, SupabaseSyncJson::decodeLibraryItems)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val progress = when (val result = get(SyncTable.WATCH_PROGRESS, userId, session, SupabaseSyncJson::decodeProgressItems)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val watched = when (val result = get(SyncTable.WATCHED_ITEMS, userId, session, SupabaseSyncJson::decodeWatchedItems)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val settings = when (val result = get(
            SyncTable.PROFILE_SETTINGS,
            userId,
            session,
            SupabaseSyncJson::decodeProfileSettingsItems,
        )) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val addons = when (val result = get(SyncTable.ADDONS, userId, session, SupabaseSyncJson::decodeAddons)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val plugins = when (val result = get(SyncTable.PLUGINS, userId, session, SupabaseSyncJson::decodePlugins)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val collections = when (val result = get(SyncTable.COLLECTIONS, userId, session, SupabaseSyncJson::decodeCollections)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val homeCatalogSettings = when (val result = get(
            SyncTable.HOME_CATALOG_SETTINGS,
            userId,
            session,
            SupabaseSyncJson::decodeHomeCatalogSettingsItems,
        )) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        val providerCredentials = when (val result = getProviderCredentials(userId)) {
            is PullValue.Rows -> result.value
            is PullValue.Failed -> return@withSession result.result
        }
        SyncResult.Success(
            RemoteSnapshot(
                profiles = profiles,
                library = library,
                progress = progress,
                watchedItems = watched,
                profileSettings = settings,
                addons = addons,
                plugins = plugins,
                collections = collections,
                homeCatalogSettings = homeCatalogSettings,
                providerCredentials = providerCredentials,
            ),
        )
    }

    override suspend fun upsert(mutation: SyncMutation): SyncResult<Unit> = currentSession { session ->
        val userId = session.userId
        val envelope = mutation.toEnvelope(userId)
        when (val result = applyMutation(envelope)) {
            is SyncResult.Success -> when (result.value) {
                is MutationDisposition.Accepted,
                MutationDisposition.Stale,
                -> SyncResult.Success(Unit)
                MutationDisposition.Conflict -> SyncResult.Failure(SyncFailureKind.Conflict)
            }
            is SyncResult.Failure -> result
            SyncResult.SessionExpired -> SyncResult.SessionExpired
        }
    }

    override suspend fun delete(mutation: DeleteMutation): SyncResult<Unit> = currentSession { session ->
        require(mutation.profileId > 0) { "Profile identity must be positive" }
        val domain = mutation.table.domain
        val envelope = SyncMutationEnvelope.create(
            ownerUserId = session.userId,
            domain = domain,
            profileId = mutation.profileId,
            recordKey = mutation.stableId,
            operation = SyncOperation.DELETE,
            clientChangedAtEpochMs = mutation.clientChangedAtEpochMs,
            payloadJson = null,
        )
        when (val result = delete(envelope)) {
            is SyncResult.Success -> when (result.value) {
                is MutationDisposition.Accepted,
                MutationDisposition.Stale,
                -> SyncResult.Success(Unit)
                MutationDisposition.Conflict -> SyncResult.Failure(SyncFailureKind.Conflict)
            }
            is SyncResult.Failure -> result
            SyncResult.SessionExpired -> SyncResult.SessionExpired
        }
    }

    private suspend fun <T> get(
        table: SyncTable,
        userId: String,
        session: SupabaseSession,
        decoder: (String) -> List<T>?,
    ): PullValue<T> {
        val records = mutableListOf<T>()
        var offset = 0
        repeat(MAX_PAGES) {
            val path = "/rest/v1/${table.tableName}?select=*&user_id=eq.${encode(userId)}" +
                "&order=${table.identityColumn}.asc&limit=$PAGE_SIZE&offset=$offset"
            when (val response = requestWithRefreshResult(session) { active ->
                transport.execute(path = path, method = "GET", accessToken = active.accessToken)
            }) {
                is SyncResult.Success -> {
                    val page = response.value
                    if (!page.isSuccessful) return PullValue.Failed(page.toFailureResult())
                    val body = page.body ?: return PullValue.Failed(SyncResult.Failure(SyncFailureKind.Decode))
                    val decoded = decoder(body)
                        ?: return PullValue.Failed(SyncResult.Failure(SyncFailureKind.Decode))
                    records += decoded
                    val next = SupabasePage.tryFromResponse(table.domain, page).nextOffsetAfter(offset)
                        ?: (offset + PAGE_SIZE).takeIf { decoded.size >= PAGE_SIZE }
                    if (next == null) return PullValue.Rows(records)
                    offset = next
                }
                is SyncResult.Failure -> return PullValue.Failed(response)
                SyncResult.SessionExpired -> return PullValue.Failed(SyncResult.SessionExpired)
            }
        }
        return PullValue.Failed(SyncResult.Failure(SyncFailureKind.Server))
    }

    private suspend fun getProviderCredentials(userId: String): PullValue<com.sluggyard.tv.core.sync.model.ProviderCredentialRecord> {
        val vault = credentialVault ?: return PullValue.Failed(SyncResult.Failure(SyncFailureKind.Configuration))
        val records = mutableListOf<com.sluggyard.tv.core.sync.model.ProviderCredentialRecord>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            when (val result = vault.pull(userId, cursor)) {
                is SyncResult.Success -> {
                    val page = result.value
                    val decoded = page.records.mapNotNull { SupabaseSyncJson.decodeProviderCredential(it.toString()) }
                    if (decoded.size != page.records.size) {
                        return PullValue.Failed(SyncResult.Failure(SyncFailureKind.Decode))
                    }
                    records += decoded
                    val next = page.nextCursor ?: return PullValue.Rows(records)
                    if (next == cursor) return PullValue.Failed(SyncResult.Failure(SyncFailureKind.Decode))
                    cursor = next
                }
                is SyncResult.Failure -> return PullValue.Failed(result)
                SyncResult.SessionExpired -> return PullValue.Failed(SyncResult.SessionExpired)
            }
        }
        return PullValue.Failed(SyncResult.Failure(SyncFailureKind.Server))
    }

    private suspend fun <T> withSession(
        userId: String,
        block: suspend (SupabaseSession) -> SyncResult<T>,
    ): SyncResult<T> = currentSession { session ->
        if (session.userId != userId) return@currentSession SyncResult.Failure(SyncFailureKind.Forbidden)
        block(session)
    }

    private suspend fun <T> currentSession(
        block: suspend (SupabaseSession) -> SyncResult<T>,
    ): SyncResult<T> {
        val session = (sessions.read() as? SupabaseSessionState.Active)?.session
            ?: return SyncResult.Failure(SyncFailureKind.Unauthorized)
        return block(session)
    }

    private fun pageResult(
        domain: SyncDomain,
        response: SupabaseHttpResponse,
    ): SyncResult<SupabasePage> = when (val result = SupabasePage.tryFromResponse(domain, response)) {
        is PageResult.Success -> if (result.page.records.all { record ->
            runCatching {
                SyncDomainAdapters.forDomain(domain).recordKey(record.jsonObject)?.isNotBlank() == true
            }.getOrDefault(false)
        }) {
            SyncResult.Success(result.page)
        } else {
            SyncResult.Failure(SyncFailureKind.Decode)
        }
        is PageResult.Failure -> if (response.code == 401) {
            SyncResult.SessionExpired
        } else {
            SyncResult.Failure(result.kind)
        }
    }

    private fun mutationResult(response: SupabaseHttpResponse): SyncResult<MutationDisposition> {
        if (!response.isSuccessful) {
            return if (response.code == 401) {
                SyncResult.SessionExpired
            } else {
                SyncResult.Failure(response.failureKind())
            }
        }
        val body = response.body ?: return SyncResult.Failure(SyncFailureKind.Decode)
        return runCatching { SyncResult.Success(MutationDisposition.fromJson(body)) }
            .getOrElse { SyncResult.Failure(SyncFailureKind.Decode) }
    }

    private suspend fun requestWithRefreshResult(
        session: SupabaseSession,
        request: suspend (SupabaseSession) -> SupabaseHttpResponse,
    ): SyncResult<SupabaseHttpResponse> {
        val first = request(session)
        if (first.code != 401) return SyncResult.Success(first)
        return when (val refreshed = auth.refresh()) {
            is SyncResult.Success -> {
                val second = request(refreshed.value)
                if (second.code == 401) SyncResult.SessionExpired else SyncResult.Success(second)
            }
            is SyncResult.Failure -> if (refreshed.kind == SyncFailureKind.Unauthorized) {
                SyncResult.SessionExpired
            } else {
                SyncResult.Failure(refreshed.kind)
            }
            SyncResult.SessionExpired -> SyncResult.SessionExpired
        }
    }

    private fun SupabaseHttpResponse.toFailureResult(): SyncResult<Nothing> = when {
        code == 401 -> SyncResult.SessionExpired
        else -> SyncResult.Failure(
            when (code) {
                0 -> SyncFailureKind.Network
                403 -> SyncFailureKind.Forbidden
                409 -> SyncFailureKind.Conflict
                429 -> SyncFailureKind.RateLimited
                in 500..599 -> SyncFailureKind.Server
                else -> SyncFailureKind.Decode
            },
        )
    }

    private sealed interface PullValue<out T> {
        data class Rows<T>(val value: List<T>) : PullValue<T>
        data class Failed(val result: SyncResult<Nothing>) : PullValue<Nothing>
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100
    }
}

private fun PageResult.nextOffsetAfter(currentOffset: Int): Int? = when (this) {
    is PageResult.Failure -> null
    is PageResult.Success -> page.nextCursor
        ?.toIntOrNull()
        ?.takeIf { it > currentOffset }
}

private fun SyncMutation.toEnvelope(ownerUserId: String): SyncMutationEnvelope = when (this) {
    is SyncMutation.Delete -> value
    is SyncMutation.Profile -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.PROFILES,
        profileId = value.profileId,
        recordKey = value.profileId.toString(),
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeProfile(value, ownerUserId),
    )
    is SyncMutation.Addon -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.ADDONS,
        profileId = value.profileId,
        recordKey = value.url,
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeAddon(value, ownerUserId),
    )
    is SyncMutation.Plugin -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.PLUGINS,
        profileId = value.profileId,
        recordKey = value.url,
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodePlugin(value, ownerUserId),
    )
    is SyncMutation.LibraryItem -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.LIBRARY,
        profileId = value.profileId,
        recordKey = value.contentId,
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeLibraryItem(value, ownerUserId),
    )
    is SyncMutation.Progress -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.WATCH_PROGRESS,
        profileId = value.profileId,
        recordKey = value.progressKey,
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeProgress(value, ownerUserId),
    )
    is SyncMutation.WatchedItem -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.WATCHED_ITEMS,
        profileId = value.profileId,
        recordKey = value.contentId,
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeWatchedItem(value, ownerUserId),
    )
    is SyncMutation.Collection -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.COLLECTIONS,
        profileId = value.profileId,
        recordKey = value.profileId.toString(),
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeCollection(value, ownerUserId),
    )
    is SyncMutation.ProfileSettings -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.PROFILE_SETTINGS,
        profileId = value.profileId,
        recordKey = value.profileId.toString(),
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeProfileSettings(value, ownerUserId),
    )
    is SyncMutation.HomeCatalogSettings -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.HOME_CATALOG_SETTINGS,
        profileId = value.profileId,
        recordKey = value.profileId.toString(),
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAt,
        payloadJson = SupabaseSyncJson.encodeHomeCatalogSettings(value, ownerUserId),
    )
    is SyncMutation.ProviderCredential -> SyncMutationEnvelope.create(
        ownerUserId = ownerUserId,
        domain = SyncDomain.PROVIDER_CREDENTIALS,
        profileId = value.profileId,
        recordKey = value.providerId,
        operation = SyncOperation.UPSERT,
        clientChangedAtEpochMs = value.changedAtEpochMs,
        payloadJson = SupabaseSyncJson.encodeProviderCredential(value, ownerUserId),
    )
}

internal fun SupabaseHttpResponse.failureKind(): SyncFailureKind = when (code) {
    0 -> SyncFailureKind.Network
    401 -> SyncFailureKind.Unauthorized
    403 -> SyncFailureKind.Forbidden
    409 -> SyncFailureKind.Conflict
    429 -> SyncFailureKind.RateLimited
    in 500..599 -> SyncFailureKind.Server
    else -> SyncFailureKind.Decode
}
