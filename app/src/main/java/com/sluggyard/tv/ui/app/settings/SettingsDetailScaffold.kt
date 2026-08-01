package com.sluggyard.tv.ui.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.app.ButtonStyle
import com.sluggyard.tv.ui.app.TvButton
import com.sluggyard.tv.ui.app.requestFocusReliably

@Composable
fun SettingsDetailScaffold(
    category: SettingsCategory,
    onBack: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    showBack: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (showBack) {
        BackHandler(onBack = onBack)
    }
    if (contentFocusRequester != null) {
        LaunchedEffect(contentFocusRequester, category) {
            contentFocusRequester.requestFocusReliably(retries = 8)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlugYardPalette.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                vertical = 34.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (showBack) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        category.title,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = SlugYardPalette.OnCanvas,
                    )
                    Text(
                        category.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = SlugYardPalette.OnCanvasMuted,
                    )
                }
                TvButton(
                    label = "Back",
                    onClick = onBack,
                    style = ButtonStyle.Secondary,
                    focusRequester = contentFocusRequester,
                )
            }
        }
        content()
    }
}
