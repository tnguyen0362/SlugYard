package com.sluggyard.tv.ui.rewrite.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RewriteCredentialEntryTest {
    @Test
    fun credentialSubmissionTrimsApiKeysButRejectsBlankInput() {
        assertEquals("torbox-api-key", credentialSubmissionValue("  torbox-api-key  "))
        assertNull(credentialSubmissionValue("   "))
    }
}
