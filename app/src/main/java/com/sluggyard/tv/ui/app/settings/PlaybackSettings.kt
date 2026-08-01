package com.sluggyard.tv.ui.app.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.BuildConfig
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
internal fun AboutSettings() {
    var openDoc by remember { mutableStateOf<AboutDoc?>(null) }
    SettingsGroup("About SlugYard") {
        ValueRow("Application", "SlugYard TV")
        ValueRow("Version", BuildConfig.VERSION_NAME)
        ValueRow("Playback", "Automatic dual-player fallback (ExoPlayer + mpv)")
        ActionRow(
            title = "License",
            description = "GNU General Public License v3.0 (GPLv3)",
            actionLabel = "View",
            onClick = { openDoc = AboutDoc.License },
        )
        ActionRow(
            title = "Terms of Service",
            description = "Rules for using this pre-release app.",
            actionLabel = "View",
            onClick = { openDoc = AboutDoc.Terms },
        )
        ActionRow(
            title = "Privacy Policy",
            description = "What stays on-device and what optional sync does.",
            actionLabel = "View",
            onClick = { openDoc = AboutDoc.Privacy },
        )
        ActionRow(
            title = "Credits & attribution",
            description = "ExoPlayer / Media3, mpv, Kodi downmix, NuvioTV, and more.",
            actionLabel = "View",
            onClick = { openDoc = AboutDoc.Credits },
        )
    }
    openDoc?.let { doc ->
        AlertDialog(
            onDismissRequest = { openDoc = null },
            title = {
                Text(
                    when (doc) {
                        AboutDoc.License -> "License"
                        AboutDoc.Terms -> "Terms of Service"
                        AboutDoc.Privacy -> "Privacy Policy"
                        AboutDoc.Credits -> "Credits & attribution"
                    },
                )
            },
            text = {
                Text(
                    text = when (doc) {
                        AboutDoc.License -> ABOUT_LICENSE
                        AboutDoc.Terms -> ABOUT_TERMS
                        AboutDoc.Privacy -> ABOUT_PRIVACY
                        AboutDoc.Credits -> ABOUT_CREDITS
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { openDoc = null }) { Text("Close") }
            },
        )
    }
}

private enum class AboutDoc { License, Terms, Privacy, Credits }

private val ABOUT_LICENSE = """
SlugYard is free software under the GNU General Public License, version 3.

You may copy, modify, and redistribute it under the terms of that license.
There is no warranty. Full text: LICENSE in the project repository.

https://www.gnu.org/licenses/gpl-3.0.en.html
""".trimIndent()

private val ABOUT_TERMS = """
SlugYard is a client-side Android TV app. It does not host or distribute media.

You are responsible for the content you play and for any debrid keys, addons, and third-party accounts you connect.

This is a public pre-release (beta). Features may change or break.

Full Terms: TERMS.md in the project repository
(https://github.com/tnguyen0362/SlugYard).
""".trimIndent()

private val ABOUT_PRIVACY = """
Debrid API keys stay on this device and are not uploaded with SlugYard sync.

Optional sign-in can sync profiles, watch progress, and non-secret preferences via Supabase. Trakt tokens may sync if you link Trakt. Guest mode needs no account.

Third-party services (debrid, addons, TMDB, Trakt, etc.) have their own policies.

Full Privacy Policy: PRIVACY.md in the project repository.
""".trimIndent()

private val ABOUT_CREDITS = """
Playback stack:
• ExoPlayer / AndroidX Media3 (Apache-2.0)
• mpv / libmpv-android
• Kodi-derived FFmpeg audio downmix (ffmpeg-decoder-downmix/)

Lineage:
• Substantial modifications of NuvioTV (see NOTICE)

Also: Jetpack Compose, Hilt, OkHttp, Coil, and other libraries declared in Gradle.

Full notices: NOTICE and ffmpeg-decoder-downmix/NOTICE.md
""".trimIndent()

