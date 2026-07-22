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
internal fun AccountSettings(facade: RewriteSettingsFacade, onSignOut: () -> Unit) {
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        signedIn = facade.sessions.read() is SupabaseSessionState.Active
    }
    RewriteSettingsGroup("PlayFlix account") {
        RewriteValueRow("Status", when (signedIn) { true -> "Signed in"; false -> "Guest"; null -> "Checking" })
        RewriteValueRow("Sync", if (signedIn == true) "Enabled for this profile" else "Available after sign-in")
        if (signedIn == true) RewriteActionRow("Sign out", "Remove the active account from this device.", onSignOut)
    }
}

@Composable
internal fun ProfilesSettings(state: RewriteProfileState) {
    RewriteSettingsGroup("Profiles") {
        RewriteValueRow("Active profile", state.activeProfile.name)
        RewriteValueRow("Profiles on this device", state.profiles.size.toString())
        RewriteValueRow("Profile management", "Open Profiles from the main navigation")
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
    val layout by facade.layoutPreferences.selectedLayout.collectAsState(initial = null)
    val posterLabels by facade.layoutPreferences.posterLabelsEnabled.collectAsState(initial = true)
    val smoothFocus by facade.layoutPreferences.smoothBringIntoViewEnabled.collectAsState(initial = true)
    val fastNavigation by facade.layoutPreferences.fastHorizontalNavigationEnabled.collectAsState(initial = false)
    RewriteSettingsGroup("Home and navigation") {
        RewriteToggleRow("Hide unreleased titles", "Filter titles with a known future release date.", homeSettings.hideUnreleased, onHideUnreleasedChanged)
        RewriteToggleRow("Remember active profile", "Open ${profileState.activeProfile.name} automatically.", profileState.rememberLastProfile, onRememberProfileChanged)
        RewriteToggleRow("Poster labels", "Keep title labels visible below poster art.", posterLabels) { value -> scope.launch { facade.layoutPreferences.setPosterLabelsEnabled(value) } }
        RewriteToggleRow("Smooth focus movement", "Use the TV focus animation when moving between cards.", smoothFocus) { value -> scope.launch { facade.layoutPreferences.setSmoothBringIntoViewEnabled(value) } }
        RewriteToggleRow("Fast horizontal navigation", "Reduce focus delay for long poster rows.", fastNavigation) { value -> scope.launch { facade.layoutPreferences.setFastHorizontalNavigationEnabled(value) } }
        RewriteValueRow("Layout", layout?.toString()?.replace('_', ' ') ?: "Automatic")
    }
}
