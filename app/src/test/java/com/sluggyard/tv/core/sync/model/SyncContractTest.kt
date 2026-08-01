package com.sluggyard.tv.core.sync.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncContractTest {
    @Test
    fun syncDomainNamesAreStable() {
        assertEquals(
            listOf(
                "PROFILES",
                "ADDONS",
                "PLUGINS",
                "LIBRARY",
                "WATCH_PROGRESS",
                "WATCHED_ITEMS",
                "COLLECTIONS",
                "PROFILE_SETTINGS",
                "HOME_CATALOG_SETTINGS",
                "PROVIDER_CREDENTIALS",
            ),
            SyncDomain.entries.map { it.name },
        )
        assertEquals(listOf("UPSERT", "DELETE"), SyncOperation.entries.map { it.name })
    }

    @Test
    fun mutationIdIsDeterministicAndOperationScoped() {
        val first = envelope(operation = SyncOperation.UPSERT)
        val sameMutation = envelope(operation = SyncOperation.UPSERT)
        val deleteMutation = envelope(operation = SyncOperation.DELETE)

        assertEquals(first.mutationId, sameMutation.mutationId)
        assertNotEquals(first.mutationId, deleteMutation.mutationId)
        assertEquals(64, first.mutationId.length)
        assertTrue(first.mutationId.all { it in "0123456789abcdef" })
    }

    @Test
    fun everyMutationIdentityComponentChangesTheMutationId() {
        val base = SyncMutationId.forMutation(
            ownerUserId = "user-1",
            domain = SyncDomain.LIBRARY,
            profileId = 2,
            recordKey = "tmdb:movie:1",
            clientChangedAtEpochMs = 1_725_000_000_000,
            operation = SyncOperation.UPSERT,
        )

        assertNotEquals(base, mutationId(ownerUserId = "user-2"))
        assertNotEquals(base, mutationId(domain = SyncDomain.COLLECTIONS))
        assertNotEquals(base, mutationId(profileId = 3))
        assertNotEquals(base, mutationId(recordKey = "tmdb:movie:2"))
        assertNotEquals(base, mutationId(clientChangedAtEpochMs = 1_725_000_000_001))
        assertNotEquals(base, mutationId(operation = SyncOperation.DELETE))
    }

    @Test
    fun canonicalMutationEncodingUsesUtf8ByteLengths() {
        assertEquals(
            "0acb40a2c83835da27bc23c4b0d469ad0dddba21e6be0d6ec54d5e146fb1505b",
            SyncMutationId.forMutation(
                ownerUserId = "ü",
                domain = SyncDomain.LIBRARY,
                profileId = null,
                recordKey = "ключ",
                clientChangedAtEpochMs = 7,
                operation = SyncOperation.DELETE,
            ),
        )
    }

    @Test
    fun malformedOrReusedMutationIdentityIsRejected() {
        val valid = envelope()

        assertThrows(IllegalArgumentException::class.java) {
            SyncMutationEnvelope(
                mutationId = "not-a-canonical-id",
                ownerUserId = valid.ownerUserId,
                domain = valid.domain,
                profileId = valid.profileId,
                recordKey = valid.recordKey,
                operation = valid.operation,
                clientChangedAtEpochMs = valid.clientChangedAtEpochMs,
                schemaVersion = valid.schemaVersion,
                payloadJson = valid.payloadJson,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncMutationEnvelope(
                mutationId = valid.mutationId,
                ownerUserId = "user-2",
                domain = valid.domain,
                profileId = valid.profileId,
                recordKey = valid.recordKey,
                operation = valid.operation,
                clientChangedAtEpochMs = valid.clientChangedAtEpochMs,
                schemaVersion = valid.schemaVersion,
                payloadJson = valid.payloadJson,
            )
        }
    }

    @Test
    fun operationEncodingIsStableAndRoundTrips() {
        assertEquals("UPSERT", SyncOperation.UPSERT.encode())
        assertEquals("DELETE", SyncOperation.DELETE.encode())
        assertEquals(SyncOperation.UPSERT, SyncOperation.decode("UPSERT"))
        assertEquals(SyncOperation.DELETE, SyncOperation.decode("delete"))
        assertNull(SyncOperation.decode("merge"))
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        assertThrows(UnsupportedSyncSchemaVersionException::class.java) {
            envelope(schemaVersion = SyncSchemaVersion.CURRENT + 1)
        }
        assertTrue(SyncSchemaVersion.isSupported(SyncSchemaVersion.CURRENT))
    }

    @Test
    fun deleteEnvelopeProducesStableTombstoneIdentity() {
        val envelope = envelope(operation = SyncOperation.DELETE)

        val tombstone = SyncTombstone.from(envelope)

        assertEquals(
            SyncRecordIdentity(
                ownerUserId = "user-1",
                domain = SyncDomain.LIBRARY,
                profileId = 2,
                recordKey = "tmdb:movie:1",
            ),
            tombstone.identity,
        )
        assertEquals(envelope.mutationId, tombstone.mutationId)
        assertEquals(envelope.clientChangedAtEpochMs, tombstone.clientChangedAtEpochMs)
    }

    private fun envelope(
        operation: SyncOperation = SyncOperation.UPSERT,
        schemaVersion: Int = SyncSchemaVersion.CURRENT,
    ): SyncMutationEnvelope = SyncMutationEnvelope.create(
        ownerUserId = "user-1",
        domain = SyncDomain.LIBRARY,
        profileId = 2,
        recordKey = "tmdb:movie:1",
        operation = operation,
        clientChangedAtEpochMs = 1_725_000_000_000,
        schemaVersion = schemaVersion,
        payloadJson = "{\"title\":\"Example\"}",
    )

    private fun mutationId(
        ownerUserId: String = "user-1",
        domain: SyncDomain = SyncDomain.LIBRARY,
        profileId: Int? = 2,
        recordKey: String = "tmdb:movie:1",
        clientChangedAtEpochMs: Long = 1_725_000_000_000,
        operation: SyncOperation = SyncOperation.UPSERT,
    ): String = SyncMutationId.forMutation(
        ownerUserId = ownerUserId,
        domain = domain,
        profileId = profileId,
        recordKey = recordKey,
        clientChangedAtEpochMs = clientChangedAtEpochMs,
        operation = operation,
    )
}
