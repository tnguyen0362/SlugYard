package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.runtime.Immutable

enum class RewriteSettingsCategory(
    val title: String,
    val description: String,
) {
    Account("Account", "Sync status and local account data"),
    Profiles("Profiles", "Choose who is watching"),
    Layout("Layout", "Home rows, posters, and navigation"),
    ContentDiscovery("Content & Discovery", "Addons, catalogs, and content sources"),
    Integrations("Integrations", "Trakt, TMDB, ratings, and providers"),
    Display("Display", "Frame rate, resolution, and aspect mode"),
    Subtitles("Subtitles", "Language, visibility, and subtitle style"),
    Audio("Audio", "Language, channels, and output behavior"),
    Advanced("Advanced", "Cache, diagnostics, and effective performance controls"),
    About("About", "Version, licenses, and project information"),
}

enum class RewritePlayerSetting {
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
data class RewriteSettingsCategoryItem(
    val category: RewriteSettingsCategory,
    val title: String = category.title,
    val description: String = category.description,
)

sealed interface SettingsDestination {
    data object Root : SettingsDestination
    data class Detail(val category: RewriteSettingsCategory) : SettingsDestination
}

sealed interface SettingsIntent {
    data object OpenRoot : SettingsIntent
    data class Open(val category: RewriteSettingsCategory) : SettingsIntent
    data object Back : SettingsIntent
}

object RewriteSettingsPolicy {
    val categories: List<RewriteSettingsCategory> = listOf(
        RewriteSettingsCategory.Account,
        RewriteSettingsCategory.Profiles,
        RewriteSettingsCategory.Layout,
        RewriteSettingsCategory.ContentDiscovery,
        RewriteSettingsCategory.Integrations,
        RewriteSettingsCategory.Display,
        RewriteSettingsCategory.Subtitles,
        RewriteSettingsCategory.Audio,
        RewriteSettingsCategory.Advanced,
        RewriteSettingsCategory.About,
    )

    fun exposesPlayerSetting(setting: RewritePlayerSetting): Boolean = when (setting) {
        RewritePlayerSetting.FrameRateMatching,
        RewritePlayerSetting.ResolutionMatching,
        RewritePlayerSetting.ResizeMode,
        RewritePlayerSetting.SubtitleLanguage,
        RewritePlayerSetting.SubtitleStyle,
        RewritePlayerSetting.AudioLanguage,
        RewritePlayerSetting.AudioChannels,
        RewritePlayerSetting.AudioDownmix,
        RewritePlayerSetting.AudioTunneling,
        RewritePlayerSetting.OpticalPassthrough -> true
        RewritePlayerSetting.EngineSelection,
        RewritePlayerSetting.DolbyVisionPolicy,
        RewritePlayerSetting.LibassRenderer,
        RewritePlayerSetting.BufferSizing,
        RewritePlayerSetting.Autoplay,
        RewritePlayerSetting.IntroSkip -> false
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
