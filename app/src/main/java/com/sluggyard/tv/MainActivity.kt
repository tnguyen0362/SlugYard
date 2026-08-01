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
import com.sluggyard.tv.core.sync.RewriteProviderCredentialSyncBridge
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.ui.rewrite.RewriteAppGraph
import com.sluggyard.tv.ui.rewrite.RewriteAppShell
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchRepository
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsFacade
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.first

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

    @Inject
    lateinit var rewriteProviderCredentialSyncBridge: RewriteProviderCredentialSyncBridge

    @Inject
    lateinit var rewriteLibraryWatchRepository: RewriteLibraryWatchRepository

    @Inject
    lateinit var tmdbMetadataService: com.sluggyard.tv.core.tmdb.TmdbMetadataService

    @Inject
    lateinit var tmdbService: com.sluggyard.tv.core.tmdb.TmdbService

    @Inject
    lateinit var tmdbApi: com.sluggyard.tv.data.remote.api.TmdbApi

    @Inject
    lateinit var traktApi: com.sluggyard.tv.data.remote.api.TraktApi

    @Inject
    lateinit var traktAuthService: com.sluggyard.tv.data.repository.TraktAuthService

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
                val relatedDataSource = com.sluggyard.tv.ui.rewrite.details.RewriteRelatedDataSource(
                    tmdbMetadata = tmdbMetadataService,
                    tmdbService = tmdbService,
                    traktApi = traktApi,
                    traktAuth = traktAuthService,
                    tmdbSettings = { settingsFacade.tmdbSettings.first() },
                    moreLikeThisSource = {
                        settingsFacade.traktSettings.moreLikeThisSource.first()
                    },
                )
                val tmdbHomeDataSource = com.sluggyard.tv.ui.rewrite.home.RewriteTmdbHomeDataSource(
                    tmdbApi = tmdbApi,
                    tmdbSettings = { settingsFacade.tmdbSettings.first() },
                    // Details resolves tmdb:→IMDb and falls back to Cinemeta by manifest URL.
                    metadataAddonId = { null },
                )
                RewriteAppGraph(
                    context = applicationContext,
                    profileManager = profileManager,
                    rewriteDataStore = rewriteDataStore,
                    progressSyncBridge = rewriteProgressSyncBridge,
                    providerCredentialSyncBridge = rewriteProviderCredentialSyncBridge,
                    libraryWatchRepository = rewriteLibraryWatchRepository,
                    relatedDataSource = relatedDataSource,
                    tmdbHomeDataSource = tmdbHomeDataSource,
                    digitalReleaseLookup = com.sluggyard.tv.ui.rewrite.streams.RewriteDigitalReleaseLookup(
                        tmdbApi = tmdbApi,
                    ),
                    resolveMetaContentId = { type, id ->
                        com.sluggyard.tv.ui.rewrite.details.resolveRewriteMetaContentId(
                            type = type,
                            id = id,
                            tmdbToImdb = { tmdbId, mediaType -> tmdbService.tmdbToImdb(tmdbId, mediaType) },
                        )
                    },
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
