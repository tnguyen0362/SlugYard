package com.sluggyard.tv.ui.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File

private const val TAG = "AppDataStoreMigrate"

/** Current rewrite/app preferences file. Never rename without a migration here. */
internal const val APP_DATA_STORE_NAME = "playflix_app"

/**
 * Pre-[PreferenceDataStoreFactory] file migration.
 *
 * Commit 7ceaff0b renamed `playflix_rewrite` → `playflix_app` without copying the file.
 * TorBox / RD / Premiumize keys (and CW / profiles) lived in the old file, so every update
 * after that rename looked like a factory reset of credentials.
 */
internal fun migrateLegacyAppDataStoreFiles(context: Context) {
    val dest = context.preferencesDataStoreFile(APP_DATA_STORE_NAME)
    val legacyFiles = LEGACY_APP_DATA_STORE_NAMES.map { context.preferencesDataStoreFile(it) }
    val result = restoreAppDataStoreFromLegacyFiles(dest = dest, legacyFiles = legacyFiles)
    when (result) {
        is AppDataStoreRestoreResult.RestoredMissing ->
            Log.i(TAG, "Restored $APP_DATA_STORE_NAME from legacy ${result.legacyName} (${result.bytes} bytes)")
        is AppDataStoreRestoreResult.ReplacedUndersized ->
            Log.i(
                TAG,
                "Replaced undersized $APP_DATA_STORE_NAME (${result.previousBytes}B) " +
                    "with legacy ${result.legacyName} (${result.bytes}B); backup=${result.backupName}",
            )
        AppDataStoreRestoreResult.NoOp -> Unit
        is AppDataStoreRestoreResult.Failed ->
            Log.e(TAG, "Failed restoring $APP_DATA_STORE_NAME from ${result.legacyName}", result.error)
    }
}

internal sealed class AppDataStoreRestoreResult {
    data object NoOp : AppDataStoreRestoreResult()
    data class RestoredMissing(val legacyName: String, val bytes: Long) : AppDataStoreRestoreResult()
    data class ReplacedUndersized(
        val legacyName: String,
        val previousBytes: Long,
        val bytes: Long,
        val backupName: String,
    ) : AppDataStoreRestoreResult()
    data class Failed(val legacyName: String, val error: Throwable) : AppDataStoreRestoreResult()
}

internal fun restoreAppDataStoreFromLegacyFiles(
    dest: File,
    legacyFiles: List<File>,
): AppDataStoreRestoreResult {
    val destLock = File(dest.path + ".lock")
    for (source in legacyFiles) {
        if (!source.exists() || source.length() <= 0L) continue
        val legacyName = source.nameWithoutExtension.substringBefore(".preferences_pb")
            .ifBlank { source.name }
        if (!dest.exists() || dest.length() <= 0L) {
            return runCatching {
                source.copyTo(dest, overwrite = true)
                AppDataStoreRestoreResult.RestoredMissing(legacyName = source.name, bytes = source.length())
            }.getOrElse { AppDataStoreRestoreResult.Failed(source.name, it) }
        }
        val destBytes = dest.length()
        val sourceBytes = source.length()
        if (sourceBytes > destBytes + 64L && !destLock.exists()) {
            return runCatching {
                val backup = File(dest.path + ".pre_rewrite_restore")
                dest.copyTo(backup, overwrite = true)
                source.copyTo(dest, overwrite = true)
                AppDataStoreRestoreResult.ReplacedUndersized(
                    legacyName = source.name,
                    previousBytes = destBytes,
                    bytes = sourceBytes,
                    backupName = backup.name,
                )
            }.getOrElse { AppDataStoreRestoreResult.Failed(source.name, it) }
        }
    }
    return AppDataStoreRestoreResult.NoOp
}

private val LEGACY_APP_DATA_STORE_NAMES = listOf(
    "playflix_rewrite",
)
