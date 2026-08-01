package com.sluggyard.tv.ui.app.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PlayerControlActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerControlsFocusAndroidTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun downFromPlayMovesToSecondaryControls() {
        val actions = PlayerControlActions(
            onPlayPause = {},
            onSeekForward = {},
            onSeekBackward = {},
            onSources = {},
            onEpisodes = {},
            onAudio = {},
            onSubtitles = {},
            onSpeed = {},
            onBack = {},
        )

        composeRule.setContent {
            val playFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                playFocusRequester.requestPlayerFocus()
            }
            PlayerControls(
                state = PlayerUiState(contentType = "series"),
                actions = actions,
                playPauseFocusRequester = playFocusRequester,
            )
        }

        composeRule.onNodeWithContentDescription("Play").assertIsFocused()
        composeRule.onNodeWithContentDescription("Play").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithContentDescription("Sources").assertIsFocused()
    }

    @Test
    fun controlsExposeAllSeriesActionsToDpadFocus() {
        val actions = PlayerControlActions(
            onPlayPause = {},
            onSeekForward = {},
            onSeekBackward = {},
            onSources = {},
            onEpisodes = {},
            onAudio = {},
            onSubtitles = {},
            onSpeed = {},
            onBack = {},
        )

        composeRule.setContent {
            val playFocusRequester = remember { FocusRequester() }
            PlayerControls(
                state = PlayerUiState(contentType = "series"),
                actions = actions,
                playPauseFocusRequester = playFocusRequester,
            )
        }

        composeRule.onNodeWithContentDescription("Episodes")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
    }
}
