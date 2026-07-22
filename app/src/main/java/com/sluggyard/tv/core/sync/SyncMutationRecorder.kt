package com.sluggyard.tv.core.sync

import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.remote.SyncMutation

/** Account-binds local mutations before they enter the durable outbox. */
class SyncMutationRecorder(
    private val sessions: SupabaseSessionStore,
    private val local: LocalSyncStore,
) {
    suspend fun record(mutation: SyncMutation): SyncResult<Unit> {
        val session = (sessions.read() as? SupabaseSessionState.Active)?.session
            ?: return SyncResult.Failure(SyncFailureKind.Unauthorized)
        (local as? AccountBoundLocalSyncStore)?.bindOwner(session.userId)
        local.record(mutation)
        return SyncResult.Success(Unit)
    }
}
