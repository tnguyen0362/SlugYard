package com.sluggyard.tv.ui.rewrite.settings

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

@Composable
internal fun DisplaySettings(facade: RewriteSettingsFacade, player: PlayerSettings, scope: CoroutineScope) {
    RewriteSettingsGroup("Display matching") {
        RewriteCycleRow("Frame-rate matching", player.frameRateMatchingMode.name, FrameRateMatchingMode.entries.map { it.name }) { value -> scope.launch { facade.setFrameRateMatchingMode(FrameRateMatchingMode.valueOf(value)) } }
        RewriteToggleRow("Resolution matching", "Match the display to the source resolution when supported.", player.resolutionMatchingEnabled) { scope.launch { facade.setResolutionMatchingEnabled(it) } }
        RewriteCycleRow("Resize mode", resizeModeLabel(player.resizeMode), listOf("Fit", "Fill", "Stretch", "Center", "Automatic")) { value -> scope.launch { facade.setResizeMode(listOf(0, 1, 2, 3, 4)[listOf("Fit", "Fill", "Stretch", "Center", "Automatic").indexOf(value)]) } }
    }
}

@Composable
internal fun SubtitleSettings(facade: RewriteSettingsFacade, player: PlayerSettings, scope: CoroutineScope) {
    val style = player.subtitleStyle
    val languages = AVAILABLE_SUBTITLE_LANGUAGES.map { it.code }
    RewriteSettingsGroup("Subtitle selection") {
        RewriteCycleRow("Preferred language", style.preferredLanguage, languages, valueLabel = ::languageOptionLabel) { scope.launch { facade.setSubtitlePreferredLanguage(it) } }
        RewriteCycleRow("Secondary language", style.secondaryPreferredLanguage ?: "None", listOf("None") + languages, valueLabel = ::languageOptionLabel) { value -> scope.launch { facade.setSubtitleSecondaryLanguage(value.takeUnless { it == "None" }) } }
        RewriteToggleRow("Use forced subtitles", "Prefer forced subtitle tracks when available.", style.useForcedSubtitles) { scope.launch { facade.setUseForcedSubtitles(it) } }
        RewriteToggleRow("Preferred languages only", "Hide subtitle tracks outside your selected languages.", style.showOnlyPreferredLanguages) { scope.launch { facade.setSubtitleShowOnlyPreferredLanguages(it) } }
    }
    RewriteSettingsGroup("Subtitle appearance") {
        RewriteCycleRow("Size", "${style.size}%", listOf(80, 100, 120, 140, 160, 180).map { "$it%" }) { value -> scope.launch { facade.setSubtitleSize(value.removeSuffix("%").toInt()) } }
        RewriteCycleRow("Position", "${style.verticalOffset}%", listOf(-10, 0, 5, 15, 25, 40).map { "$it%" }) { value -> scope.launch { facade.setSubtitleVerticalOffset(value.removeSuffix("%").toInt()) } }
        RewriteToggleRow("Bold text", "Increase subtitle weight for distance viewing.", style.bold) { scope.launch { facade.setSubtitleBold(it) } }
        RewriteToggleRow("Outline", "Draw a readable edge around subtitle glyphs.", style.outlineEnabled) { scope.launch { facade.setSubtitleOutlineEnabled(it) } }
        RewriteCycleRow("Outline width", "${style.outlineWidth}px", (1..5).map { "${it}px" }) { value -> scope.launch { facade.setSubtitleOutlineWidth(value.removeSuffix("px").toInt()) } }
    }
}

@Composable
internal fun AudioSettings(facade: RewriteSettingsFacade, player: PlayerSettings, scope: CoroutineScope) {
    RewriteSettingsGroup("Audio selection") {
        RewriteCycleRow("Preferred language", player.preferredAudioLanguage, listOf(AudioLanguageOption.DEVICE, AudioLanguageOption.DEFAULT, AudioLanguageOption.ORIGINAL) + AVAILABLE_SUBTITLE_LANGUAGES.map { it.code }, valueLabel = ::languageOptionLabel) { scope.launch { facade.setPreferredAudioLanguage(it) } }
        RewriteCycleRow("Secondary language", player.secondaryPreferredAudioLanguage ?: "None", listOf("None", AudioLanguageOption.DEVICE, AudioLanguageOption.ORIGINAL) + AVAILABLE_SUBTITLE_LANGUAGES.map { it.code }, valueLabel = ::languageOptionLabel) { value -> scope.launch { facade.setSecondaryPreferredAudioLanguage(value.takeUnless { it == "None" }) } }
        RewriteToggleRow("Downmix", "Convert multichannel audio to the selected output layout.", player.downmixEnabled) { scope.launch { facade.setDownmixEnabled(it) } }
        RewriteCycleRow("Output channels", player.audioOutputChannels.displayLabel, AudioOutputChannels.entries.map { it.displayLabel }) { value -> scope.launch { facade.setAudioOutputChannels(AudioOutputChannels.entries.first { it.displayLabel == value }) } }
        RewriteToggleRow("Preserve original mix", "Keep the original channel balance while downmixing.", player.maintainOriginalAudioOnDownmix) { scope.launch { facade.setMaintainOriginalAudioOnDownmix(it) } }
    }
    RewriteSettingsGroup("Device output") {
        RewriteToggleRow("Tunneling", "Allow the device to use its hardware audio path.", player.tunnelingEnabled) { scope.launch { facade.setTunnelingEnabled(it) } }
        RewriteToggleRow("Optical passthrough", "Preserve encoded audio for optical output devices.", player.forceOpticalPassthrough) { scope.launch { facade.setForceOpticalPassthrough(it) } }
    }
}

@Composable
internal fun AdvancedSettings(player: PlayerSettings) {
    RewriteSettingsGroup("Automatic playback policy") {
        RewriteValueRow("Engine", "Automatic")
        RewriteValueRow("Buffering", if (player.bufferEngineEnabled) "Managed automatically" else "Standard")
        RewriteValueRow("Dolby Vision", "Automatic")
        RewriteValueRow("Cache", if (player.vodCacheEnabled) "Enabled" else "Managed automatically")
        RewriteValueRow("Diagnostics", if (player.playbackIssueReportsEnabled) "Opted in" else "Local only")
        RewriteValueRow("Performance", if (player.exoPerformanceModeEnabled) "Enabled" else "Automatic")
    }
}

@Composable
internal fun AboutSettings() {
    RewriteSettingsGroup("About SlugYard") {
        RewriteValueRow("Application", "SlugYard TV")
        RewriteValueRow("Version", com.sluggyard.tv.BuildConfig.VERSION_NAME)
        RewriteValueRow("Settings model", "Locked-down SlugYard TV")
        RewriteValueRow("Playback", "Automatic dual-player fallback")
        RewriteValueRow("Privacy", "Credentials remain on this device profile")
    }
}
