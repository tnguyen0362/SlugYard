package com.sluggyard.tv.ui.rewrite.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettings
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState

@Composable
internal fun AccountSettings(
    facade: RewriteSettingsFacade,
    onSignedOut: () -> Unit,
    onOpenAuth: () -> Unit,
) {
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        signedIn = facade.sessions.read() is SupabaseSessionState.Active
    }
    RewriteSettingsGroup("SlugYard account") {
        RewriteValueRow("Status", when (signedIn) { true -> "Signed in"; false -> "Guest"; null -> "Checking" })
        RewriteValueRow("Sync", if (signedIn == true) "Enabled for this profile" else "Available after sign-in")
        if (signedIn == true) {
            RewriteActionRow("Sign out", "Remove the active account from this device.") {
                scope.launch {
                    facade.auth.signOut()
                    signedIn = false
                    onSignedOut()
                }
            }
        } else if (signedIn == false) {
            RewriteActionRow(
                "Sign in",
                "Open the account sign-in screen without changing local profiles.",
                actionLabel = "Open",
                onClick = onOpenAuth,
            )
        }
    }
}

@Composable
internal fun ProfilesSettings(state: RewriteProfileState, onOpenProfiles: () -> Unit) {
    RewriteSettingsGroup("Profiles") {
        RewriteValueRow("Active profile", state.activeProfile.name)
        RewriteValueRow("Profiles on this device", state.profiles.size.toString())
        RewriteActionRow(
            "Open Profiles",
            "Choose, create, rename, or remove a profile.",
            onClick = onOpenProfiles,
        )
    }
}

@Composable
internal fun LayoutSettings(
    facade: RewriteSettingsFacade,
    homeSettings: RewriteHomeSettings,
    profileState: RewriteProfileState,
    onHideUnreleasedChanged: (Boolean) -> Unit,
    onRememberProfileChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val smoothFocus by facade.layoutPreferences.smoothBringIntoViewEnabled.collectAsState(initial = true)
    val fastNavigation by facade.layoutPreferences.fastHorizontalNavigationEnabled.collectAsState(initial = false)
    RewriteSettingsGroup("Home and navigation") {
        RewriteToggleRow("Hide unreleased titles", "Filter titles with a known future release date.", homeSettings.hideUnreleased, onHideUnreleasedChanged)
        RewriteToggleRow("Remember active profile", "Open the last selected profile automatically.", profileState.rememberLastProfile, onRememberProfileChanged)
        RewriteToggleRow("Smooth focus movement", "Use the TV focus animation when moving between cards.", smoothFocus) { value -> scope.launch { facade.layoutPreferences.setSmoothBringIntoViewEnabled(value) } }
        RewriteToggleRow("Fast horizontal navigation", "Reduce focus delay for long poster rows.", fastNavigation) { value -> scope.launch { facade.layoutPreferences.setFastHorizontalNavigationEnabled(value) } }
    }
}
