package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The small browse shelf immediately below the featured title.
 *
 * This is intentionally a separate, focusable surface rather than a set of
 * filters inside a catalog row: on TV it gives the home screen the same
 * hero -> browse by genre -> programming-rails hierarchy as the reference UI.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HomeGenreBubbles(
    modifier: Modifier = Modifier
) {
    val genres = remember {
        listOf("Drama", "Action", "Comedy", "Crime", "Adventure", "Romance", "Thriller", "Animation")
    }
    LazyRow(
        modifier = modifier.height(108.dp),
        contentPadding = PaddingValues(horizontal = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(genres, key = { it }) { genre ->
            val shape = RoundedCornerShape(18.dp)
            Card(
                // These are browse labels, not pretend filters. They must not
                // steal D-pad focus from the first catalog rail until filtering
                // has an actual catalog action behind it.
                onClick = {},
                modifier = Modifier
                    .width(164.dp)
                    .height(64.dp)
                    .focusProperties { canFocus = false },
                shape = CardDefaults.shape(shape),
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard.copy(alpha = 0.80f),
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard
                ),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.22f)),
                        shape = shape
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                        shape = shape
                    )
                ),
                scale = CardDefaults.scale(focusedScale = 1.035f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.94f),
                        maxLines = 1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}
