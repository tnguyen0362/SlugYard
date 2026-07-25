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
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.TraktAuthState
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.domain.model.TmdbSettings
import com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettings
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileState
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridConnection
import kotlinx.coroutines.launch

@Composable
fun RewriteSettingsScreen(
    facade: RewriteSettingsFacade,
    homeSettings: RewriteHomeSettings,
    profileState: RewriteProfileState,
    onHideUnreleasedChanged: (Boolean) -> Unit,
    onRememberProfileChanged: (Boolean) -> Unit,
    debridConnection: RewriteDebridConnection,
    onConnect: suspend (DebridService, String) -> Unit,
    onDebridEnabledChanged: suspend (Boolean) -> Unit,
    onSelect: suspend (DebridService) -> Unit,
    communityAddonCount: Int,
    modifier: Modifier = Modifier,
) {
    var destination by remember { mutableStateOf<SettingsDestination>(SettingsDestination.Root) }
    var lastOpenedCategory by remember { mutableStateOf<RewriteSettingsCategory?>(null) }
    val categoryListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val traktAuth by facade.traktAuth.collectAsState(initial = TraktAuthState())
    val tmdb by facade.tmdbSettings.collectAsState(initial = TmdbSettings())
    val debrid by facade.debridSettings.collectAsState(initial = DebridSettings())
    val player by facade.playerSettings.collectAsState(initial = PlayerSettings())
    val openSubtitlesEnabled by facade.openSubtitlesEnabled.collectAsState(initial = false)
    val openSubtitlesKey by facade.openSubtitlesApiKey.collectAsState(initial = "")

    when (val current = destination) {
        SettingsDestination.Root -> RewriteSettingsCategoryList(
            categories = facade.categories,
            onOpen = {
                lastOpenedCategory = it
                destination = SettingsDestination.Detail(it)
            },
            modifier = modifier,
            state = categoryListState,
            restoreFocusCategory = lastOpenedCategory,
        )

        is SettingsDestination.Detail -> RewriteSettingsDetailScaffold(
            category = current.category,
            onBack = { destination = SettingsDestination.Root },
        ) {
            when (current.category) {
                RewriteSettingsCategory.Account -> AccountSettings(facade) { scope.launch { facade.auth.signOut() } }
                RewriteSettingsCategory.Profiles -> ProfilesSettings(profileState)
                RewriteSettingsCategory.Layout -> LayoutSettings(facade, homeSettings, profileState, onHideUnreleasedChanged, onRememberProfileChanged)
                RewriteSettingsCategory.ContentDiscovery -> ContentDiscoverySettings(
                    facade,
                    tmdb,
                    onTmdbEnabled = { scope.launch { facade.setTmdbEnabled(it) } },
                    communityAddonCount,
                )
                RewriteSettingsCategory.Integrations -> IntegrationsSettings(
                    facade,
                    traktAuth,
                    tmdb,
                    debrid,
                    openSubtitlesEnabled,
                    openSubtitlesKey,
                    debridConnection,
                    onConnect,
                    onDebridEnabledChanged,
                    onSelect,
                )
                RewriteSettingsCategory.Display -> DisplaySettings(facade, player, scope)
                RewriteSettingsCategory.Subtitles -> SubtitleSettings(facade, player, scope)
                RewriteSettingsCategory.Audio -> AudioSettings(facade, player, scope)
                RewriteSettingsCategory.Advanced -> AdvancedSettings(player)
                RewriteSettingsCategory.About -> AboutSettings()
            }
        }
    }
}
