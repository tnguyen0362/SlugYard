package com.sluggyard.tv.ui.app.settings

import androidx.compose.runtime.Immutable

enum class SettingsCategory(
    val title: String,
    val description: String,
) {
    Account("Account", "Sync status and local account data"),
    Profiles("Profiles", "Choose who is watching"),
    Layout("Layout", "Home rows, posters, and navigation"),
    Addons("Addons", "Installed addons"),
    Integrations("Integrations", "Trakt, TMDB, ratings, and providers"),
    Display("Display", "Frame rate, resolution, and aspect mode"),
    Subtitles("Subtitles", "Language, visibility, and subtitle style"),
    Audio("Audio", "Language, channels, and output behavior"),
    About("About", "License, privacy, terms, and credits"),
}

enum class PlayerSetting {
    FrameRateMatching,
    ResolutionMatching,
    ResizeMode,
    SubtitleLanguage,
    SubtitleStyle,
    AudioLanguage,
    AudioChannels,
    AudioDownmix,
    AudioTunneling,
    OpticalPassthrough,
    EngineSelection,
    DolbyVisionPolicy,
    LibassRenderer,
    BufferSizing,
    Autoplay,
    IntroSkip,
}

@Immutable
data class SettingsCategoryItem(
    val category: SettingsCategory,
    val title: String = category.title,
    val description: String = category.description,
)

sealed interface SettingsDestination {
    data object Root : SettingsDestination
    data class Detail(val category: SettingsCategory) : SettingsDestination
}

sealed interface SettingsIntent {
    data object OpenRoot : SettingsIntent
    data class Open(val category: SettingsCategory) : SettingsIntent
    data object Back : SettingsIntent
}

object SettingsPolicy {
    val categories: List<SettingsCategory> = listOf(
        SettingsCategory.Account,
        SettingsCategory.Profiles,
        SettingsCategory.Layout,
        SettingsCategory.Addons,
        SettingsCategory.Integrations,
        SettingsCategory.Display,
        SettingsCategory.Subtitles,
        SettingsCategory.Audio,
        SettingsCategory.About,
    )

    fun exposesPlayerSetting(setting: PlayerSetting): Boolean = when (setting) {
        PlayerSetting.FrameRateMatching,
        PlayerSetting.ResolutionMatching,
        PlayerSetting.ResizeMode,
        PlayerSetting.SubtitleLanguage,
        PlayerSetting.SubtitleStyle,
        PlayerSetting.AudioLanguage,
        PlayerSetting.AudioChannels,
        PlayerSetting.AudioDownmix,
        PlayerSetting.AudioTunneling,
        PlayerSetting.OpticalPassthrough -> true
        PlayerSetting.EngineSelection,
        PlayerSetting.DolbyVisionPolicy,
        PlayerSetting.LibassRenderer,
        PlayerSetting.BufferSizing,
        PlayerSetting.Autoplay,
        PlayerSetting.IntroSkip -> false
    }
}

fun reduceSettingsDestination(
    current: SettingsDestination,
    intent: SettingsIntent,
): SettingsDestination = when (intent) {
    SettingsIntent.OpenRoot -> SettingsDestination.Root
    is SettingsIntent.Open -> SettingsDestination.Detail(intent.category)
    SettingsIntent.Back -> SettingsDestination.Root
}
