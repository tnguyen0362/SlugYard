package com.sluggyard.tv.ui.app.settings

import androidx.compose.runtime.Composable
import com.sluggyard.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.sluggyard.tv.data.local.AudioLanguageOption
import com.sluggyard.tv.data.local.AudioOutputChannels
import com.sluggyard.tv.data.local.FrameRateMatchingMode
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.ui.util.languageCodeToName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun languageOptionLabel(code: String): String = when (code) {
    "None" -> "None"
    AudioLanguageOption.DEVICE -> "Device language"
    AudioLanguageOption.DEFAULT -> "Media default"
    AudioLanguageOption.ORIGINAL -> "Original language"
    else -> languageCodeToName(code)
}

private fun FrameRateMatchingMode.label(): String = when (this) {
    FrameRateMatchingMode.OFF -> "Off"
    FrameRateMatchingMode.START -> "Match on start"
    FrameRateMatchingMode.START_STOP -> "Match frame rate"
}

@Composable
internal fun DisplaySettings(facade: SettingsFacade, player: PlayerSettings, scope: CoroutineScope) {
    SettingsGroup("Display matching") {
        CycleRow("Frame-rate matching", player.frameRateMatchingMode.label(), FrameRateMatchingMode.entries.map { it.label() }) { value -> scope.launch { facade.setFrameRateMatchingMode(FrameRateMatchingMode.entries.first { it.label() == value }) } }
        ToggleRow("Resolution matching", "Match the display to the source resolution when supported.", player.resolutionMatchingEnabled) { scope.launch { facade.setResolutionMatchingEnabled(it) } }
        CycleRow("Resize mode", resizeModeLabel(player.resizeMode), listOf("Fit", "Fill", "Stretch", "Center", "Automatic")) { value -> scope.launch { facade.setResizeMode(listOf(0, 1, 2, 3, 4)[listOf("Fit", "Fill", "Stretch", "Center", "Automatic").indexOf(value)]) } }
    }
}

@Composable
internal fun SubtitleSettings(facade: SettingsFacade, player: PlayerSettings, scope: CoroutineScope) {
    val style = player.subtitleStyle
    val languages = AVAILABLE_SUBTITLE_LANGUAGES.map { it.code }
    SettingsGroup("Subtitle selection") {
        CycleRow("Preferred language", style.preferredLanguage, languages, valueLabel = ::languageOptionLabel) { scope.launch { facade.setSubtitlePreferredLanguage(it) } }
        CycleRow("Secondary language", style.secondaryPreferredLanguage ?: "None", listOf("None") + languages, valueLabel = ::languageOptionLabel) { value -> scope.launch { facade.setSubtitleSecondaryLanguage(value.takeUnless { it == "None" }) } }
        ToggleRow("Use forced subtitles", "Prefer forced subtitle tracks when available.", style.useForcedSubtitles) { scope.launch { facade.setUseForcedSubtitles(it) } }
        ToggleRow("Preferred languages only", "Hide subtitle tracks outside your selected languages.", style.showOnlyPreferredLanguages) { scope.launch { facade.setSubtitleShowOnlyPreferredLanguages(it) } }
    }
    SettingsGroup("Subtitle appearance") {
        CycleRow("Size", "${style.size}%", listOf(80, 100, 120, 140, 160, 180).map { "$it%" }) { value -> scope.launch { facade.setSubtitleSize(value.removeSuffix("%").toInt()) } }
        CycleRow("Position", "${style.verticalOffset}%", listOf(-10, 0, 5, 15, 25, 40).map { "$it%" }) { value -> scope.launch { facade.setSubtitleVerticalOffset(value.removeSuffix("%").toInt()) } }
        ToggleRow("Bold text", "Increase subtitle weight for distance viewing.", style.bold) { scope.launch { facade.setSubtitleBold(it) } }
        ToggleRow("Outline", "Draw a readable edge around subtitle glyphs.", style.outlineEnabled) { scope.launch { facade.setSubtitleOutlineEnabled(it) } }
        CycleRow("Outline width", "${style.outlineWidth}px", (1..5).map { "${it}px" }) { value -> scope.launch { facade.setSubtitleOutlineWidth(value.removeSuffix("px").toInt()) } }
    }
}

@Composable
internal fun AudioSettings(facade: SettingsFacade, player: PlayerSettings, scope: CoroutineScope) {
    SettingsGroup("Audio selection") {
        CycleRow("Preferred language", player.preferredAudioLanguage, listOf(AudioLanguageOption.DEVICE, AudioLanguageOption.DEFAULT, AudioLanguageOption.ORIGINAL) + AVAILABLE_SUBTITLE_LANGUAGES.map { it.code }, valueLabel = ::languageOptionLabel) { scope.launch { facade.setPreferredAudioLanguage(it) } }
        CycleRow("Secondary language", player.secondaryPreferredAudioLanguage ?: "None", listOf("None", AudioLanguageOption.DEVICE, AudioLanguageOption.ORIGINAL) + AVAILABLE_SUBTITLE_LANGUAGES.map { it.code }, valueLabel = ::languageOptionLabel) { value -> scope.launch { facade.setSecondaryPreferredAudioLanguage(value.takeUnless { it == "None" }) } }
        ToggleRow("Downmix", "Convert multichannel audio to the selected output layout.", player.downmixEnabled) { scope.launch { facade.setDownmixEnabled(it) } }
        CycleRow("Output channels", player.audioOutputChannels.displayLabel, AudioOutputChannels.entries.map { it.displayLabel }) { value -> scope.launch { facade.setAudioOutputChannels(AudioOutputChannels.entries.first { it.displayLabel == value }) } }
        ToggleRow("Preserve original mix", "Keep the original channel balance while downmixing.", player.maintainOriginalAudioOnDownmix) { scope.launch { facade.setMaintainOriginalAudioOnDownmix(it) } }
    }
    SettingsGroup("Device output") {
        ToggleRow("Tunneling", "Allow the device to use its hardware audio path.", player.tunnelingEnabled) { scope.launch { facade.setTunnelingEnabled(it) } }
        ToggleRow("Optical passthrough", "Preserve encoded audio for optical output devices.", player.forceOpticalPassthrough) { scope.launch { facade.setForceOpticalPassthrough(it) } }
    }
}

@Composable
internal fun AdvancedSettings(player: PlayerSettings) {
    SettingsGroup("Automatic playback policy") {
        ValueRow("Engine", "Automatic")
        ValueRow("Buffering", if (player.bufferEngineEnabled) "Managed automatically" else "Standard")
        ValueRow("Dolby Vision", "Automatic")
        ValueRow("Cache", if (player.vodCacheEnabled) "Enabled" else "Managed automatically")
        ValueRow("Diagnostics", if (player.playbackIssueReportsEnabled) "Opted in" else "Local only")
        ValueRow("Performance", if (player.exoPerformanceModeEnabled) "Enabled" else "Automatic")
    }
}

@Composable
internal fun AboutSettings() {
    SettingsGroup("About SlugYard") {
        ValueRow("Application", "SlugYard TV")
        ValueRow("Version", com.sluggyard.tv.BuildConfig.VERSION_NAME)
        ValueRow("Playback", "Automatic dual-player fallback")
        ValueRow("Privacy", "Credentials are stored securely on this device.")
    }
}
