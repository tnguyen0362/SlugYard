package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.TraktAuthState
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettings
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileState
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridConnection
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import kotlinx.coroutines.launch

@Composable
fun RewriteSettingsScreen(
    facade: RewriteSettingsFacade,
    onSignedOut: () -> Unit,
    onOpenAuth: () -> Unit,
    homeSettings: RewriteHomeSettings,
    profileState: RewriteProfileState,
    onHideUnreleasedChanged: (Boolean) -> Unit,
    onRememberProfileChanged: (Boolean) -> Unit,
    onCatalogOrderChanged: (List<HomeCatalogKey>) -> Unit,
    debridConnection: RewriteDebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onDisconnect: suspend (DebridService) -> Unit,
    onDebridEnabledChanged: suspend (Boolean) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
    rewriteAddons: List<ManagedAddon>,
    onInstallCommunityAddons: suspend () -> Unit,
    onUninstallCommunityAddons: suspend () -> Unit,
    onOpenProfiles: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<SettingsDestination>(SettingsDestination.Root) }
    var lastOpenedCategory by remember { mutableStateOf<RewriteSettingsCategory?>(null) }
    val categoryListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val traktAuth by facade.traktAuth.collectAsState(initial = TraktAuthState())
    val tmdb by facade.tmdbSettings.collectAsState(initial = TmdbSettings())
    val player by facade.playerSettings.collectAsState(initial = PlayerSettings())

    @Composable
    fun RenderSettingsDetail(category: RewriteSettingsCategory) {
        when (category) {
            RewriteSettingsCategory.Account -> AccountSettings(facade, onSignedOut, onOpenAuth)
            RewriteSettingsCategory.Profiles -> ProfilesSettings(profileState, onOpenProfiles)
            RewriteSettingsCategory.Layout -> LayoutSettings(facade, homeSettings, profileState, onHideUnreleasedChanged, onRememberProfileChanged)
            RewriteSettingsCategory.ContentDiscovery -> ContentDiscoverySettings(
                tmdb = tmdb,
                onTmdbEnabled = { scope.launch { facade.setTmdbEnabled(it) } },
                onTmdbEnrichContinueWatching = { scope.launch { facade.setTmdbEnrichContinueWatching(it) } },
                rewriteAddons = rewriteAddons,
                debridConnected = debridConnection.activeService != null ||
                    debridConnection.configuredServices.isNotEmpty(),
                onInstallCommunityAddons = onInstallCommunityAddons,
                onUninstallCommunityAddons = onUninstallCommunityAddons,
            )
            RewriteSettingsCategory.Integrations -> IntegrationsSettings(
                facade,
                traktAuth,
                tmdb,
                debridConnection,
                rewriteAddons = rewriteAddons,
                onInstallCommunityAddons = onInstallCommunityAddons,
                onUninstallCommunityAddons = onUninstallCommunityAddons,
                onConnect,
                onDisconnect,
                onSelect,
            )
            RewriteSettingsCategory.Display -> DisplaySettings(facade, player, scope)
            RewriteSettingsCategory.Subtitles -> SubtitleSettings(facade, player, scope)
            RewriteSettingsCategory.Audio -> AudioSettings(facade, player, scope)
            RewriteSettingsCategory.Advanced -> AdvancedSettings(player)
            RewriteSettingsCategory.About -> AboutSettings()
        }
    }

    when (val current = destination) {
        SettingsDestination.Root -> {
            val selectedCategory = lastOpenedCategory ?: facade.categories.firstOrNull()
            if (selectedCategory != null) {
                RewriteSettingsSplitPane(
                    categories = facade.categories,
                    selected = selectedCategory,
                    onSelect = { category ->
                        lastOpenedCategory = category
                    },
                    categoryListState = categoryListState,
                    initialFocusRequester = contentFocusRequester,
                    headerFocusRequester = headerFocusRequester,
                    modifier = modifier,
                ) {
                    RewriteSettingsDetailScaffold(
                        category = selectedCategory,
                        onBack = {},
                        showBack = false,
                        content = { RenderSettingsDetail(selectedCategory) },
                    )
                }
            }
        }

        is SettingsDestination.Detail -> RewriteSettingsDetailScaffold(
            category = current.category,
            onBack = { destination = SettingsDestination.Root },
            contentFocusRequester = contentFocusRequester,
        ) { RenderSettingsDetail(current.category) }
    }
}
