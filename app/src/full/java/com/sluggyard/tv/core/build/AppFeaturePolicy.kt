package com.sluggyard.tv.core.build

object AppFeaturePolicy {
    val pluginsEnabled: Boolean = false
    val inAppUpdatesEnabled: Boolean = false
    val inAppTrailerPlaybackEnabled: Boolean = true
    val externalTrailerPlaybackEnabled: Boolean = true
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    val imdbRatingLogoEnabled: Boolean = true
}
