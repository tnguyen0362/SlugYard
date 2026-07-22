package com.sluggyard.tv.core.sync

import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.remote.SupabaseHttpTransport
import com.sluggyard.tv.core.sync.remote.SupabaseHttpResponse
import com.sluggyard.tv.core.sync.remote.SupabaseCredentialVaultGateway
import com.sluggyard.tv.core.sync.remote.SyncTable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SchemaReadiness(
    val checkedTables: Set<SyncTable>,
    val failure: SyncFailureKind? = null,
) {
    val ready: Boolean get() = failure == null && checkedTables.size == SyncTable.entries.size
}

interface SupabaseSchemaProbe {
    suspend fun probe(table: SyncTable): SyncResult<Unit>
}

class SyncSchemaValidator(
    private val probe: SupabaseSchemaProbe,
) {
    suspend fun validate(): SyncResult<SchemaReadiness> {
        val checked = linkedSetOf<SyncTable>()
        for (table in SyncTable.entries) {
            when (val result = probe.probe(table)) {
                is SyncResult.Success -> checked += table
                is SyncResult.Failure -> return SyncResult.Success(SchemaReadiness(checked, result.kind))
                SyncResult.SessionExpired -> return SyncResult.SessionExpired
            }
        }
        return SyncResult.Success(SchemaReadiness(checked))
    }
}

class DefaultSupabaseSchemaProbe(
    private val transport: SupabaseHttpTransport,
    private val sessions: SupabaseSessionStore,
    private val credentialVault: SupabaseCredentialVaultGateway? = null,
) : SupabaseSchemaProbe {
    override suspend fun probe(table: SyncTable): SyncResult<Unit> {
        val session = (sessions.read() as? SupabaseSessionState.Active)?.session
        if (table == SyncTable.PROVIDER_CREDENTIALS) {
            val active = session ?: return SyncResult.Failure(SyncFailureKind.Unauthorized)
            return when (val result = credentialVault?.pull(active.userId, null)) {
                is SyncResult.Success -> SyncResult.Success(Unit)
                is SyncResult.Failure -> result
                SyncResult.SessionExpired -> SyncResult.SessionExpired
                null -> SyncResult.Failure(SyncFailureKind.Configuration)
            }
        }
        val path = "/rest/v1/${table.tableName}?select=${encode(table.identityColumn)}&limit=0"
        val response = transport.execute(path = path, method = "GET", accessToken = session?.accessToken)
        return response.toResult()
    }

    private fun SupabaseHttpResponse.toResult(): SyncResult<Unit> = when {
        isSuccessful -> SyncResult.Success(Unit)
        code == 401 -> SyncResult.SessionExpired
        else -> SyncResult.Failure(
            when (code) {
                0 -> SyncFailureKind.Network
                403 -> SyncFailureKind.Forbidden
                429 -> SyncFailureKind.RateLimited
                in 500..599 -> SyncFailureKind.Server
                else -> SyncFailureKind.Decode
            },
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
