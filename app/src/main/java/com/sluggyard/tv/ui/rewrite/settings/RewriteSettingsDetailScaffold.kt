package com.sluggyard.tv.ui.rewrite.settings

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.rewrite.requestFocusReliably

@Composable
fun RewriteSettingsDetailScaffold(
    category: RewriteSettingsCategory,
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
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (showBack) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(category.title, style = MaterialTheme.typography.displaySmall)
                    Text(category.description, style = MaterialTheme.typography.bodyLarge, color = SlugYardPalette.OnCanvasMuted)
                }
                OutlinedButton(
                    onClick = onBack,
                    modifier = if (contentFocusRequester != null) Modifier.focusRequester(contentFocusRequester) else Modifier,
                ) { Text("Back") }
            }
        }
        content()
    }
}
