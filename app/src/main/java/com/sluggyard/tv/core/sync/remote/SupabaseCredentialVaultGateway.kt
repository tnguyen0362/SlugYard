package com.sluggyard.tv.core.sync.remote

import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSession
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.model.SyncMutationEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface SupabaseCredentialVaultGateway {
    suspend fun pull(ownerUserId: String, cursor: String?): SyncResult<SupabasePage>

    suspend fun apply(mutation: SyncMutationEnvelope): SyncResult<MutationDisposition>
}

class DefaultSupabaseCredentialVaultGateway(
    private val transport: SupabaseHttpTransport,
    private val sessions: SupabaseSessionStore,
    private val auth: SupabaseAuthGateway,
) : SupabaseCredentialVaultGateway {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun pull(ownerUserId: String, cursor: String?): SyncResult<SupabasePage> =
        withSession(ownerUserId) { session ->
            val path = buildString {
                append("/functions/v1/provider-credentials?limit=100")
                cursor?.takeIf(String::isNotBlank)?.let { append("&cursor=${encode(it)}") }
            }
            when (val response = requestWithRefresh(session) { active ->
                transport.execute(path = path, method = "GET", accessToken = active.accessToken)
            }) {
                is SyncResult.Success -> when (val page = SupabasePage.tryFromResponse(SyncDomain.PROVIDER_CREDENTIALS, response.value)) {
                    is PageResult.Success -> SyncResult.Success(page.page)
                    is PageResult.Failure -> if (page.kind == SyncFailureKind.Unauthorized) {
                        SyncResult.SessionExpired
                    } else {
                        SyncResult.Failure(page.kind)
                    }
                }
                is SyncResult.Failure -> response
                SyncResult.SessionExpired -> SyncResult.SessionExpired
            }
        }

    override suspend fun apply(mutation: SyncMutationEnvelope): SyncResult<MutationDisposition> {
        require(mutation.domain == SyncDomain.PROVIDER_CREDENTIALS) {
            "Credential vault requires a provider credential mutation"
        }
        return currentSession { session ->
            if (mutation.ownerUserId != session.userId) {
                return@currentSession SyncResult.Failure(SyncFailureKind.Forbidden)
            }
            val body = buildJsonObject {
                put("operation", mutation.operation.name)
                put("record_key", mutation.recordKey)
                put("client_changed_at", mutation.clientChangedAtEpochMs)
                put("mutation_id", mutation.mutationId)
                put("schema_version", mutation.schemaVersion)
                mutation.payloadJson?.let { put("payload", json.parseToJsonElement(it)) }
            }.toString()
            when (val response = requestWithRefresh(session) { active ->
                transport.execute(
                    path = "/functions/v1/provider-credentials",
                    method = "POST",
                    body = body,
                    accessToken = active.accessToken,
                )
            }) {
                is SyncResult.Success -> {
                    val value = response.value
                    if (!value.isSuccessful) {
                        if (value.code == 401) SyncResult.SessionExpired
                        else SyncResult.Failure(value.failureKind())
                    } else {
                        val bodyText = value.body ?: return@currentSession SyncResult.Failure(SyncFailureKind.Decode)
                        runCatching { SyncResult.Success(MutationDisposition.fromJson(bodyText)) }
                            .getOrElse { SyncResult.Failure(SyncFailureKind.Decode) }
                    }
                }
                is SyncResult.Failure -> response
                SyncResult.SessionExpired -> SyncResult.SessionExpired
            }
        }
    }

    private suspend fun <T> withSession(
        ownerUserId: String,
        block: suspend (SupabaseSession) -> SyncResult<T>,
    ): SyncResult<T> = currentSession { session ->
        if (session.userId != ownerUserId) SyncResult.Failure(SyncFailureKind.Forbidden) else block(session)
    }

    private suspend fun <T> currentSession(
        block: suspend (SupabaseSession) -> SyncResult<T>,
    ): SyncResult<T> {
        val session = (sessions.read() as? SupabaseSessionState.Active)?.session
            ?: return SyncResult.Failure(SyncFailureKind.Unauthorized)
        return block(session)
    }

    private suspend fun requestWithRefresh(
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

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
