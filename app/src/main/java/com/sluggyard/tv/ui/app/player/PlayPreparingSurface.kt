package com.sluggyard.tv.ui.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.preferTvBackdropUrl

/**
 * Single play-handoff chrome: full-bleed title art + status line.
 * Used for both auto-pick ("Finding a playable stream…") and retained-player prepare
 * ("Building player…") so those phases read as one continuous window.
 */
@Composable
fun PlayPreparingSurface(
    artUrl: String?,
    title: String,
    statusMessage: String,
    modifier: Modifier = Modifier,
    showProgressSpinner: Boolean = true,
    loadingProgress: Float? = null,
    availability: List<String> = emptyList(),
    showChooser: Boolean = false,
    onChooseSource: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backFocusRequester: FocusRequester? = null,
    chooseSourceFocusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (!artUrl.isNullOrBlank()) {
            val enlargedUrl = preferTvBackdropUrl(artUrl).orEmpty()
            // Isolate prepare-art cache entries from Home card keys so a blocked Play
            // (full-bleed Spider-Man, etc.) cannot paint into recycled poster tiles.
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(enlargedUrl)
                    .size(Size(1280, 720))
                    .memoryCacheKey("play-prepare:$enlargedUrl")
                    .diskCacheKey("play-prepare:$enlargedUrl")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.85f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showProgressSpinner && !showChooser) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (!showChooser) Color.White.copy(alpha = 0.9f) else SlugYardPalette.Danger,
                )
            }
            loadingProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.38f),
                    color = SlugYardPalette.Accent,
                    trackColor = SlugYardPalette.SurfaceElevated,
                )
            }
            if (showChooser && availability.isNotEmpty()) {
                Text(
                    "Also available on ${availability.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            if (onBack != null || (showChooser && onChooseSource != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showChooser && onChooseSource != null) {
                        var chooseFocused by remember { mutableStateOf(false) }
                        Button(
                            onClick = onChooseSource,
                            shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
                            modifier = Modifier
                                .then(
                                    if (chooseSourceFocusRequester != null) {
                                        Modifier.focusRequester(chooseSourceFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                                .onFocusChanged { chooseFocused = it.isFocused }
                                .then(
                                    if (chooseFocused) {
                                        Modifier.border(
                                            3.dp,
                                            SlugYardPalette.FocusRing,
                                            RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) { Text("Choose source") }
                    }
                    if (onBack != null) {
                        var backFocused by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = onBack,
                            shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
                            modifier = Modifier
                                .then(
                                    if (backFocusRequester != null) {
                                        Modifier.focusRequester(backFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                                .onFocusChanged { backFocused = it.isFocused }
                                .then(
                                    if (backFocused) {
                                        Modifier.border(
                                            3.dp,
                                            SlugYardPalette.FocusRing,
                                            RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) { Text("Back") }
                    }
                }
            }
        }
    }
}

/** Prefer landscape backdrop for full-bleed prepare chrome; fall back to poster. */
fun playPreparingArtUrl(backdropUrl: String?, posterUrl: String?): String? =
    backdropUrl?.takeIf { it.isNotBlank() } ?: posterUrl?.takeIf { it.isNotBlank() }
