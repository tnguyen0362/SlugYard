package com.sluggyard.tv.ui.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.ui.app.data.HomeSettings
import com.sluggyard.tv.ui.app.data.ProfileState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
    val landscapeExpand by facade.layoutPreferences.modernLandscapePostersEnabled.collectAsState(initial = true)
    SettingsGroup("Home and navigation") {
        ToggleRow(
            "Hide unreleased titles",
            "Hides Home catalog rows whose addon release date is still in the future. Does not block Play — that uses the separate digital-release gate.",
            homeSettings.hideUnreleased,
            onHideUnreleasedChanged,
        )
        ToggleRow(
            "Landscape posters on focus",
            "When off, Home cards stay portrait (no landscape expand).",
            landscapeExpand,
        ) { enabled -> scope.launch { facade.layoutPreferences.setModernLandscapePostersEnabled(enabled) } }
        ToggleRow("Remember active profile", "Open the last selected profile automatically.", profileState.rememberLastProfile, onRememberProfileChanged)
    }
}
