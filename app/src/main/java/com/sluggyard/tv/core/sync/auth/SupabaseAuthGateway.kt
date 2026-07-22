package com.sluggyard.tv.core.sync.auth

import com.sluggyard.tv.core.sync.remote.SupabaseHttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface SyncResult<out T> {
    data class Success<T>(val value: T) : SyncResult<T>
    data class Failure(val kind: SyncFailureKind) : SyncResult<Nothing>
    data object SessionExpired : SyncResult<Nothing>
}

enum class SyncFailureKind {
    InvalidInput,
    Configuration,
    Network,
    Unauthorized,
    Forbidden,
    RateLimited,
    Server,
    Decode,
    Conflict,
}

interface SupabaseAuthGateway {
    suspend fun signIn(email: String, password: String): SyncResult<SupabaseSession>

    suspend fun signUp(email: String, password: String): SyncResult<SupabaseSession>

    suspend fun refresh(): SyncResult<SupabaseSession>

    suspend fun currentUser(): SyncResult<String>

    suspend fun signOut(): SyncResult<Unit>
}

class DefaultSupabaseAuthGateway(
    private val transport: SupabaseHttpTransport,
    private val sessions: SupabaseSessionStore,
) : SupabaseAuthGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private var refreshOperation: RefreshOperation? = null

    override suspend fun signIn(email: String, password: String): SyncResult<SupabaseSession> {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            return SyncResult.Failure(SyncFailureKind.InvalidInput)
        }
        val response = transport.execute(
            path = "/auth/v1/token?grant_type=password",
            method = "POST",
            body = "{\"email\":${jsonString(normalizedEmail)},\"password\":${jsonString(password)}}",
        )
        return persistSession(response)
    }

    override suspend fun signUp(email: String, password: String): SyncResult<SupabaseSession> {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            return SyncResult.Failure(SyncFailureKind.InvalidInput)
        }
        val response = transport.execute(
            path = "/auth/v1/signup",
            method = "POST",
            body = "{\"email\":${jsonString(normalizedEmail)},\"password\":${jsonString(password)}}",
        )
        return persistSession(response)
    }

    override suspend fun refresh(): SyncResult<SupabaseSession> {
        val observedSession = (sessions.read() as? SupabaseSessionState.Active)?.session
            ?: return SyncResult.Failure(SyncFailureKind.Unauthorized)
        val (operation, isLeader) = refreshMutex.withLock {
            val existing = refreshOperation
            if (existing != null && existing.session == observedSession && existing.result.isActive) {
                existing to false
            } else {
                val created = RefreshOperation(
                    session = observedSession,
                    result = CompletableDeferred(),
                )
                refreshOperation = created
                created to true
            }
        }
        if (!isLeader) return operation.result.await()

        return try {
            val session = (sessions.read() as? SupabaseSessionState.Active)?.session
            val result = if (session == null) {
                SyncResult.Failure(SyncFailureKind.Unauthorized)
            } else if (session != observedSession) {
                SyncResult.Success(session)
            } else {
                val response = transport.execute(
                    path = "/auth/v1/token?grant_type=refresh_token",
                    method = "POST",
                    body = "{\"refresh_token\":${jsonString(session.refreshToken)}}",
                )
                persistSession(response, clearOnUnauthorized = true)
            }
            operation.result.complete(result)
            result
        } catch (cancelled: CancellationException) {
            operation.result.cancel(cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            operation.result.completeExceptionally(failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                refreshMutex.withLock {
                    if (refreshOperation === operation) refreshOperation = null
                }
            }
        }
    }

    override suspend fun currentUser(): SyncResult<String> {
        val session = (sessions.read() as? SupabaseSessionState.Active)?.session
            ?: return SyncResult.Failure(SyncFailureKind.Unauthorized)
        val response = transport.execute(
            path = "/auth/v1/user",
            method = "GET",
            accessToken = session.accessToken,
        )
        if (response.code == 401) return SyncResult.SessionExpired
        if (!response.isSuccessful) return response.toFailure()
        val userId = response.body?.let(::decodeUserId)
            ?: return SyncResult.Failure(SyncFailureKind.Decode)
        return SyncResult.Success(userId)
    }

    override suspend fun signOut(): SyncResult<Unit> {
        val session = (sessions.read() as? SupabaseSessionState.Active)?.session
        val response = session?.let {
            transport.execute(
                path = "/auth/v1/logout",
                method = "POST",
                accessToken = it.accessToken,
            )
        }
        sessions.clear()
        return when {
            response == null || response.isSuccessful || response.code == 401 -> SyncResult.Success(Unit)
            else -> response.toFailure()
        }
    }

    private suspend fun persistSession(
        response: com.sluggyard.tv.core.sync.remote.SupabaseHttpResponse,
        clearOnUnauthorized: Boolean = false,
    ): SyncResult<SupabaseSession> {
        if (response.code == 401) {
            if (clearOnUnauthorized) sessions.clear()
        }
        if (!response.isSuccessful) return response.toFailure()
        val session = response.body?.let { body ->
            withContext(Dispatchers.IO) { decodeSession(body) }
        }
            ?: return SyncResult.Failure(SyncFailureKind.Decode)
        sessions.write(session)
        return SyncResult.Success(session)
    }

    private fun decodeSession(payload: String): SupabaseSession? = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        val user = root["user"]?.jsonObject
        val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val userId = user?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresAt = SupabaseSessionExpiry.fromResponse(
            expiresAt = root["expires_at"]?.jsonPrimitive?.longOrNull,
            expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull,
            nowEpochMs = System.currentTimeMillis(),
        )
        SupabaseSession(userId, accessToken, refreshToken, expiresAt)
            .takeIf(SupabaseSession::isUsable)
    }.getOrNull()

    private fun decodeUserId(payload: String): String? = runCatching {
        json.parseToJsonElement(payload).jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    private data class RefreshOperation(
        val session: SupabaseSession,
        val result: CompletableDeferred<SyncResult<SupabaseSession>>,
    )
}

private fun jsonString(value: String): String =
    Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(value))

private fun com.sluggyard.tv.core.sync.remote.SupabaseHttpResponse.toFailure(): SyncResult<Nothing> =
    SyncResult.Failure(
        when (code) {
            0 -> SyncFailureKind.Network
            401 -> SyncFailureKind.Unauthorized
            403 -> SyncFailureKind.Forbidden
            409 -> SyncFailureKind.Conflict
            429 -> SyncFailureKind.RateLimited
            in 500..599 -> SyncFailureKind.Server
            else -> SyncFailureKind.Decode
        },
    )
