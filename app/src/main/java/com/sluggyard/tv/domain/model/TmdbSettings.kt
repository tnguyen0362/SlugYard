package com.sluggyard.tv.domain.model

data class TmdbSettings(
    val enabled: Boolean = false,
    val modernHomeEnabled: Boolean = false,
    val enrichContinueWatching: Boolean = true,
    val language: String = "en",
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useReleaseDates: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useTrailers: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true,
)