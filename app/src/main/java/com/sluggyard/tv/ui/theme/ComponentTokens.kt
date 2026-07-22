package com.sluggyard.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class SlugYardCardComponentTokens(
    val width: Dp,
    val height: Dp,
    val cornerRadius: Dp,
    val contentPadding: Dp,
    val focusedBorderWidth: Dp,
    val focusedScale: Float
)

@Immutable
data class SlugYardRowComponentTokens(
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val itemSpacing: Dp,
    val titleBottomSpacing: Dp
)

@Immutable
data class SlugYardSidebarComponentTokens(
    val legacyCollapsedWidth: Dp,
    val legacyExpandedWidth: Dp,
    val collapsedWidth: Dp,
    val expandedWidth: Dp,
    val itemHeight: Dp,
    val itemWidth: Dp,
    val iconSize: Dp,
    val leadingVisual: Dp,
    val panelRadius: Dp,
    val contentGap: Dp
)

@Immutable
data class SlugYardDialogComponentTokens(
    val maxWidth: Dp,
    val contentPadding: Dp,
    val cornerRadius: Dp,
    val actionSpacing: Dp
)

@Immutable
data class SlugYardPlayerComponentTokens(
    val overlayHorizontalPadding: Dp,
    val overlayVerticalPadding: Dp,
    val controlSize: Dp,
    val sidePanelWidth: Dp,
    val railWidth: Dp,
    val progressHeight: Dp
)

@Immutable
data class SlugYardSettingsComponentTokens(
    val containerRadius: Dp,
    val secondaryCardRadius: Dp,
    val railItemHeight: Dp,
    val workspacePadding: Dp,
    val rowGap: Dp
)

@Immutable
data class SlugYardComponentTokens(
    val posterCard: SlugYardCardComponentTokens,
    val backdropCard: SlugYardCardComponentTokens,
    val collectionCard: SlugYardCardComponentTokens,
    val continueWatchingCard: SlugYardCardComponentTokens,
    val episodeCard: SlugYardCardComponentTokens,
    val row: SlugYardRowComponentTokens,
    val sidebar: SlugYardSidebarComponentTokens,
    val dialog: SlugYardDialogComponentTokens,
    val sidePanel: SlugYardDialogComponentTokens,
    val settings: SlugYardSettingsComponentTokens,
    val player: SlugYardPlayerComponentTokens,
    val buttonHeight: Dp,
    val chipHeight: Dp,
    val badgeHeight: Dp,
    val skeletonCornerRadius: Dp
)

object SlugYardComponents {
    val tokens = SlugYardComponentTokens(
        posterCard = SlugYardCardComponentTokens(
            width = 126.dp,
            height = 189.dp,
            cornerRadius = 2.dp,
            contentPadding = 8.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.01f
        ),
        backdropCard = SlugYardCardComponentTokens(
            width = 320.dp,
            height = 180.dp,
            cornerRadius = 4.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.01f
        ),
        collectionCard = SlugYardCardComponentTokens(
            width = 320.dp,
            height = 180.dp,
            cornerRadius = 4.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.01f
        ),
        continueWatchingCard = SlugYardCardComponentTokens(
            width = 260.dp,
            height = 146.dp,
            cornerRadius = 2.dp,
            contentPadding = 12.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.01f
        ),
        episodeCard = SlugYardCardComponentTokens(
            width = 320.dp,
            height = 207.dp,
            cornerRadius = 4.dp,
            contentPadding = 16.dp,
            focusedBorderWidth = 2.dp,
            focusedScale = 1.01f
        ),
        row = SlugYardRowComponentTokens(
            horizontalPadding = 48.dp,
            verticalPadding = 6.dp,
            itemSpacing = 12.dp,
            titleBottomSpacing = 14.dp
        ),
        sidebar = SlugYardSidebarComponentTokens(
            legacyCollapsedWidth = 72.dp,
            legacyExpandedWidth = 196.dp,
            collapsedWidth = 184.dp,
            expandedWidth = 262.dp,
            itemHeight = 52.dp,
            itemWidth = 148.dp,
            iconSize = 22.dp,
            leadingVisual = 34.dp,
            panelRadius = 30.dp,
            contentGap = 14.dp
        ),
        dialog = SlugYardDialogComponentTokens(
            maxWidth = 720.dp,
            contentPadding = 24.dp,
            cornerRadius = 8.dp,
            actionSpacing = 12.dp
        ),
        sidePanel = SlugYardDialogComponentTokens(
            maxWidth = 420.dp,
            contentPadding = 20.dp,
            cornerRadius = 12.dp,
            actionSpacing = 12.dp
        ),
        settings = SlugYardSettingsComponentTokens(
            containerRadius = 16.dp,
            secondaryCardRadius = 12.dp,
            railItemHeight = 56.dp,
            workspacePadding = 20.dp,
            rowGap = 16.dp
        ),
        player = SlugYardPlayerComponentTokens(
            overlayHorizontalPadding = 52.dp,
            overlayVerticalPadding = 36.dp,
            controlSize = 44.dp,
            sidePanelWidth = 360.dp,
            railWidth = 280.dp,
            progressHeight = 4.dp
        ),
        buttonHeight = 52.dp,
        chipHeight = 32.dp,
        badgeHeight = 20.dp,
        skeletonCornerRadius = 4.dp
    )
}
