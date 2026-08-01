@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.ui.theme.SlugYardTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.sluggyard.tv.R

@Composable
fun NextEpisodeEndPromptOverlay(
    nextEpisode: NextEpisodeInfo,
    onContinue: () -> Unit,
    onReturnToDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val continueFocusRequester = remember { FocusRequester() }
    val returnFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val nextEpisodeText = nextEpisodeEndPromptLabel(nextEpisode)

    LaunchedEffect(nextEpisode.videoId) {
        focusManager.clearFocus(force = true)
        repeat(3) { withFrameNanos { } }
        runCatching { continueFocusRequester.requestFocus() }
    }

    // Deliberately not a full-screen scrim: the frame the episode ended on stays visible behind a
    // bottom banner, so the prompt reads as "up next" rather than "your video was taken away".
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.92f)
                )
            )
            .zIndex(3f),
        contentAlignment = Alignment.BottomStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .padding(
                    horizontal = SlugYardTheme.spacing.xxl,
                    vertical = SlugYardTheme.spacing.xl
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlugYardTheme.spacing.lg)
        ) {
            nextEpisode.thumbnail?.takeIf { it.isNotBlank() }?.let { thumbnail ->
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .width(184.dp)
                        .height(104.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentScale = ContentScale.Crop
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SlugYardTheme.spacing.xxs)
            ) {
                Text(
                    text = stringResource(R.string.next_episode_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.56f)
                )
                Text(
                    text = nextEpisodeText,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                nextEpisode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(SlugYardTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogButton(
                    text = stringResource(R.string.player_next_episode_prompt_yes),
                    onClick = onContinue,
                    isPrimary = true,
                    modifier = Modifier
                        .focusRequester(continueFocusRequester)
                        .focusProperties { right = returnFocusRequester }
                )

                DialogButton(
                    text = stringResource(R.string.player_next_episode_prompt_no),
                    onClick = onReturnToDetails,
                    isPrimary = false,
                    modifier = Modifier
                        .focusRequester(returnFocusRequester)
                        .focusProperties { left = continueFocusRequester }
                )
            }
        }
    }
}

@Composable
private fun nextEpisodeEndPromptLabel(nextEpisode: NextEpisodeInfo): String {
    if (nextEpisode.isOtherType) return nextEpisode.title
    val episodeCode = stringResource(
        R.string.season_episode_format,
        nextEpisode.season,
        nextEpisode.episode
    )
    return "$episodeCode - ${nextEpisode.title}"
}
