package com.sluggyard.tv.ui.app.watchhub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.preferTvBackdropUrl
import com.sluggyard.tv.ui.app.requestFocusReliably

/**
 * Dedicated Stremio-like WatchHub surface: shows where a title is available to stream
 * on platforms/services. Informational only — Back returns to Details.
 */
@Composable
fun WatchHubScreen(
    title: String,
    posterUrl: String?,
    platforms: List<WatchHubPlatform>,
    loading: Boolean,
    message: String? = null,
    onBack: () -> Unit,
    onInstallCommunityAddons: (() -> Unit)? = null,
    contentFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val backFocusRequester = contentFocusRequester ?: remember { FocusRequester() }
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    LaunchedEffect(loading, platforms.size, message) {
        backFocusRequester.requestFocusReliably()
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(preferTvBackdropUrl(posterUrl))
                    .size(Size(1280, 720))
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(SlugYardPalette.SurfaceElevated),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.92f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                    vertical = SlugYardTvMetrics.ScreenVerticalInset,
                ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "WatchHub",
                style = MaterialTheme.typography.titleMedium,
                color = SlugYardPalette.Accent,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Where to watch",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.92f),
            )
            when {
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Text(
                            "Checking streaming platforms…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
                platforms.isNotEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                    ) {
                        itemsIndexed(platforms, key = { _, platform -> platform.label }) { _, platform ->
                            WatchHubPlatformCard(platform)
                        }
                    }
                }
                else -> {
                    Text(
                        message
                            ?: "No streaming platforms found for this title. Connect a debrid provider for torrent sources, or install community addons to enable WatchHub.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.88f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var backFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlugYardPalette.SurfaceElevated,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .focusRequester(backFocusRequester)
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
                ) {
                    Text("Back")
                }
                if (onInstallCommunityAddons != null && platforms.isEmpty() && !loading) {
                    var installFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = onInstallCommunityAddons,
                        shape = RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlugYardPalette.Accent,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier
                            .onFocusChanged { installFocused = it.isFocused }
                            .then(
                                if (installFocused) {
                                    Modifier.border(
                                        3.dp,
                                        SlugYardPalette.FocusRing,
                                        RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Text("Install community addons")
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchHubPlatformCard(platform: WatchHubPlatform) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SlugYardPalette.SurfaceElevated.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            platform.label,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        platform.detail?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = SlugYardPalette.OnCanvasMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
