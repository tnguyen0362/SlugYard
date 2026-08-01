package com.sluggyard.tv.core.sync.adapter

import com.sluggyard.tv.core.sync.model.SyncDomain
import com.sluggyard.tv.core.sync.model.SyncOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyncDomainAdapterTest {
    private val json = Json

    @Test
    fun `all schema domains have identity adapters`() {
        assertEquals(10, SyncDomainAdapters.all.size)
        assertEquals(
            SyncDomain.entries.toSet(),
            SyncDomainAdapters.all.map { it.domain }.toSet(),
        )
    }

    @Test
    fun `library adapter builds canonical envelope from wire record`() {
        val adapter = SyncDomainAdapters.forDomain(SyncDomain.LIBRARY)
        val record = json.parseToJsonElement(
            """{"profile_id":2,"content_id":"movie/1","client_changed_at":42}""",
        ).jsonObject

        val envelope = adapter.envelope("owner", SyncOperation.UPSERT, record.toString(), record)

        assertNotNull(envelope)
        assertEquals("movie/1", envelope?.recordKey)
        assertEquals(2, envelope?.profileId)
        assertEquals("owner", envelope?.ownerUserId)
    }
}
