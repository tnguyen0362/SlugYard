package com.sluggyard.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.sluggyard.tv.ui.design.SlugYardTvTheme
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.RewriteProgressSyncBridge
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.ui.rewrite.RewriteAppGraph
import com.sluggyard.tv.ui.rewrite.RewriteAppShell
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsFacade
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Named

// Compatibility-only symbols for compiled, unreachable legacy screens. They are not provided by
// the rewrite activity and will disappear with those screens during the final provenance sweep.
val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null,
)

/** Application-scoped locale cache retained for infrastructure that is outside the rewrite shell. */
object LocaleCache {
    const val UNSET = "__unset__"

    @Volatile
    var localeTag: String = UNSET
}

/**
 * Rewrite-owned Android TV activity.
 *
 * Playback is deliberately not initialized here: the Media3/mpv owner remains behind the narrow
 * player-route handoff, while browsing, profiles, addon management, and navigation belong to the
 * Compose rewrite graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var auth: SupabaseAuthGateway

    @Inject
    lateinit var sessions: SupabaseSessionStore

    @Inject
    lateinit var settingsFacade: RewriteSettingsFacade

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    @Named("rewrite")
    lateinit var rewriteDataStore: DataStore<Preferences>

    @Inject
    lateinit var rewriteProgressSyncBridge: RewriteProgressSyncBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null)

        window.decorView.post {
            val capabilities = com.sluggyard.tv.core.player.DisplayCapabilities.detect(this)
            com.sluggyard.tv.core.player.DisplayCapabilities.logSummary(capabilities)
        }

        setContent {
            val graph = remember {
                RewriteAppGraph(
                    context = applicationContext,
                    profileManager = profileManager,
                    rewriteDataStore = rewriteDataStore,
                    progressSyncBridge = rewriteProgressSyncBridge,
                )
            }
            SlugYardTvTheme {
                RewriteAppShell(
                    context = applicationContext,
                    graph = graph,
                    auth = auth,
                    sessions = sessions,
                    settingsFacade = settingsFacade,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
