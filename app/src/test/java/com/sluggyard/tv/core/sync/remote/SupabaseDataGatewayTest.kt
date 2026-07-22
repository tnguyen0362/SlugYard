package com.sluggyard.tv.core.sync.remote

import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSession
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.model.SyncMutationEnvelope
import com.sluggyard.tv.core.sync.model.SyncOperation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseDataGatewayTest {
    private val session = SupabaseSession("user", "access", "refresh", Long.MAX_VALUE)

    @Test
    fun `pull page uses scoped stable ordered pagination`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        val path = slot<String>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)
        coEvery {
            transport.execute(
                path = capture(path),
                method = "GET",
                body = null,
                accessToken = "access",
                headers = emptyMap(),
            )
        } returns SupabaseHttpResponse(200, "[]", mapOf("x-next-cursor" to "100"))

        val result = DefaultSupabaseDataGateway(transport, sessions, auth)
            .pullPage(SyncDomain.LIBRARY, "user", null)

        assertTrue(result is SyncResult.Success)
        assertEquals(
            "/rest/v1/library?select=*&user_id=eq.user&order=content_id.asc&limit=100&offset=0",
            path.captured,
        )
    }

    @Test
    fun `pull page rejects malformed cursor`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)

        val result = DefaultSupabaseDataGateway(transport, sessions, auth)
            .pullPage(SyncDomain.LIBRARY, "user", "not-a-number")

        assertEquals(SyncResult.Failure(SyncFailureKind.Configuration), result)
    }

    @Test
    fun `pull page url encodes account identity`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        val owner = "user@example.com/one"
        val ownerSession = session.copy(userId = owner)
        val path = slot<String>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(ownerSession)
        coEvery {
            transport.execute(
                path = capture(path),
                method = "GET",
                body = null,
                accessToken = "access",
                headers = emptyMap(),
            )
        } returns SupabaseHttpResponse(200, "[]")

        DefaultSupabaseDataGateway(transport, sessions, auth)
            .pullPage(SyncDomain.PROFILES, owner, null)

        assertEquals(
            "/rest/v1/profiles?select=*&user_id=eq.user%40example.com%2Fone&order=profile_index.asc&limit=100&offset=0",
            path.captured,
        )
    }

    @Test
    fun `event pull validates cursor and advances from event id`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        val path = slot<String>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)
        coEvery {
            transport.execute(
                path = capture(path),
                method = "GET",
                body = null,
                accessToken = "access",
                headers = emptyMap(),
            )
        } returns SupabaseHttpResponse(200, "[{\"event_id\":12}]")

        val result = DefaultSupabaseDataGateway(transport, sessions, auth)
            .pullEvents(SyncDomain.WATCH_PROGRESS, "user", 5)

        assertEquals(
            "/rest/v1/watch_progress_events?select=*&user_id=eq.user&event_id=gt.5&order=event_id.asc&limit=100",
            path.captured,
        )
        assertEquals(12L, (result as SyncResult.Success).value.nextCursor)
    }

    @Test
    fun `guarded mutation sends canonical rpc envelope`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        val body = slot<String>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)
        coEvery {
            transport.execute(
                path = "/rest/v1/rpc/apply_sync_mutation",
                method = "POST",
                body = capture(body),
                accessToken = "access",
                headers = emptyMap(),
            )
        } returns SupabaseHttpResponse(200, "{\"accepted\":true,\"duplicate\":false}")

        val mutation = SyncMutationEnvelope.create(
            ownerUserId = "user",
            domain = SyncDomain.LIBRARY,
            profileId = 1,
            recordKey = "movie",
            operation = SyncOperation.UPSERT,
            clientChangedAtEpochMs = 20L,
            payloadJson = "{\"content_id\":\"movie\"}",
        )
        val result = DefaultSupabaseDataGateway(transport, sessions, auth).applyMutation(mutation)

        assertEquals(SyncResult.Success(MutationDisposition.Accepted(false)), result)
        assertTrue(body.captured.contains("\"p_domain\":\"LIBRARY\""))
        assertTrue(body.captured.contains("\"p_mutation_id\":\"${mutation.mutationId}\""))
    }

    @Test
    fun `guarded mutation rejects a different account before transport`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)

        val mutation = SyncMutationEnvelope.create(
            ownerUserId = "other-user",
            domain = SyncDomain.LIBRARY,
            profileId = 1,
            recordKey = "movie",
            operation = SyncOperation.UPSERT,
            clientChangedAtEpochMs = 20L,
            payloadJson = "{\"content_id\":\"movie\"}",
        )
        val result = DefaultSupabaseDataGateway(transport, sessions, auth).applyMutation(mutation)

        assertEquals(SyncResult.Failure(SyncFailureKind.Forbidden), result)
        coVerify(exactly = 0) { transport.execute(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `401 refreshes once and retries page with new access token`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        val refreshed = session.copy(accessToken = "new-access")
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)
        coEvery { auth.refresh() } returns SyncResult.Success(refreshed)
        coEvery {
            transport.execute(
                path = any(),
                method = "GET",
                body = null,
                accessToken = any(),
                headers = emptyMap(),
            )
        } returnsMany listOf(
            SupabaseHttpResponse(401, null),
            SupabaseHttpResponse(200, "[]"),
        )

        val result = DefaultSupabaseDataGateway(transport, sessions, auth)
            .pullPage(SyncDomain.PROFILES, "user", null)

        assertTrue(result is SyncResult.Success)
        coVerify(exactly = 1) { auth.refresh() }
        coVerify(exactly = 2) { transport.execute(any(), "GET", null, any(), emptyMap()) }
    }

    @Test
    fun `second 401 becomes session expired`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)
        coEvery { auth.refresh() } returns SyncResult.Success(session.copy(accessToken = "new-access"))
        coEvery {
            transport.execute(
                path = any(),
                method = "GET",
                body = null,
                accessToken = any(),
                headers = emptyMap(),
            )
        } returns SupabaseHttpResponse(401, null)

        val result = DefaultSupabaseDataGateway(transport, sessions, auth)
            .pullPage(SyncDomain.PROFILES, "user", null)

        assertEquals(SyncResult.SessionExpired, result)
    }

    @Test
    fun `refresh unauthorized becomes session expired`() = runTest {
        val transport = mockk<SupabaseHttpTransport>()
        val sessions = mockk<SupabaseSessionStore>()
        val auth = mockk<SupabaseAuthGateway>()
        coEvery { sessions.read() } returns SupabaseSessionState.Active(session)
        coEvery { auth.refresh() } returns SyncResult.Failure(SyncFailureKind.Unauthorized)
        coEvery {
            transport.execute(
                path = any(),
                method = "GET",
                body = null,
                accessToken = any(),
                headers = emptyMap(),
            )
        } returns SupabaseHttpResponse(401, null)

        val result = DefaultSupabaseDataGateway(transport, sessions, auth)
            .pullPage(SyncDomain.PROFILES, "user", null)

        assertEquals(SyncResult.SessionExpired, result)
    }
}
