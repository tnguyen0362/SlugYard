package com.sluggyard.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.sluggyard.tv.ui.design.SlugYardTvTheme
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.ProgressSyncBridge
import com.sluggyard.tv.core.sync.ProviderCredentialSyncBridge
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.ui.app.AppGraph
import com.sluggyard.tv.ui.app.AppShell
import com.sluggyard.tv.ui.app.data.LibraryWatchRepository
import com.sluggyard.tv.ui.app.settings.SettingsFacade
import com.sluggyard.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.first

/** Application-scoped locale cache retained for infrastructure outside the app shell. */
object LocaleCache {
    const val UNSET = "__unset__"

    @Volatile
    var localeTag: String = UNSET
}

/**
 * Android TV activity for the SlugYard app shell.
 *
 * Playback is deliberately not initialized here: the Media3/mpv owner remains behind the narrow
 * player-route handoff, while browsing, profiles, addon management, and navigation belong to the
 * Compose app graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var auth: SupabaseAuthGateway

    @Inject
    lateinit var sessions: SupabaseSessionStore

    @Inject
    lateinit var settingsFacade: SettingsFacade

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    @Named("app")
    lateinit var dataStore: DataStore<Preferences>

    @Inject
    lateinit var progressSyncBridge: ProgressSyncBridge

    @Inject
    lateinit var providerCredentialSyncBridge: ProviderCredentialSyncBridge

    @Inject
    lateinit var libraryWatchRepository: LibraryWatchRepository

    @Inject
    lateinit var profileStore: com.sluggyard.tv.ui.app.data.ProfileStore

    @Inject
    lateinit var tmdbMetadataService: com.sluggyard.tv.core.tmdb.TmdbMetadataService

    @Inject
    lateinit var tmdbService: com.sluggyard.tv.core.tmdb.TmdbService

    @Inject
    lateinit var streamLinkCacheDataStore: com.sluggyard.tv.data.local.StreamLinkCacheDataStore

    @Inject
    lateinit var tmdbApi: com.sluggyard.tv.data.remote.api.TmdbApi

    @Inject
    lateinit var traktApi: com.sluggyard.tv.data.remote.api.TraktApi

    @Inject
    lateinit var traktAuthService: com.sluggyard.tv.data.repository.TraktAuthService

    @Inject
    lateinit var watchProgressRepository: WatchProgressRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep an opaque canvas behind Compose so the launcher never shows through as a
        // "logo on transparent" frame while boot/auth/profile gates settle.
        window.setBackgroundDrawableResource(R.color.splash_background)

        window.decorView.post {
            val capabilities = com.sluggyard.tv.core.player.DisplayCapabilities.detect(this)
            com.sluggyard.tv.core.player.DisplayCapabilities.logSummary(capabilities)
        }

        setContent {
            val graph = remember {
                val relatedDataSource = com.sluggyard.tv.ui.app.details.RelatedDataSource(
                    tmdbMetadata = tmdbMetadataService,
                    tmdbService = tmdbService,
                    traktApi = traktApi,
                    traktAuth = traktAuthService,
                    tmdbSettings = { settingsFacade.tmdbSettings.first() },
                    moreLikeThisSource = {
                        settingsFacade.traktSettings.moreLikeThisSource.first()
                    },
                )
                val tmdbHomeDataSource = com.sluggyard.tv.ui.app.home.TmdbHomeDataSource(
                    tmdbApi = tmdbApi,
                    tmdbSettings = { settingsFacade.tmdbSettings.first() },
                    // Details resolves tmdb:→IMDb and falls back to Cinemeta by manifest URL.
                    metadataAddonId = { null },
                )
                AppGraph(
                    context = applicationContext,
                    profileManager = profileManager,
                    dataStore = dataStore,
                    profiles = profileStore,
                    progressSyncBridge = progressSyncBridge,
                    providerCredentialSyncBridge = providerCredentialSyncBridge,
                    libraryWatchRepository = libraryWatchRepository,
                    watchProgressRepository = watchProgressRepository,
                    relatedDataSource = relatedDataSource,
                    tmdbHomeDataSource = tmdbHomeDataSource,
                    tmdbService = tmdbService,
                    streamLinkCache = streamLinkCacheDataStore,
                    digitalReleaseLookup = com.sluggyard.tv.ui.app.streams.DigitalReleaseLookup(
                        tmdbApi = tmdbApi,
                    ),
                    resolveMetaContentId = { type, id ->
                        com.sluggyard.tv.ui.app.details.resolveMetaContentId(
                            type = type,
                            id = id,
                            tmdbToImdb = { tmdbId, mediaType -> tmdbService.tmdbToImdb(tmdbId, mediaType) },
                        )
                    },
                )
            }
            SlugYardTvTheme {
                AppShell(
                    context = applicationContext,
                    graph = graph,
                    auth = auth,
                    sessions = sessions,
                    settingsFacade = settingsFacade,
                    watchProgressRepository = watchProgressRepository,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
