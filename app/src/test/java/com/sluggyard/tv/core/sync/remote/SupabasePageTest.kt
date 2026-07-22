package com.sluggyard.tv.core.sync.remote

import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.model.SyncDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabasePageTest {
    @Test
    fun `page parser keeps records and continuation cursor`() {
        val page = SupabasePage.fromResponse(
            domain = SyncDomain.LIBRARY,
            response = SupabaseHttpResponse(
                code = 200,
                body = "[{\"profile_id\":1,\"content_id\":\"movie\"}]",
                headers = mapOf("x-next-cursor" to "library:100"),
            ),
        )

        assertEquals(SyncDomain.LIBRARY, page.domain)
        assertEquals(1, page.records.size)
        assertEquals("library:100", page.nextCursor)
    }

    @Test
    fun `page parser derives next offset from content range`() {
        val page = SupabasePage.fromResponse(
            SyncDomain.PROFILES,
            SupabaseHttpResponse(200, "[]", mapOf("content-range" to "0-99/150")),
        )

        assertEquals("100", page.nextCursor)
    }

    @Test
    fun `page parser rejects non-array or unsuccessful responses`() {
        val result = SupabasePage.tryFromResponse(
            SyncDomain.PROFILES,
            SupabaseHttpResponse(500, "{}"),
        )

        assertTrue(result is PageResult.Failure)
        assertEquals(SyncFailureKind.Server, (result as PageResult.Failure).kind)
    }

    @Test
    fun `mutation disposition parses accepted stale and duplicate states`() {
        assertEquals(
            MutationDisposition.Accepted(duplicate = false),
            MutationDisposition.fromJson("{\"accepted\":true,\"duplicate\":false}"),
        )
        assertEquals(
            MutationDisposition.Accepted(duplicate = true),
            MutationDisposition.fromJson("{\"accepted\":true,\"duplicate\":true}"),
        )
        assertEquals(
            MutationDisposition.Stale,
            MutationDisposition.fromJson("{\"accepted\":false,\"reason\":\"stale_or_conflicting\"}"),
        )
    }
}
