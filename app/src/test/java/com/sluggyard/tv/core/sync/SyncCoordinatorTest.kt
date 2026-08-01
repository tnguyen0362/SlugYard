package com.sluggyard.tv.core.sync

import com.sluggyard.tv.core.sync.auth.SupabaseSession
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.model.CloudProfile
import com.sluggyard.tv.core.sync.remote.RemoteSnapshot
import com.sluggyard.tv.core.sync.remote.SupabaseDataGateway
import com.sluggyard.tv.core.sync.remote.SyncMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SyncCoordinatorTest {
    @Test
    fun signedOutLeavesLocalDataAndDoesNotCallRemote() = runBlocking {
        val local = FakeLocalStore()
        local.queue(SyncMutation.Profile(profile(1, 10)))
        val remote = FakeRemote()

        val result = coordinator(local, remote, SupabaseSessionState.SignedOut).synchronize()

        assertIs<SyncResult.Success<SyncSummary>>(result)
        assertEquals(0, remote.pullCalls)
        assertEquals(1, local.pending().size)
    }

    @Test
    fun pullMergeApplyAndDrainAreLocalFirst() = runBlocking {
        val local = FakeLocalStore(LocalSnapshot(profiles = listOf(profile(1, 20))))
        local.queue(SyncMutation.Profile(profile(1, 20)))
        val remote = FakeRemote(
            snapshot = RemoteSnapshot(
                profiles = listOf(profile(1, 15), profile(2, 12)),
                library = emptyList(),
                progress = emptyList(),
                watchedItems = emptyList(),
                profileSettings = emptyList(),
            ),
        )

        val result = coordinator(local, remote).synchronize()

        assertIs<SyncResult.Success<SyncSummary>>(result)
        assertEquals(1, result.value.pushedMutations)
        assertEquals(listOf(1, 2), local.snapshot().profiles.map(CloudProfile::profileId))
        assertEquals(20, local.snapshot().profiles.first().changedAt)
        assertEquals(0, local.pending().size)
        assertEquals(1, remote.upsertCalls)
    }

    @Test
    fun transientFailureRetriesThreeTimesAndRetainsWhenExhausted() = runBlocking {
        val local = FakeLocalStore()
        local.queue(SyncMutation.Profile(profile(1, 10)))
        val remote = FakeRemote(upsertResults = ArrayDeque(listOf(
            SyncResult.Failure(SyncFailureKind.Network),
            SyncResult.Failure(SyncFailureKind.Server),
            SyncResult.Failure(SyncFailureKind.RateLimited),
        )))

        val result = coordinator(local, remote).synchronize()

        assertEquals(SyncResult.Failure(SyncFailureKind.RateLimited), result)
        assertEquals(3, remote.upsertCalls)
        assertEquals(1, local.pending().size)
    }

    @Test
    fun permanentFailureIsNotRetried() = runBlocking {
        val local = FakeLocalStore()
        local.queue(SyncMutation.Profile(profile(1, 10)))
        val remote = FakeRemote(upsertResults = ArrayDeque(listOf(SyncResult.Failure(SyncFailureKind.Forbidden))))

        val result = coordinator(local, remote).synchronize()

        assertEquals(SyncResult.Failure(SyncFailureKind.Forbidden), result)
        assertEquals(1, remote.upsertCalls)
        assertEquals(1, local.pending().size)
    }

    @Test
    fun cancellationIsNotConvertedToFailure() = runBlocking {
        val local = FakeLocalStore()
        val remote = object : FakeRemote() {
            override suspend fun pull(userId: String): SyncResult<RemoteSnapshot> {
                throw CancellationException("cancelled")
            }
        }

        assertFailsWith<CancellationException> { coordinator(local, remote).synchronize() }
        Unit
    }

    private fun coordinator(
        local: FakeLocalStore,
        remote: FakeRemote,
        state: SupabaseSessionState = SupabaseSessionState.Active(session),
    ) = SyncCoordinator(
        sessions = FakeSessions(state),
        remote = remote,
        local = local,
        wait = {},
    )

    private fun profile(id: Int, changedAt: Long) = CloudProfile(id, "Profile $id", "#123456", null, changedAt)

    private companion object {
        val session = SupabaseSession("user-1", "access", "refresh", Long.MAX_VALUE)
    }
}

private class FakeSessions(private var state: SupabaseSessionState) : SupabaseSessionStore {
    override suspend fun read(): SupabaseSessionState = state
    override suspend fun write(session: SupabaseSession) { state = SupabaseSessionState.Active(session) }
    override suspend fun clear() { state = SupabaseSessionState.SignedOut }
}

private open class FakeRemote(
    private val snapshot: RemoteSnapshot = RemoteSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    private val upsertResults: ArrayDeque<SyncResult<Unit>> = ArrayDeque(),
) : SupabaseDataGateway {
    var pullCalls = 0
    var upsertCalls = 0

    override suspend fun pull(userId: String): SyncResult<RemoteSnapshot> {
        pullCalls++
        return SyncResult.Success(snapshot)
    }

    override suspend fun upsert(mutation: SyncMutation): SyncResult<Unit> {
        upsertCalls++
        return upsertResults.removeFirstOrNull() ?: SyncResult.Success(Unit)
    }

    override suspend fun delete(mutation: com.sluggyard.tv.core.sync.remote.DeleteMutation): SyncResult<Unit> =
        SyncResult.Success(Unit)
}

private class FakeLocalStore(
    private var value: LocalSnapshot = LocalSnapshot(),
) : LocalSyncStore {
    private val outbox = linkedMapOf<String, SyncMutation>()

    fun queue(mutation: SyncMutation) { outbox[mutation.stableId()] = mutation }

    override suspend fun snapshot(): LocalSnapshot = value
    override suspend fun apply(snapshot: RemoteSnapshot) { value = LocalSnapshot(snapshot.profiles, snapshot.library, snapshot.progress, snapshot.watchedItems, snapshot.profileSettings) }
    override suspend fun enqueue(mutation: SyncMutation) { queue(mutation) }
    override suspend fun pending(): List<SyncMutation> = outbox.values.toList()
    override suspend fun acknowledge(mutationId: String) { outbox.remove(mutationId) }
}
