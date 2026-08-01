package com.sluggyard.tv.ui.app.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.screens.player.PlayerUiState
import com.sluggyard.tv.ui.screens.player.PlayerControlActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import com.sluggyard.tv.ui.util.languageCodeToName
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Rewrite-owned ten-foot control layer; playback and source-selection mechanics stay below it. */
@Composable
fun PlayerControls(
    state: PlayerUiState,
    actions: PlayerControlActions,
    playPauseFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    currentPositionMs: Long = 0L,
    durationMs: Long = 0L,
    bufferedPositionMs: Long = 0L,
) {
    val backFocusRequester = remember { FocusRequester() }
    val scrubberFocusRequester = remember { FocusRequester() }
    val sourcesFocusRequester = remember { FocusRequester() }
    val audioFocusRequester = remember { FocusRequester() }
    val subtitlesFocusRequester = remember { FocusRequester() }
    val speedFocusRequester = remember { FocusRequester() }
    val aspectRatioFocusRequester = remember { FocusRequester() }
    val episodesFocusRequester = remember { FocusRequester() }
    val hasEpisodes = state.contentType?.lowercase() in setOf("series", "tv")
    val streamLabel = state.currentStreamName?.cleanPlayerStreamLabel()
    val selectedSubtitleLabel = state.subtitleTracks
        .firstOrNull { it.index == state.selectedSubtitleTrackIndex || it.isSelected }
        ?.let { track ->
            val language = subtitleLanguageNameForControls(track.language, track.name)
            val format = subtitleCodecTagForControls(track.codec, track.name)
            listOfNotNull(language, format).joinToString(" ")
        }
        ?: state.selectedAddonSubtitle?.getDisplayLanguage()
        ?: "Off"
    val selectedAudioLabel = state.audioTracks
        .firstOrNull { it.index == state.selectedAudioTrackIndex || it.isSelected }
        ?.name
        ?.takeIf { it.isNotBlank() }
        ?: "Audio"
    val showPausedMeta = !state.isPlaying
    val clockText = rememberClockText(state.osdClockEnabled)
    val endsAtText = rememberEndsAtText(
        enabled = state.osdClockEnabled,
        currentPositionMs = currentPositionMs,
        durationMs = durationMs,
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to SlugYardPalette.Canvas.copy(alpha = .72f),
                    0.28f to Color.Transparent,
                    0.58f to Color.Transparent,
                    1f to SlugYardPalette.Canvas.copy(alpha = .94f),
                ),
            )
            .padding(
                horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                vertical = SlugYardTvMetrics.ScreenVerticalInset,
            ),
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                PlayerIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = actions.onBack,
                    emphasis = ButtonEmphasis.SECONDARY,
                    focusRequester = backFocusRequester,
                    downFocusRequester = scrubberFocusRequester,
                )
                if (!showPausedMeta) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = state.contentName ?: state.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = SlugYardPalette.OnCanvas,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        streamLabel?.let {
                            Text(
                                it,
                                color = SlugYardPalette.OnCanvasMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (clockText != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(clockText, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    endsAtText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SlugYardPalette.OnCanvasMuted)
                    }
                }
            }
        }

        if (showPausedMeta) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.5f)
                    .padding(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val heading = state.contentName ?: state.title
                val logoUrl = state.logo?.takeIf { it.isNotBlank() }
                if (logoUrl != null) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = heading,
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .heightIn(max = 104.dp),
                        alignment = Alignment.CenterStart,
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    OutlinedPlayerText(
                        text = heading,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                    )
                }
                state.title.takeIf { it.isNotBlank() && it != state.contentName }?.let {
                    OutlinedPlayerText(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        outlineWidth = 3f,
                        maxLines = 2,
                    )
                }
                PauseRatingRow(imdbRating = state.imdbRating, tmdbRating = state.tmdbVoteAverage)
                state.description?.takeIf { it.isNotBlank() }?.let {
                    OutlinedPlayerText(
                        text = it,
                        style = PauseDescriptionTextStyle,
                        color = Color.White.copy(alpha = 0.94f),
                        outlineWidth = 3f,
                        maxLines = 4,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PlayerIconButton(
                    icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    onClick = actions.onPlayPause,
                    emphasis = ButtonEmphasis.PRIMARY,
                    focusRequester = playPauseFocusRequester,
                    upFocusRequester = backFocusRequester,
                    downFocusRequester = subtitlesFocusRequester,
                    rightFocusRequester = scrubberFocusRequester,
                )
                PlayerScrubber(
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    bufferedPositionMs = bufferedPositionMs,
                    previewPositionMs = state.pendingPreviewSeekPosition,
                    onSeekPreview = actions.onSeekPreview,
                    onSeekCommit = actions.onSeekCommit,
                    onConfirm = {
                        // After a long scrub focus stays on the bar; OK used to do nothing
                        // while the media Play/Pause key still worked — map Center/Enter
                        // to play/pause (and commit any in-flight preview seek first).
                        if (state.pendingPreviewSeekPosition != null) {
                            actions.onSeekCommit()
                        }
                        actions.onPlayPause()
                    },
                    focusRequester = scrubberFocusRequester,
                    upFocusRequester = backFocusRequester,
                    downFocusRequester = subtitlesFocusRequester,
                    leftFocusRequester = playPauseFocusRequester,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerLabeledIconButton(
                        icon = Icons.Filled.Subtitles,
                        label = selectedSubtitleLabel,
                        onClick = actions.onSubtitles,
                        focusRequester = subtitlesFocusRequester,
                        upFocusRequester = scrubberFocusRequester,
                        leftFocusRequester = playPauseFocusRequester,
                        rightFocusRequester = audioFocusRequester,
                    )
                    PlayerLabeledIconButton(
                        icon = Icons.Filled.Audiotrack,
                        label = selectedAudioLabel,
                        onClick = actions.onAudio,
                        focusRequester = audioFocusRequester,
                        upFocusRequester = scrubberFocusRequester,
                        leftFocusRequester = subtitlesFocusRequester,
                        rightFocusRequester = if (hasEpisodes) episodesFocusRequester else sourcesFocusRequester,
                    )
                    if (hasEpisodes) {
                        PlayerLabeledIconButton(
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            label = "Episodes",
                            onClick = actions.onEpisodes,
                            focusRequester = episodesFocusRequester,
                            upFocusRequester = scrubberFocusRequester,
                            leftFocusRequester = audioFocusRequester,
                            rightFocusRequester = sourcesFocusRequester,
                        )
                    }
                    PlayerLabeledIconButton(
                        icon = Icons.Filled.Layers,
                        label = "Sources",
                        onClick = actions.onSources,
                        focusRequester = sourcesFocusRequester,
                        upFocusRequester = scrubberFocusRequester,
                        leftFocusRequester = if (hasEpisodes) episodesFocusRequester else audioFocusRequester,
                        rightFocusRequester = aspectRatioFocusRequester,
                    )
                    PlayerLabeledIconButton(
                        icon = Icons.Filled.AspectRatio,
                        label = stringResource(state.aspectMode.labelResId),
                        onClick = actions.onToggleAspectRatio,
                        focusRequester = aspectRatioFocusRequester,
                        upFocusRequester = scrubberFocusRequester,
                        leftFocusRequester = sourcesFocusRequester,
                        rightFocusRequester = speedFocusRequester,
                    )
                    PlayerLabeledIconButton(
                        icon = Icons.Filled.Speed,
                        label = "${state.playbackSpeed}×",
                        onClick = actions.onSpeed,
                        focusRequester = speedFocusRequester,
                        upFocusRequester = scrubberFocusRequester,
                        leftFocusRequester = aspectRatioFocusRequester,
                    )
                }
            }
        }
    }
}

/**
 * Focusable, D-pad-scrubbable transport bar. LEFT/RIGHT while focused preview-seeks in
 * accelerating steps (repeat-aware); releasing the key commits the seek. This is the primary
 * fine-grained seek entry point for the rewrite controls -- the Rewind10/Forward10 buttons only
 * cover fixed 10s jumps.
 */
@Composable
private fun PlayerScrubber(
    currentPositionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
    previewPositionMs: Long?,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onConfirm: () -> Unit = {},
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val displayPositionMs = previewPositionMs ?: currentPositionMs
    val progress = if (durationMs > 0) (displayPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedProgress = if (durationMs > 0) {
        (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val isScrubbing = previewPositionMs != null
    val animatedProgress by animateFloatAsState(
        progress,
        animationSpec = if (isScrubbing) tween(0) else tween(150),
        label = "scrubber progress",
    )
    val trackHeight = if (isFocused) 8.dp else 4.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .focusRequester(focusRequester)
                .then(
                    if (upFocusRequester != null || downFocusRequester != null || leftFocusRequester != null) {
                        Modifier.focusProperties {
                            upFocusRequester?.let { up = it }
                            downFocusRequester?.let { down = it }
                            leftFocusRequester?.let { left = it }
                        }
                    } else Modifier
                )
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_UP) {
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (isScrubbing) onSeekCommit()
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER,
                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                            -> {
                                onConfirm()
                                true
                            }
                            else -> false
                        }
                    } else if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val repeatCount = native.repeatCount
                                val stepMs = when {
                                    repeatCount >= 8 -> 30_000L
                                    repeatCount >= 3 -> 20_000L
                                    else -> 10_000L
                                }
                                val isLeft = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                                onSeekPreview(if (isLeft) -stepMs else stepMs)
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER,
                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                            -> true
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackWidth = maxWidth
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(50)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SlugYardPalette.OnCanvasMuted.copy(alpha = .28f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(bufferedProgress)
                        .fillMaxHeight()
                        .background(SlugYardPalette.OnCanvasMuted.copy(alpha = .5f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .background(if (isScrubbing) SlugYardPalette.OnCanvas else SlugYardPalette.Accent),
                )
            }
            if (isFocused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = trackWidth * animatedProgress - 7.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (isScrubbing) SlugYardPalette.OnCanvas else SlugYardPalette.Accent),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatPlayerTime(displayPositionMs),
                color = if (isScrubbing) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatPlayerTime(durationMs),
                color = SlugYardPalette.OnCanvasMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Pause-overlay synopsis face. A serif voice reads as editorial next to the sans-serif transport
 * chrome, and the generous line height keeps it legible from a couch.
 */
private val PauseDescriptionTextStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 25.sp,
    letterSpacing = 0.15.sp,
)

/**
 * Paused chrome sits directly on video frames of unknown brightness, so every line gets a real
 * black stroke behind the fill rather than a drop shadow.
 */
@Composable
private fun OutlinedPlayerText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    outlineWidth: Float = 4.5f,
    maxLines: Int = Int.MAX_VALUE,
) {
    Box(modifier = modifier) {
        Text(
            text = text,
            style = style.copy(
                color = Color.Black,
                drawStyle = Stroke(width = outlineWidth, join = StrokeJoin.Round, cap = StrokeCap.Round),
            ),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PauseRatingRow(imdbRating: Float?, tmdbRating: Double?) {
    val badges = buildList {
        imdbRating?.takeIf { it > 0f }?.let { add("IMDb" to formatPlayerRating(it.toDouble())) }
        tmdbRating?.takeIf { it > 0.0 }?.let { add("TMDB" to formatPlayerRating(it)) }
    }
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { (source, value) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = .58f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    source,
                    style = MaterialTheme.typography.labelSmall,
                    color = SlugYardPalette.Accent,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "★ $value",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun formatPlayerRating(value: Double): String = String.format(Locale.US, "%.1f", value)

private enum class ButtonEmphasis { PRIMARY, SECONDARY, SUBORDINATE }

private fun String.cleanPlayerStreamLabel(): String =
    replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(96)
        .ifBlank { "Selected stream" }

private fun formatPlayerTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    emphasis: ButtonEmphasis,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, animationSpec = tween(160), label = "$contentDescription focus scale")
    val diameter = when (emphasis) {
        ButtonEmphasis.PRIMARY -> 44.dp
        ButtonEmphasis.SECONDARY -> 36.dp
        ButtonEmphasis.SUBORDINATE -> 32.dp
    }
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    val background = when {
        focused && emphasis == ButtonEmphasis.PRIMARY -> Color.White
        focused -> SlugYardPalette.Accent
        emphasis == ButtonEmphasis.PRIMARY -> Color.White.copy(alpha = 0.92f)
        else -> SlugYardPalette.SurfaceElevated.copy(alpha = 0.85f)
    }
    val tint = when {
        focused && emphasis == ButtonEmphasis.PRIMARY -> Color(0xFF141414)
        focused -> Color(0xFF141414)
        emphasis == ButtonEmphasis.PRIMARY -> Color(0xFF141414)
        else -> SlugYardPalette.OnCanvas
    }

    Box(
        modifier = Modifier
            .size(diameter)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (upFocusRequester != null || downFocusRequester != null || leftFocusRequester != null || rightFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                        leftFocusRequester?.let { left = it }
                        rightFocusRequester?.let { right = it }
                    }
                } else Modifier
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(background, shape)
            .border(
                SlugYardTvMetrics.FocusRingWidth,
                if (focused) SlugYardPalette.FocusRing else Color.Transparent,
                shape,
            )
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (emphasis == ButtonEmphasis.PRIMARY) 22.dp else 18.dp),
        )
    }
}

@Composable
private fun PlayerLabeledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, animationSpec = tween(160), label = "$label focus scale")
    val shortLabel = label.take(18)
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (upFocusRequester != null || downFocusRequester != null || leftFocusRequester != null || rightFocusRequester != null) {
                    Modifier.focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                        leftFocusRequester?.let { left = it }
                        rightFocusRequester?.let { right = it }
                    }
                } else Modifier
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(shape)
                .background(if (focused) Color.White else SlugYardPalette.SurfaceElevated.copy(alpha = 0.9f), shape)
                .border(
                    SlugYardTvMetrics.FocusRingWidth,
                    if (focused) SlugYardPalette.FocusRing else Color.Transparent,
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (focused) Color(0xFF141414) else SlugYardPalette.OnCanvas,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            shortLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (focused) Color.White else SlugYardPalette.OnCanvasMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberClockText(enabled: Boolean): String? {
    if (!enabled) return null
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(now))
}

@Composable
private fun rememberEndsAtText(enabled: Boolean, currentPositionMs: Long, durationMs: Long): String? {
    if (!enabled || durationMs <= 0L) return null
    val remaining = (durationMs - currentPositionMs).coerceAtLeast(0L)
    val end = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + remaining }
    val formatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(end.time)
    return "Ends at $formatted"
}

private fun subtitleLanguageNameForControls(language: String?, fallback: String): String {
    language?.takeIf(String::isNotBlank)?.let { return languageCodeToName(it) }
    return if (fallback.matches(Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,4})?"))) {
        languageCodeToName(fallback)
    } else {
        fallback
    }
}

private fun subtitleCodecTagForControls(codec: String?, name: String?): String? {
    val haystack = listOfNotNull(codec, name).joinToString(" ").lowercase(Locale.US)
    return when {
        "pgs" in haystack || "sup" in haystack || "hdmv" in haystack -> "PGS"
        "ass" in haystack || "ssa" in haystack -> "ASS"
        "srt" in haystack || "subrip" in haystack -> "SRT"
        "vtt" in haystack || "webvtt" in haystack -> "VTT"
        "ttml" in haystack -> "TTML"
        "dvb" in haystack -> "DVB"
        codec.isNullOrBlank() -> null
        else -> codec.substringAfterLast('/').uppercase(Locale.US).take(8)
    }
}
