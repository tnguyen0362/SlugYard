package com.sluggyard.tv.ui.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppDataStoreFileMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun copiesLegacyRewriteFileWhenAppFileMissing() {
        val dir = temp.newFolder("files")
        val legacy = File(dir, "playflix_rewrite.preferences_pb").apply {
            writeText("legacy-credentials-and-profiles")
        }
        val dest = File(dir, "playflix_app.preferences_pb")

        val result = restoreAppDataStoreFromLegacyFiles(dest = dest, legacyFiles = listOf(legacy))

        assertTrue(result is AppDataStoreRestoreResult.RestoredMissing)
        assertEquals("legacy-credentials-and-profiles", dest.readText())
    }

    @Test
    fun replacesUndersizedAppFileFromLegacy() {
        val dir = temp.newFolder("files2")
        val legacy = File(dir, "playflix_rewrite.preferences_pb").apply {
            writeText("x".repeat(200))
        }
        val dest = File(dir, "playflix_app.preferences_pb").apply {
            writeText("tiny")
        }

        val result = restoreAppDataStoreFromLegacyFiles(dest = dest, legacyFiles = listOf(legacy))

        assertTrue(result is AppDataStoreRestoreResult.ReplacedUndersized)
        assertEquals("x".repeat(200), dest.readText())
        assertTrue(File(dest.path + ".pre_rewrite_restore").exists())
    }
}
