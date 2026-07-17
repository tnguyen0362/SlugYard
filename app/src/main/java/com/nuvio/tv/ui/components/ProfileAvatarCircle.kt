package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun ProfileAvatarCircle(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    isSelected: Boolean = false,
    avatarImageUrl: String? = null,
    imageCrossfade: Boolean = true
) {
    val avatarColor = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val fontSize = (size.value * 0.4f).sp
    val context = LocalContext.current
    val imageRequest = remember(context, avatarImageUrl, imageCrossfade) {
        avatarImageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(imageCrossfade)
                .build()
        }
    }
    // Avatar URLs are optional remote content. A stale catalog URL used to
    // leave a blank circle, so retain the local initial as the error fallback.
    var imageFailed by remember(avatarImageUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor, CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Render the local identity immediately. A successful image covers it;
        // a slow or broken remote image never leaves an empty avatar.
        Text(
            text = initial,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
        if (imageRequest != null && !imageFailed) {
            AsyncImage(
                model = imageRequest,
                contentDescription = name,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true }
            )
        }
    }
}
