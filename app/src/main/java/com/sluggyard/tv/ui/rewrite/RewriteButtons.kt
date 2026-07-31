package com.sluggyard.tv.ui.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics

enum class RewriteButtonStyle {
    Primary,
    Secondary,
    Ghost,
}

/**
 * Shared TV button chrome for rewrite surfaces (settings, search, dialogs, streams).
 * Soft-rect streaming shape with white focus ring and gold primary fill on focus when Primary.
 */
@Composable
fun RewriteTvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RewriteButtonStyle = RewriteButtonStyle.Primary,
    enabled: Boolean = true,
    fillMaxWidth: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(SlugYardTvMetrics.ButtonCornerRadius)
    val container = when {
        !enabled -> SlugYardPalette.Surface.copy(alpha = 0.55f)
        style == RewriteButtonStyle.Primary && focused -> SlugYardPalette.Accent
        style == RewriteButtonStyle.Primary -> SlugYardPalette.OnCanvas
        style == RewriteButtonStyle.Secondary && focused -> Color.White
        style == RewriteButtonStyle.Secondary -> Color.Transparent
        style == RewriteButtonStyle.Ghost && focused -> SlugYardPalette.SurfaceElevated
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> SlugYardPalette.OnCanvasMuted.copy(alpha = 0.55f)
        style == RewriteButtonStyle.Primary && focused -> Color(0xFF141414)
        style == RewriteButtonStyle.Primary -> SlugYardPalette.Canvas
        style == RewriteButtonStyle.Secondary && focused -> Color(0xFF141414)
        focused -> SlugYardPalette.OnCanvas
        else -> SlugYardPalette.OnCanvas
    }
    val borderColor = when {
        focused -> SlugYardPalette.FocusRing
        style == RewriteButtonStyle.Secondary -> SlugYardPalette.OnCanvasMuted.copy(alpha = 0.55f)
        style == RewriteButtonStyle.Ghost -> Color.Transparent
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .background(container, shape)
            .border(SlugYardTvMetrics.FocusRingWidth, borderColor, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RewriteTvButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Compact content padding used by settings / search status panels. */
val RewriteTvButtonContentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
