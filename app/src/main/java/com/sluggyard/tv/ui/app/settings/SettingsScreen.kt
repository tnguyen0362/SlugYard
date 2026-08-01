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
    @Suppress("UNUSED_PARAMETER") onCatalogOrderChanged: (List<HomeCatalogKey>) -> Unit,
    debridConnection: DebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onDisconnect: suspend (DebridService) -> Unit,
    @Suppress("UNUSED_PARAMETER") onDebridEnabledChanged: suspend (Boolean) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
    addons: List<ManagedAddon>,
    onInstallAddon: suspend (manifestUrl: String) -> Unit,
    onUninstallAddon: suspend (manifestUrl: String) -> Unit,
    onOpenProfiles: () -> Unit,
    contentFocusRequester: FocusRequester? = null,
    @Suppress("UNUSED_PARAMETER") headerFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<SettingsDestination>(SettingsDestination.Root) }
    var lastOpenedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val categoryListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val traktAuth by facade.traktAuth.collectAsState(initial = TraktAuthState())
    val player by facade.playerSettings.collectAsState(initial = PlayerSettings())

    @Composable
    fun RenderSettingsDetail(category: SettingsCategory) {
        when (category) {
            SettingsCategory.Account -> AccountSettings(facade, onSignedOut, onOpenAuth)
            SettingsCategory.Profiles -> ProfilesSettings(profileState, onOpenProfiles)
            SettingsCategory.Layout -> LayoutSettings(facade, homeSettings, profileState, onHideUnreleasedChanged, onRememberProfileChanged)
            SettingsCategory.Addons -> AddonsSettings(
                addons = addons,
                onInstallAddon = onInstallAddon,
                onUninstallAddon = onUninstallAddon,
            )
            SettingsCategory.Integrations -> IntegrationsSettings(
                facade,
                traktAuth,
                debridConnection,
                onConnect,
                onDisconnect,
                onSelect,
            )
            SettingsCategory.Display -> DisplaySettings(facade, player, scope)
            SettingsCategory.Subtitles -> SubtitleSettings(facade, player, scope)
            SettingsCategory.Audio -> AudioSettings(facade, player, scope)
            SettingsCategory.About -> AboutSettings(facade)
        }
    }

    when (val current = destination) {
        // Categories-only root: no sticky left rail beside content.
        SettingsDestination.Root -> SettingsCategoryList(
            categories = facade.categories,
            onOpen = { category ->
                lastOpenedCategory = category
                destination = SettingsDestination.Detail(category)
            },
            state = categoryListState,
            restoreFocusCategory = lastOpenedCategory,
            initialFocusRequester = contentFocusRequester,
            modifier = modifier,
        )

        // Full-pane category content; Back returns to the category list.
        is SettingsDestination.Detail -> SettingsDetailScaffold(
            category = current.category,
            onBack = { destination = SettingsDestination.Root },
            contentFocusRequester = contentFocusRequester,
            showBack = true,
            modifier = modifier,
        ) { RenderSettingsDetail(current.category) }
    }
}
