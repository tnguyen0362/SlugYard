package com.sluggyard.tv.ui.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.ui.app.data.HomeSettings
import com.sluggyard.tv.ui.app.data.ProfileState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState

@Composable
internal fun AccountSettings(
    facade: SettingsFacade,
    onSignedOut: () -> Unit,
    onOpenAuth: () -> Unit,
) {
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        signedIn = facade.sessions.read() is SupabaseSessionState.Active
    }
    SettingsGroup("SlugYard account") {
        ValueRow("Status", when (signedIn) { true -> "Signed in"; false -> "Guest"; null -> "Checking" })
        ValueRow("Sync", if (signedIn == true) "Enabled for this profile" else "Available after sign-in")
        if (signedIn == true) {
            ActionRow("Sign out", "Remove the active account from this device.") {
                scope.launch {
                    facade.auth.signOut()
                    signedIn = false
                    onSignedOut()
                }
            }
        } else if (signedIn == false) {
            ActionRow(
                "Sign in",
                "Open the account sign-in screen without changing local profiles.",
                actionLabel = "Open",
                onClick = onOpenAuth,
            )
        }
    }
}

@Composable
internal fun ProfilesSettings(state: ProfileState, onOpenProfiles: () -> Unit) {
    SettingsGroup("Profiles") {
        ValueRow("Active profile", state.activeProfile.name)
        ValueRow("Profiles on this device", state.profiles.size.toString())
        ActionRow(
            "Open Profiles",
            "Choose, create, rename, or remove a profile.",
            onClick = onOpenProfiles,
        )
    }
}

@Composable
internal fun LayoutSettings(
    facade: SettingsFacade,
    homeSettings: HomeSettings,
    profileState: ProfileState,
    onHideUnreleasedChanged: (Boolean) -> Unit,
    onRememberProfileChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val smoothFocus by facade.layoutPreferences.smoothBringIntoViewEnabled.collectAsState(initial = true)
    val fastNavigation by facade.layoutPreferences.fastHorizontalNavigationEnabled.collectAsState(initial = false)
    val badgeSettings by facade.streamBadgeSettings.collectAsState(
        initial = com.sluggyard.tv.core.streams.StreamBadgeSettings(),
    )
    var badgeMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        when (val result = facade.ensureDefaultStreamBadgePack()) {
            is com.sluggyard.tv.core.streams.StreamBadgeImportResult.Success ->
                badgeMessage = "Xperience badges installed (${result.rules.enabledFilterCount} filters)."
            is com.sluggyard.tv.core.streams.StreamBadgeImportResult.Error ->
                badgeMessage = result.message
            null -> Unit
        }
    }
    SettingsGroup("Home and navigation") {
        ToggleRow("Hide unreleased titles", "Filter titles with a known future release date.", homeSettings.hideUnreleased, onHideUnreleasedChanged)
        ToggleRow("Remember active profile", "Open the last selected profile automatically.", profileState.rememberLastProfile, onRememberProfileChanged)
        ToggleRow("Smooth focus movement", "Use the TV focus animation when moving between cards.", smoothFocus) { value -> scope.launch { facade.layoutPreferences.setSmoothBringIntoViewEnabled(value) } }
        ToggleRow("Fast horizontal navigation", "Reduce focus delay for long poster rows.", fastNavigation) { value -> scope.launch { facade.layoutPreferences.setFastHorizontalNavigationEnabled(value) } }
    }
    SettingsGroup("Stream badges") {
        val active = badgeSettings.rules.activeImport
        ValueRow(
            "Active pack",
            when {
                active == null -> "None"
                else -> "${active.enabledFilterCount} badges · ${active.groups.size} groups"
            },
        )
        badgeMessage?.let { ValueRow("Status", it) }
        ActionRow(
            title = if (active == null) "Install Xperience badges" else "Refresh badges",
            description = "Import the Xperience Fusion-style badge pack for Sources rows.",
            actionLabel = if (active == null) "Install" else "Refresh",
        ) {
            scope.launch {
                badgeMessage = "Importing…"
                badgeMessage = when (val result = facade.refreshStreamBadgePack()) {
                    is com.sluggyard.tv.core.streams.StreamBadgeImportResult.Success ->
                        "Installed ${result.rules.enabledFilterCount} badges."
                    is com.sluggyard.tv.core.streams.StreamBadgeImportResult.Error -> result.message
                }
            }
        }
        if (active != null) {
            ActionRow(
                title = "Remove badges",
                description = "Clear imported Fusion badge packs from this profile.",
                actionLabel = "Remove",
            ) {
                scope.launch {
                    facade.clearStreamBadgePacks()
                    badgeMessage = "Badge packs removed."
                }
            }
        }
    }
}
