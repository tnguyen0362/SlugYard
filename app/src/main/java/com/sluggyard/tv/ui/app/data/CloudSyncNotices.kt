package com.sluggyard.tv.ui.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Surfaces cloud-sync skips that would otherwise fail silently (non-integer profile ids).
 * Home / settings can show [latestNotice] without coupling stores to Compose.
 */
object CloudSyncNotices {
    private val _latestNotice = MutableStateFlow<String?>(null)
    val latestNotice: StateFlow<String?> = _latestNotice.asStateFlow()

    fun reportProfileNotCloudLinked(profileId: String) {
        _latestNotice.value =
            "Cloud sync skipped for profile \"$profileId\". Use a numeric profile (1, 2, …) so Continue Watching and Watchlist can sync."
    }

    fun clear() {
        _latestNotice.value = null
    }
}

/** Returns a cloud profile id or null after recording a user-visible notice. */
internal fun cloudLinkedProfileIdOrNull(profileId: String): Int? {
    val parsed = profileId.toIntOrNull()
    if (parsed == null) CloudSyncNotices.reportProfileNotCloudLinked(profileId)
    return parsed
}
