package com.sluggyard.tv.ui.rewrite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RewriteProfileMigrationTest {
    @Test
    fun `imports neutral names active selection and remember setting`() {
        val migrated = migrateProfileStateIfDefault(
            current = RewriteProfileState(),
            prior = listOf(PriorProfileSnapshot("1", "  Alex  "), PriorProfileSnapshot("2", "Sam")),
            activeProfileId = "2",
            rememberLastProfile = false,
        )

        assertEquals(listOf(RewriteProfile("1", "Alex"), RewriteProfile("2", "Sam")), migrated.profiles)
        assertEquals("2", migrated.activeProfileId)
        assertEquals(false, migrated.rememberLastProfile)
    }

    @Test
    fun `does not overwrite initialized rewrite profiles`() {
        val current = RewriteProfileState(profiles = listOf(RewriteProfile("rewrite", "Viewer")), activeProfileId = "rewrite")

        assertSame(current, migrateProfileStateIfDefault(current, emptyList(), "", true))
    }
}
