package com.sluggyard.tv.core.sync.auth

import com.sluggyard.tv.core.sync.remote.SupabaseHttpResponse
import com.sluggyard.tv.core.sync.remote.SupabaseHttpTransport
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseSessionTest {
    private val session = SupabaseSession(
        userId = "user-1",
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresAtEpochMs = 2_000L,
    )

    @Test
    fun codecRoundTripsOnlyTheSessionFields() {
        assertEquals(session, SupabaseSessionCodec.decode(SupabaseSessionCodec.encode(session)))
    }

    @Test
    fun codecRejectsMalformedOrIncompletePayloads() {
        assertNull(SupabaseSessionCodec.decode("not-json"))
        assertNull(SupabaseSessionCodec.decode("{\"access_token\":\"token\"}"))
    }

    @Test
    fun signUpPersistsTheReturnedSession() = runBlocking {
        val sessions = InMemorySessionStore(SupabaseSessionState.SignedOut)
        val transport = mockk<SupabaseHttpTransport>()
        coEvery {
            transport.execute(
                path = "/auth/v1/signup",
                method = "POST",
                body = any(),
                accessToken = null,
                headers = any(),
            )
        } returns SupabaseHttpResponse(
            code = 200,
            body = """
                {"access_token":"access","refresh_token":"refresh",
                 "expires_at":1700003600,"user":{"id":"user-1"}}
            """.trimIndent(),
        )

        val result = DefaultSupabaseAuthGateway(transport, sessions).signUp(
            email = " viewer@example.com ",
            password = "password",
        )

        assertEquals(
            SyncResult.Success(
                SupabaseSession("user-1", "access", "refresh", 1_700_003_600_000L),
            ),
            result,
        )
        assertEquals(
            SupabaseSessionState.Active(
                SupabaseSession("user-1", "access", "refresh", 1_700_003_600_000L),
            ),
            sessions.read(),
        )
    }

    @Test
    fun signUpRejectsBlankCredentialsBeforeTransport() = runBlocking {
        val transport = mockk<SupabaseHttpTransport>()
        val result = DefaultSupabaseAuthGateway(
            transport,
            InMemorySessionStore(SupabaseSessionState.SignedOut),
        ).signUp(" ", "")

        assertEquals(SyncResult.Failure(SyncFailureKind.InvalidInput), result)
    }

    @Test
    fun expiryAndUsabilityAreDeterministic() {
        assertTrue(session.isUsable())
        assertFalse(session.isExpired(nowEpochMs = 1_999L))
        assertTrue(session.isExpired(nowEpochMs = 2_000L))
    }

    @Test
    fun supabaseExpiryFormsNormalizeToEpochMillisecondsWithoutDoubleScaling() {
        assertEquals(
            1_700_000_000_000L,
            SupabaseSessionExpiry.epochMillis(1_700_000_000L),
        )
        assertEquals(
            1_700_000_000_000L,
            SupabaseSessionExpiry.epochMillis(1_700_000_000_000L),
        )
        assertEquals(
            1_700_003_600_000L,
            SupabaseSessionExpiry.fromResponse(
                expiresAt = 1_700_003_600_000L,
                expiresIn = null,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertEquals(
            1_700_003_600_000L,
            SupabaseSessionExpiry.fromResponse(
                expiresAt = 0L,
                expiresIn = 3_600L,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun concurrentRefreshesShareOneSuccessfulRefresh() = runBlocking {
        val sessions = InMemorySessionStore(SupabaseSessionState.Active(session))
        val transport = mockk<SupabaseHttpTransport>()
        var refreshCalls = 0
        coEvery {
            transport.execute(
                path = "/auth/v1/token?grant_type=refresh_token",
                method = "POST",
                body = any(),
                accessToken = null,
                headers = any(),
            )
        } coAnswers {
            refreshCalls++
            delay(25L)
            SupabaseHttpResponse(
                code = 200,
                body = """
                    {"access_token":"new-access","refresh_token":"new-refresh",
                     "expires_at":1700003600,"user":{"id":"user-1"}}
                """.trimIndent(),
            )
        }
        val gateway = DefaultSupabaseAuthGateway(transport, sessions)

        val results = coroutineScope {
            listOf(async { gateway.refresh() }, async { gateway.refresh() }).awaitAll()
        }

        assertEquals(1, refreshCalls)
        assertEquals(results[0], results[1])
        assertIs<SyncResult.Success<SupabaseSession>>(results[0])
        Unit
    }

    @Test
    fun concurrentRefreshFailuresShareOneFailureResult() = runBlocking {
        val sessions = InMemorySessionStore(SupabaseSessionState.Active(session))
        val transport = mockk<SupabaseHttpTransport>()
        var refreshCalls = 0
        coEvery {
            transport.execute(
                path = "/auth/v1/token?grant_type=refresh_token",
                method = "POST",
                body = any(),
                accessToken = null,
                headers = any(),
            )
        } coAnswers {
            refreshCalls++
            delay(25L)
            SupabaseHttpResponse(code = 500, body = null)
        }
        val gateway = DefaultSupabaseAuthGateway(transport, sessions)

        val results = coroutineScope {
            listOf(async { gateway.refresh() }, async { gateway.refresh() }).awaitAll()
        }

        assertEquals(1, refreshCalls)
        assertEquals(SyncResult.Failure(SyncFailureKind.Server), results[0])
        assertEquals(results[0], results[1])
        Unit
    }

    @Test
    fun refreshPreservesFailureCategoriesAndDoesNotClearOnNetworkFailure() = runBlocking {
        val cases = listOf(
            0 to SyncFailureKind.Network,
            401 to SyncFailureKind.Unauthorized,
            403 to SyncFailureKind.Forbidden,
            409 to SyncFailureKind.Conflict,
            429 to SyncFailureKind.RateLimited,
            500 to SyncFailureKind.Server,
        )

        cases.forEach { (code, expectedKind) ->
            val sessions = InMemorySessionStore(SupabaseSessionState.Active(session))
            val transport = mockk<SupabaseHttpTransport>()
            coEvery { transport.execute(any(), any(), any(), any(), any()) } returns
                SupabaseHttpResponse(code, null)
            val result = DefaultSupabaseAuthGateway(transport, sessions).refresh()

            assertEquals(SyncResult.Failure(expectedKind), result)
            if (expectedKind == SyncFailureKind.Network) {
                assertEquals(SupabaseSessionState.Active(session), sessions.read())
            }
        }

        val transport = mockk<SupabaseHttpTransport>()
        coEvery { transport.execute(any(), any(), any(), any(), any()) } returns
            SupabaseHttpResponse(200, "not-json")
        assertEquals(
            SyncResult.Failure(SyncFailureKind.Decode),
            DefaultSupabaseAuthGateway(
                transport,
                InMemorySessionStore(SupabaseSessionState.Active(session)),
            ).refresh(),
        )
    }

    @Test
    fun refreshPreservesCancellation() = runBlocking {
        val transport = mockk<SupabaseHttpTransport>()
        coEvery { transport.execute(any(), any(), any(), any(), any()) } throws
            CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            DefaultSupabaseAuthGateway(
                transport,
                InMemorySessionStore(SupabaseSessionState.Active(session)),
            ).refresh()
        }
        Unit
    }

    private class InMemorySessionStore(
        private var state: SupabaseSessionState,
    ) : SupabaseSessionStore {
        override suspend fun read(): SupabaseSessionState = state

        override suspend fun write(session: SupabaseSession) {
            state = SupabaseSessionState.Active(session)
        }

        override suspend fun clear() {
            state = SupabaseSessionState.SignedOut
        }
    }
}
