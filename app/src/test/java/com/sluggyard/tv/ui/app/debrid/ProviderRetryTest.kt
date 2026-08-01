package com.sluggyard.tv.ui.app.debrid

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRetryTest {
    @Test
    fun `transient provider failures are retried with a bounded attempt count`() = runTest {
        var attempts = 0
        val result = retryProviderCall(
            isRetryable = { it is TorboxResult.NetworkFailure },
        ) {
            attempts++
            if (attempts < 3) {
                TorboxResult.NetworkFailure(IllegalStateException("temporary"))
            } else {
                TorboxResult.Success(buildJsonObject {})
            }
        }

        assertEquals(3, attempts)
        assertEquals(true, result is TorboxResult.Success)
    }
}
