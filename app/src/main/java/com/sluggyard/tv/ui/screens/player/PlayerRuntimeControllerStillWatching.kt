package com.sluggyard.tv.ui.screens.player

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val STILL_WATCHING_COUNTDOWN_SECONDS = 60
private const val STILL_WATCHING_COUNTDOWN_TICK_MS = 1_000L

internal fun shouldEnterStillWatchingPrompt(
    stillWatchingEnabled: Boolean,
    autoPlayNextEpisodeEnabled: Boolean,
    nextEpisodeHasAired: Boolean,
    consecutiveAutoPlayCount: Int,
    threshold: Int,
): Boolean {
    if (!stillWatchingEnabled) return false
    if (!autoPlayNextEpisodeEnabled) return false
    if (!nextEpisodeHasAired) return false
    return consecutiveAutoPlayCount >= threshold
}

internal fun PlayerRuntimeController.enterStillWatchingPromptMode() {
    val upcoming = _uiState.value.nextEpisode ?: return
    val alreadyActive = _uiState.value.postPlayMode is PostPlayMode.StillWatching &&
        stillWatchingPromptJob?.isActive == true
    if (alreadyActive) return

    pauseForStillWatchingPrompt()
    stillWatchingPromptJob?.cancel()

    _uiState.update { current ->
        current.copy(
            postPlayMode = PostPlayMode.StillWatching(
                nextEpisode = upcoming,
                countdownSec = STILL_WATCHING_COUNTDOWN_SECONDS,
            ),
            activeSkipInterval = null,
            showControls = false,
        )
    }

    val countdownJob = scope.launch {
        try {
            coroutineScope {
                var remaining = STILL_WATCHING_COUNTDOWN_SECONDS - 1
                while (remaining >= 0) {
                    delay(STILL_WATCHING_COUNTDOWN_TICK_MS)
                    _uiState.update { state ->
                        val active = state.postPlayMode as? PostPlayMode.StillWatching
                            ?: return@update state
                        state.copy(postPlayMode = active.copy(countdownSec = remaining))
                    }
                    remaining--
                }
            }
            if (_uiState.value.postPlayMode is PostPlayMode.StillWatching) {
                exitFromStillWatching()
            }
        } finally {
            if (stillWatchingPromptJob === coroutineContext.job) {
                stillWatchingPromptJob = null
            }
        }
    }
    stillWatchingPromptJob = countdownJob
}

internal fun PlayerRuntimeController.onStillWatchingContinue() {
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    consecutiveAutoPlayCount = 0
    _uiState.update { it.copy(postPlayMode = null) }
    playNextEpisode(userInitiated = true)
}

internal fun PlayerRuntimeController.onDismissStillWatchingPrompt() {
    exitFromStillWatching()
}

internal fun PlayerRuntimeController.exitFromStillWatching() {
    consecutiveAutoPlayCount = 0
    resetPostPlayOverlayState(clearEpisode = true)
    _uiState.update { it.copy(pendingExitReason = PlayerExitReason.StillWatchingPrompt) }
}