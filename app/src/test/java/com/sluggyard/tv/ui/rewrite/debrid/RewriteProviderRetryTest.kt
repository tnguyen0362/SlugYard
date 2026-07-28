package com.sluggyard.tv.ui.rewrite.debrid

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RewriteProviderRetryTest {
    @Test
    fun `transient provider failures are retried with a bounded attempt count`() = runTest {
        var attempts = 0
        val result = retryRewriteProviderCall(
            isRetryable = { it is RewriteTorboxResult.NetworkFailure },
        ) {
            attempts++
            if (attempts < 3) {
                RewriteTorboxResult.NetworkFailure(IllegalStateException("temporary"))
            } else {
                RewriteTorboxResult.Success(buildJsonObject {})
            }
        }

        assertEquals(3, attempts)
        assertEquals(true, result is RewriteTorboxResult.Success)
    }
}
