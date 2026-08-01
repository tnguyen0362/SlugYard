package com.sluggyard.tv.core.streams

data class StreamBadgeSettings(
    val rules: StreamBadgeRules = StreamBadgeRules(),
    val showFileSizeBadges: Boolean = true,
    val showAddonLogo: Boolean = true,
    val badgePlacement: StreamBadgePlacement = StreamBadgePlacement.BOTTOM
)

enum class StreamBadgePlacement {
    TOP,
    BOTTOM
}
