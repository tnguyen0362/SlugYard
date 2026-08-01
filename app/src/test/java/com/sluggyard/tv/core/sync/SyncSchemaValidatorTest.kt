package com.sluggyard.tv.core.sync

import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.remote.SyncTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SyncSchemaValidatorTest {
    @Test
    fun validatesOnlyTheApprovedTables() = runBlocking {
        val probe = RecordingProbe()

        val result = SyncSchemaValidator(probe).validate()

        assertTrue(result is SyncResult.Success)
        assertTrue(result.value.ready)
        assertEquals(SyncTable.entries.toSet(), probe.tables)
    }

    @Test
    fun reportsRlsFailureWithoutPrivilegedFallback() = runBlocking {
        val probe = RecordingProbe(failure = SyncFailureKind.Forbidden)

        val result = SyncSchemaValidator(probe).validate()

        assertTrue(result is SyncResult.Success)
        assertFalse(result.value.ready)
        assertEquals(SyncFailureKind.Forbidden, result.value.failure)
        assertEquals(1, probe.tables.size)
    }
}

private class RecordingProbe(
    private val failure: SyncFailureKind? = null,
) : SupabaseSchemaProbe {
    val tables = linkedSetOf<SyncTable>()

    override suspend fun probe(table: SyncTable): SyncResult<Unit> {
        tables += table
        return failure?.let { SyncResult.Failure(it) } ?: SyncResult.Success(Unit)
    }
}
