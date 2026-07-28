package com.sluggyard.tv.ui.rewrite.settings

import com.sluggyard.tv.ui.rewrite.data.RewriteProfileState

internal fun effectiveProfileId(
    activeProfileId: String,
    rememberLastProfile: Boolean,
    profileIds: List<String>,
): String = if (rememberLastProfile) {
    activeProfileId.takeIf(profileIds::contains) ?: profileIds.firstOrNull() ?: RewriteProfileState.DefaultProfileId
} else {
    profileIds.firstOrNull() ?: RewriteProfileState.DefaultProfileId
}
