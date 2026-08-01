package com.sluggyard.tv.ui.app.settings

import com.sluggyard.tv.ui.app.data.ProfileState

internal fun effectiveProfileId(
    activeProfileId: String,
    rememberLastProfile: Boolean,
    profileIds: List<String>,
): String = if (rememberLastProfile) {
    activeProfileId.takeIf(profileIds::contains) ?: profileIds.firstOrNull() ?: ProfileState.DefaultProfileId
} else {
    profileIds.firstOrNull() ?: ProfileState.DefaultProfileId
}
