package com.sluggyard.tv.ui.app.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCredentialLegacyDualWriteTest {
    @Test
    fun `integer profile id is used for legacy dual-write`() {
        assertEquals(2, legacyDualWriteProfileId("2"))
        assertEquals(1, legacyDualWriteProfileId("1"))
    }

    @Test
    fun `non-integer or blank target skips legacy dual-write instead of active fallback`() {
        assertNull(legacyDualWriteProfileId(null))
        assertNull(legacyDualWriteProfileId(""))
        assertNull(legacyDualWriteProfileId("default"))
        assertNull(legacyDualWriteProfileId("abc"))
    }
}
