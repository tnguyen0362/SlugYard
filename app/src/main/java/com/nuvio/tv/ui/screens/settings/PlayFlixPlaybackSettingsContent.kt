package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
fun PlayFlixPlaybackSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: PlaybackSettingsViewModel = hiltViewModel()
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    var showSubtitleLanguageDialog by remember { mutableStateOf(false) }
    var showAudioLanguageDialog by remember { mutableStateOf(false) }
    var showFrameRateDialog by remember { mutableStateOf(false) }
    val settings = playerSettings

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            item(key = "header") {
                SettingsDetailHeader(
                    title = stringResource(R.string.playflix_playback_title),
                    subtitle = stringResource(R.string.playflix_playback_subtitle)
                )
            }

            item(key = "languages") {
                SettingsGroupCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.playflix_languages)
                ) {
                    SettingsActionRow(
                        title = stringResource(R.string.playflix_subtitle_language),
                        subtitle = stringResource(R.string.playflix_subtitle_language_subtitle),
                        value = AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == settings?.subtitleStyle?.preferredLanguage }?.displayName
                            ?: stringResource(R.string.settings_default),
                        leadingIcon = Icons.Default.Subtitles,
                        onClick = { showSubtitleLanguageDialog = true },
                        enabled = settings != null,
                        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.playflix_audio_language),
                        subtitle = stringResource(R.string.playflix_audio_language_subtitle),
                        value = when (settings?.preferredAudioLanguage) {
                            AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_language_default)
                            AudioLanguageOption.DEVICE -> stringResource(R.string.audio_language_device)
                            AudioLanguageOption.ORIGINAL -> stringResource(R.string.audio_language_original)
                            else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == settings?.preferredAudioLanguage }?.displayName
                                ?: stringResource(R.string.settings_default)
                        },
                        leadingIcon = Icons.Default.ClosedCaption,
                        onClick = { showAudioLanguageDialog = true },
                        enabled = settings != null
                    )
                }
            }

            item(key = "performance") {
                SettingsGroupCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.playflix_performance)
                ) {
                    SettingsActionRow(
                        title = stringResource(R.string.playflix_match_frame_rate),
                        subtitle = stringResource(R.string.playflix_match_frame_rate_subtitle),
                        value = when (settings?.frameRateMatchingMode) {
                            FrameRateMatchingMode.OFF -> stringResource(R.string.playback_afr_off)
                            FrameRateMatchingMode.START -> stringResource(R.string.playback_afr_on_start)
                            FrameRateMatchingMode.START_STOP -> stringResource(R.string.playback_afr_on_start_stop)
                            null -> ""
                        },
                        leadingIcon = Icons.Default.Speed,
                        onClick = { showFrameRateDialog = true },
                        enabled = settings != null
                    )
                }
            }
        }

        if (showSubtitleLanguageDialog && settings != null) {
            NuvioDialog(
                onDismiss = { showSubtitleLanguageDialog = false },
                title = stringResource(R.string.playflix_subtitle_language),
                content = {
                    LazyColumn {
                        items(AVAILABLE_SUBTITLE_LANGUAGES.size) { index ->
                            val lang = AVAILABLE_SUBTITLE_LANGUAGES[index]
                            SettingsActionRow(
                                title = lang.displayName,
                                subtitle = null,
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setSubtitlePreferredLanguage(lang.code)
                                        showSubtitleLanguageDialog = false
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }

        if (showAudioLanguageDialog && settings != null) {
            NuvioDialog(
                onDismiss = { showAudioLanguageDialog = false },
                title = stringResource(R.string.playflix_audio_language),
                content = {
                    LazyColumn {
                        item {
                            SettingsActionRow(
                                title = stringResource(R.string.audio_language_default),
                                subtitle = stringResource(R.string.audio_language_default_subtitle),
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setPreferredAudioLanguage(AudioLanguageOption.DEFAULT)
                                        showAudioLanguageDialog = false
                                    }
                                }
                            )
                        }
                        item {
                            SettingsActionRow(
                                title = stringResource(R.string.audio_language_device),
                                subtitle = stringResource(R.string.audio_language_device_subtitle),
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setPreferredAudioLanguage(AudioLanguageOption.DEVICE)
                                        showAudioLanguageDialog = false
                                    }
                                }
                            )
                        }
                        item {
                            SettingsActionRow(
                                title = stringResource(R.string.audio_language_original),
                                subtitle = stringResource(R.string.audio_language_original_subtitle),
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setPreferredAudioLanguage(AudioLanguageOption.ORIGINAL)
                                        showAudioLanguageDialog = false
                                    }
                                }
                            )
                        }
                        items(AVAILABLE_SUBTITLE_LANGUAGES.size) { index ->
                            val lang = AVAILABLE_SUBTITLE_LANGUAGES[index]
                            SettingsActionRow(
                                title = lang.displayName,
                                subtitle = null,
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setPreferredAudioLanguage(lang.code)
                                        showAudioLanguageDialog = false
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }

        if (showFrameRateDialog && settings != null) {
            NuvioDialog(
                onDismiss = { showFrameRateDialog = false },
                title = stringResource(R.string.playflix_match_frame_rate),
                content = {
                    LazyColumn {
                        item {
                            SettingsActionRow(
                                title = stringResource(R.string.playback_afr_off),
                                subtitle = stringResource(R.string.playback_afr_off_sub),
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setFrameRateMatchingMode(FrameRateMatchingMode.OFF)
                                        showFrameRateDialog = false
                                    }
                                }
                            )
                        }
                        item {
                            SettingsActionRow(
                                title = stringResource(R.string.playback_afr_on_start),
                                subtitle = stringResource(R.string.playback_afr_on_start_sub),
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setFrameRateMatchingMode(FrameRateMatchingMode.START)
                                        showFrameRateDialog = false
                                    }
                                }
                            )
                        }
                        item {
                            SettingsActionRow(
                                title = stringResource(R.string.playback_afr_on_start_stop),
                                subtitle = stringResource(R.string.playback_afr_on_start_stop_sub),
                                onClick = {
                                    coroutineScope.launch {
                                        viewModel.setFrameRateMatchingMode(FrameRateMatchingMode.START_STOP)
                                        showFrameRateDialog = false
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }

    }
}
