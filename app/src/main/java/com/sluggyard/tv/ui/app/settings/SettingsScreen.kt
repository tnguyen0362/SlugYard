package com.sluggyard.tv.ui.app.settings

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
import com.sluggyard.tv.ui.app.data.HomeSettings
import com.sluggyard.tv.ui.app.data.ProfileState
import com.sluggyard.tv.ui.app.debrid.DebridConnection
import com.sluggyard.tv.core.addonprotocol.ManagedAddon
import com.sluggyard.tv.core.aggregation.HomeCatalogKey
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    facade: SettingsFacade,
    onSignedOut: () -> Unit,
    onOpenAuth: () -> Unit,
    homeSettings: HomeSettings,
    profileState: ProfileState,
    onHideUnreleasedChanged: (Boolean) -> Unit,
    onRememberProfileChanged: (Boolean) -> Unit,
    onCatalogOrderChanged: (List<HomeCatalogKey>) -> Unit,
    debridConnection: DebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onDisconnect: suspend (DebridService) -> Unit,
    onDebridEnabledChanged: suspend (Boolean) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
    addons: List<ManagedAddon>,
    onInstallCommunityAddons: suspend () -> Unit,
    onUninstallCommunityAddons: suspend () -> Unit,
    onInstallAddon: suspend (manifestUrl: String) -> Unit,
    onUninstallAddon: suspend (manifestUrl: String) -> Unit,
    onOpenProfiles: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    headerFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<SettingsDestination>(SettingsDestination.Root) }
    var lastOpenedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val categoryListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val traktAuth by facade.traktAuth.collectAsState(initial = TraktAuthState())
    val tmdb by facade.tmdbSettings.collectAsState(initial = TmdbSettings())
    val player by facade.playerSettings.collectAsState(initial = PlayerSettings())

    @Composable
    fun RenderSettingsDetail(category: SettingsCategory) {
        when (category) {
            SettingsCategory.Account -> AccountSettings(facade, onSignedOut, onOpenAuth)
            SettingsCategory.Profiles -> ProfilesSettings(profileState, onOpenProfiles)
            SettingsCategory.Layout -> LayoutSettings(homeSettings, profileState, onHideUnreleasedChanged, onRememberProfileChanged)
            SettingsCategory.Addons -> AddonsSettings(
                addons = addons,
                onInstallAddon = onInstallAddon,
                onUninstallAddon = onUninstallAddon,
            )
            SettingsCategory.ContentDiscovery -> ContentDiscoverySettings(
                addons = addons,
                debridConnected = debridConnection.activeService != null ||
                    debridConnection.configuredServices.isNotEmpty(),
                onInstallCommunityAddons = onInstallCommunityAddons,
                onUninstallCommunityAddons = onUninstallCommunityAddons,
            )
            SettingsCategory.Integrations -> IntegrationsSettings(
                facade,
                traktAuth,
                tmdb,
                debridConnection,
                addons = addons,
                onInstallCommunityAddons = onInstallCommunityAddons,
                onUninstallCommunityAddons = onUninstallCommunityAddons,
                onConnect,
                onDisconnect,
                onSelect,
            )
            SettingsCategory.Display -> DisplaySettings(facade, player, scope)
            SettingsCategory.Subtitles -> SubtitleSettings(facade, player, scope)
            SettingsCategory.Audio -> AudioSettings(facade, player, scope)
            SettingsCategory.About -> AboutSettings()
        }
    }

    when (val current = destination) {
        SettingsDestination.Root -> {
            val selectedCategory = lastOpenedCategory ?: facade.categories.firstOrNull()
            if (selectedCategory != null) {
                SettingsSplitPane(
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
                    SettingsDetailScaffold(
                        category = selectedCategory,
                        onBack = {},
                        showBack = false,
                        content = { RenderSettingsDetail(selectedCategory) },
                    )
                }
            }
        }

        is SettingsDestination.Detail -> SettingsDetailScaffold(
            category = current.category,
            onBack = { destination = SettingsDestination.Root },
            contentFocusRequester = contentFocusRequester,
        ) { RenderSettingsDetail(current.category) }
    }
}
