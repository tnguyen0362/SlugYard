package com.sluggyard.tv.ui.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialEntryTest {
    @Test
    fun credentialSubmissionTrimsApiKeysButRejectsBlankInput() {
        assertEquals("torbox-api-key", credentialSubmissionValue("  torbox-api-key  "))
        assertNull(credentialSubmissionValue("   "))
    }
}
