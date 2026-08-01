package com.sluggyard.tv.ui.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sluggyard.tv.ui.app.requestFocusReliably
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sluggyard.tv.core.addonprotocol.AddonRegistryState
import com.sluggyard.tv.core.addonprotocol.AddonRegistryAction
import com.sluggyard.tv.core.addonprotocol.StremioAddonTransport
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.core.streamresolution.ManualStreamSelection
import com.sluggyard.tv.ui.app.streams.toManualSelection
import com.sluggyard.tv.core.streamresolution.ManualResolutionResult
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.watchstate.DetailsWatchPolicy
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.design.SlugYardTvTheme
import com.sluggyard.tv.ui.app.data.CommunityAddonProvisioner
import com.sluggyard.tv.ui.app.data.CommunityProvisioningReport
import com.sluggyard.tv.ui.app.data.AddonRegistryStore
import com.sluggyard.tv.ui.app.data.LibraryWatchStore
import com.sluggyard.tv.ui.app.data.LibraryWatchRepository
import com.sluggyard.tv.ui.app.data.LibraryWatchState
import com.sluggyard.tv.ui.app.data.PlaybackProgressStore
import com.sluggyard.tv.ui.app.data.groupPlaybackCheckpoints
import com.sluggyard.tv.ui.app.data.toContinueWatchingCheckpoint
import com.sluggyard.tv.ui.app.data.useTraktContinueWatching
import com.sluggyard.tv.data.local.TraktAuthState
import com.sluggyard.tv.data.local.WatchProgressSource
import com.sluggyard.tv.domain.repository.WatchProgressRepository
import com.sluggyard.tv.core.sync.ProgressSyncBridge
import com.sluggyard.tv.core.sync.ProviderCredentialSyncBridge
import com.sluggyard.tv.ui.app.data.ProfileStore
import com.sluggyard.tv.ui.app.data.HomeSettingsStore
import com.sluggyard.tv.ui.app.data.GuestSessionStore
import com.sluggyard.tv.ui.app.data.DataIntegrityStore
import com.sluggyard.tv.ui.app.data.PriorProfileSnapshot
import com.sluggyard.tv.ui.app.data.Profile
import com.sluggyard.tv.ui.app.data.ProfileCodec
import com.sluggyard.tv.ui.app.data.ProfileState
import com.sluggyard.tv.ui.app.data.CloudSyncNotices
import com.sluggyard.tv.ui.app.data.remapNonIntegerProfileIds
import com.sluggyard.tv.ui.app.data.copyLegacyDefaultProfileScopedPreferences
import com.sluggyard.tv.ui.app.data.copyRemappedProfileScopedPreferences
import com.sluggyard.tv.ui.app.data.recoverOrphanedDebridCredentials
import com.sluggyard.tv.ui.app.data.LibraryEntry
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.ui.app.details.DetailsDataSource
import com.sluggyard.tv.ui.app.details.DetailsEpisode
import com.sluggyard.tv.ui.app.details.DetailsLoadResult
import com.sluggyard.tv.ui.app.details.DetailsRelatedPoster
import com.sluggyard.tv.ui.app.details.DetailsScreen
import com.sluggyard.tv.ui.app.details.DetailsState
import com.sluggyard.tv.ui.app.details.RelatedDataSource
import com.sluggyard.tv.ui.app.details.playbackTarget
import com.sluggyard.tv.ui.app.home.CatalogLoadState
import com.sluggyard.tv.ui.app.home.HomeCatalogState
import com.sluggyard.tv.ui.app.home.HomeDataSource
import com.sluggyard.tv.ui.app.home.HeroEnrichment
import com.sluggyard.tv.ui.app.home.HeroEnrichmentDataSource
import com.sluggyard.tv.ui.app.home.HomeRow
import com.sluggyard.tv.ui.app.home.ContinueWatchingOptionsDialog
import com.sluggyard.tv.ui.app.home.HomePoster
import com.sluggyard.tv.ui.app.home.HomeScreen
import com.sluggyard.tv.ui.app.home.HomeState
import com.sluggyard.tv.ui.app.home.TmdbHomeDataSource
import com.sluggyard.tv.ui.app.home.withHeroEnrichments
import com.sluggyard.tv.ui.app.home.enrichContinueWatchingPosters
import com.sluggyard.tv.ui.app.home.withContinueWatchingPosters
import com.sluggyard.tv.ui.app.home.CatalogRequest
import com.sluggyard.tv.ui.app.streams.hasEligibleSoftsubAutoPlay
import com.sluggyard.tv.ui.app.streams.selectAutoPlayCandidate
import com.sluggyard.tv.ui.app.streams.hasPendingCacheChecks
import com.sluggyard.tv.ui.app.streams.matchesLastPlayed
import com.sluggyard.tv.ui.app.streams.DigitalReleaseLookup
import com.sluggyard.tv.ui.app.streams.DigitalReleasePolicy
import com.sluggyard.tv.ui.app.streams.StreamScoringEngine
import com.sluggyard.tv.ui.app.streams.StreamBadgeApplicator
import com.sluggyard.tv.ui.app.streams.StreamGroup
import com.sluggyard.tv.ui.app.streams.StreamCandidate
import com.sluggyard.tv.ui.app.streams.StreamsDataSource
import com.sluggyard.tv.ui.app.streams.SubtitleDataSource
import com.sluggyard.tv.ui.app.streams.StreamsScreen
import com.sluggyard.tv.ui.app.profiles.ProfilesScreen
import com.sluggyard.tv.ui.app.settings.SettingsScreen
import com.sluggyard.tv.ui.app.settings.SettingsFacade
import com.sluggyard.tv.ui.app.player.RetainedPlayerHost
import com.sluggyard.tv.ui.app.player.PlayPreparingSurface
import com.sluggyard.tv.ui.app.player.playPreparingArtUrl
import com.sluggyard.tv.ui.app.auth.AuthGate
import com.sluggyard.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.sluggyard.tv.ui.app.debrid.DebridRuntime
import com.sluggyard.tv.ui.app.debrid.DebridConnection
import com.sluggyard.tv.ui.app.debrid.DebridCredentialStore
import com.sluggyard.tv.ui.app.debrid.AioStreamsSessionStore
import com.sluggyard.tv.ui.app.debrid.CloudManagerScreen
import com.sluggyard.tv.ui.app.data.AioStreamsConfigTransport
import com.sluggyard.tv.ui.app.data.ProviderAddonConfigurator
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.core.logging.ExperimentalDiagnostics
import com.sluggyard.tv.ui.app.watchhub.WatchHubDataSource
import com.sluggyard.tv.ui.app.watchhub.WatchHubPlatform
import com.sluggyard.tv.ui.app.watchhub.WatchHubScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Runtime composition root for the rewritten application path. */
class AppGraph(
    context: Context,
    private val profileManager: ProfileManager,
    private val dataStore: DataStore<Preferences>,
    progressSyncBridge: ProgressSyncBridge,
    val providerCredentialSyncBridge: ProviderCredentialSyncBridge,
    libraryWatchRepository: LibraryWatchRepository,
    private val watchProgressRepository: WatchProgressRepository? = null,
    relatedDataSource: RelatedDataSource? = null,
    tmdbHomeDataSource: TmdbHomeDataSource? = null,
    val tmdbService: com.sluggyard.tv.core.tmdb.TmdbService? = null,
    val streamLinkCache: com.sluggyard.tv.data.local.StreamLinkCacheDataStore? = null,
    val resolveMetaContentId: suspend (type: String, id: String) -> String = { _, id -> id },
    val digitalReleaseLookup: DigitalReleaseLookup? = null,
    val profiles: ProfileStore = ProfileStore(dataStore),
) {
    private val playbackSources = java.util.concurrent.ConcurrentHashMap<String, ResolvedPlaybackSource>()

    fun stagePlaybackSource(source: ResolvedPlaybackSource): String {
        val key = java.util.UUID.randomUUID().toString()
        playbackSources[key] = source
        return key
    }

    fun playbackSource(key: String): ResolvedPlaybackSource? = playbackSources[key]

    fun clearPlaybackSource(key: String) {
        playbackSources.remove(key)
    }

    val related = relatedDataSource
    val registry = AddonRegistryStore(dataStore)
    val libraryWatch = libraryWatchRepository
    val playbackProgress = PlaybackProgressStore(dataStore, profiles, progressSyncBridge)
    private val debridCredentials = DebridCredentialStore(dataStore, profiles)
    private val aioStreamsSessions = AioStreamsSessionStore(dataStore, profiles)
    private val aioStreamsTemplateJson = runCatching {
        context.assets.open("aiostreams/slugyard-template.json").bufferedReader().use { it.readText() }
    }.getOrDefault("")
    val debridRuntime = DebridRuntime(
        credentials = debridCredentials,
        addonConfigurator = ProviderAddonConfigurator(
            aioStreams = if (aioStreamsTemplateJson.isNotBlank()) {
                AioStreamsConfigTransport(
                    templateJson = aioStreamsTemplateJson,
                    sessions = aioStreamsSessions,
                )
            } else {
                null
            },
        ),
    )
    val homeSettings = HomeSettingsStore(dataStore, profiles)
    val dataIntegrity = DataIntegrityStore(dataStore)
    val guestSession = GuestSessionStore(dataStore)
    private val gateway = StremioAddonTransport(NetworkClient.create())
    private val communityProvisioner = CommunityAddonProvisioner(gateway, registry)
    private val _communityProvisioningReport = MutableStateFlow<CommunityProvisioningReport?>(null)
    val communityProvisioningReport: StateFlow<CommunityProvisioningReport?> = _communityProvisioningReport
    val homeDataSource = HomeDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
        settingsSnapshot = { homeSettings.settings.firstValue() },
        tmdbCatalogRequests = {
            val metaAddonId = resolveMetadataAddonId(registry.state.firstValue())
            tmdbHomeDataSource?.catalogRequests().orEmpty().map { request ->
                CatalogRequest(
                    key = request.key,
                    title = request.title,
                    load = {
                        request.load().map { poster ->
                            poster.copy(addonId = poster.addonId ?: metaAddonId)
                        }
                    },
                )
            }
        },
    )
    val heroEnrichmentDataSource = HeroEnrichmentDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val detailsDataSource = DetailsDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
        watchStateSnapshot = { libraryWatch.state.firstValue() },
        resolveMetaContentId = resolveMetaContentId,
        completedEpisodeKeys = { showId ->
            val repo = watchProgressRepository
            if (repo == null) {
                emptySet()
            } else {
                val byShow = repo.getWatchedShowEpisodes()
                val siblings = repo.getShowIdSiblings()[showId].orEmpty() + showId
                siblings.flatMap { id -> byShow[id].orEmpty() }.toSet()
            }
        },
    )
    val streamsDataSource = StreamsDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
        proactiveCacheCheckers = debridRuntime.proactiveCacheCheckers,
    )
    val watchHubDataSource = WatchHubDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val subtitleDataSource = SubtitleDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val searchDataSource = com.sluggyard.tv.ui.app.search.SearchDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val manualResolution = debridRuntime.manualResolution

    /**
     * Best-effort network bootstrap; UI is already composed while this runs on the background IO path.
     * Without an active debrid connection, only infrastructure + WatchHub are provisioned.
     */
    suspend fun provisionCommunitySources(debridConfigured: Boolean = true) {
        val configured = if (debridConfigured) debridRuntime.configuredAddonUrls() else null
        val hasConfiguredContentSources = configured != null
        // Provisioning touches the network and manifest validation; a thrown exception would
        // crash the calling coroutine silently and leave the UI with no status message.
        // Catch here so the report always reflects the outcome (even a total failure).
        _communityProvisioningReport.value = runCatching {
            communityProvisioner.provision(
                configured = configured,
                debridConfigured = hasConfiguredContentSources,
            )
        }.getOrElse {
            CommunityProvisioningReport(
                installedCount = 0,
                unavailableCount = SlugYardCommunitySourcePolicy.provisionManifestUrls(hasConfiguredContentSources).size,
                unavailableReasons = listOf("provisioning failed: ${it::class.simpleName ?: "unknown error"}"),
            )
        }
    }

    suspend fun uninstallCommunitySources() {
        _communityProvisioningReport.value = communityProvisioner.uninstall()
    }

    suspend fun installAllowlistedAddon(manifestUrl: String) {
        val normalized = manifestUrl.trim().let { raw ->
            when {
                raw.endsWith("/manifest.json", ignoreCase = true) -> raw
                raw.contains("://") -> raw.trimEnd('/') + "/manifest.json"
                else -> raw
            }
        }
        val configured = if (
            SlugYardCommunitySourcePolicy.isPlayFlixManifest(normalized) &&
            debridRuntime.configuredService() != null
        ) {
            debridRuntime.configuredAddonUrls()?.mediaFusionManifestUrl
        } else {
            null
        }
        communityProvisioner.installAllowlisted(
            registryUrl = when {
                SlugYardCommunitySourcePolicy.isPlayFlixManifest(normalized) ->
                    SlugYardCommunitySourcePolicy.PLAYFLIX_MANIFEST_URL
                else -> normalized
            },
            configuredRequestUrl = configured,
        )
    }

    suspend fun uninstallAllowlistedAddon(manifestUrl: String) {
        communityProvisioner.uninstallByManifestUrl(manifestUrl)
    }

    suspend fun synchronizeProfiles() {
        // Remap legacy "default"/UUID Rewrite profile ids onto integer ids required by
        // Supabase profile_id columns, and copy scoped DataStore blobs so Home prefs,
        // progress, library, and credentials stay visible after the id change.
        // v4 re-runs the credential family copy for installs that completed v3 without
        // migrating service-suffixed rewrite_debrid_v2_{profile}_{service} keys.
        dataStore.edit { preferences ->
            val migrationMarker = stringPreferencesKey("app_profile_migration_v4")
            val profileKey = stringPreferencesKey("app_profiles_v1")
            if (preferences[migrationMarker] != "complete") {
                val current = preferences[profileKey]?.let(ProfileCodec::decode) ?: ProfileState()
                val (remapped, remaps) = remapNonIntegerProfileIds(current)
                if (remaps.isNotEmpty()) {
                    preferences[profileKey] = ProfileCodec.encode(remapped)
                    preferences.copyRemappedProfileScopedPreferences(remaps)
                } else {
                    // Preserve the earlier default→1 blob copy for installs that already use "1".
                    preferences.copyLegacyDefaultProfileScopedPreferences()
                }
                preferences[migrationMarker] = "complete"
            }
            preferences.copyLegacyDefaultProfileScopedPreferences()
        }
        profiles.migrateIfDefault(
            profiles = profileManager.profiles.value.map { PriorProfileSnapshot(it.id.toString(), it.name) },
            activeProfileId = profileManager.activeProfileId.value.toString(),
            rememberLastProfile = profileManager.rememberLastProfileEnabled.value,
        )
        // Re-home orphaned TorBox/RD blobs onto the settled active profile. A prefs file
        // rename (playflix_rewrite→playflix_app) or profile-id remap left keys behind under
        // the old id so Connect looked empty after every update.
        val activeId = profiles.state.first().activeProfileId
        dataStore.edit { preferences ->
            preferences.recoverOrphanedDebridCredentials(activeId)
        }
        dataIntegrity.scan()
    }

    suspend fun selectProfile(profileId: String) {
        val legacyId = profileId.toIntOrNull()
        if (legacyId != null && profileManager.profiles.value.any { it.id == legacyId }) {
            profileManager.setActiveProfile(legacyId)
            profileManager.activeProfileId.first { it == legacyId }
        }
        profiles.select(profileId)
    }

    suspend fun setRememberLastProfile(enabled: Boolean) {
        profiles.setRememberLastProfile(enabled)
        profileManager.setRememberLastProfileEnabled(enabled)
    }

    suspend fun createProfile(name: String) {
        val before = profileManager.profiles.value.mapTo(mutableSetOf()) { it.id }
        if (!profileManager.createProfile(name)) return
        val created = profileManager.profiles.first { current ->
            current.any { it.id !in before }
        }.first { it.id !in before }
        profiles.addExternalProfile(created.id.toString(), created.name)
    }

    suspend fun renameProfile(profile: Profile, name: String) {
        profile.id.toIntOrNull()?.let { legacyId ->
            profileManager.profiles.value.firstOrNull { it.id == legacyId }?.let { existing ->
                profileManager.updateProfile(existing.copy(name = name.trim().take(32).ifBlank { "Viewer" }))
            }
        }
        profiles.rename(profile.id, name)
    }

    suspend fun removeProfile(profile: Profile) {
        val removed = profile.id.toIntOrNull()?.let { legacyId ->
            profileManager.deleteProfile(legacyId)
        } ?: true
        if (removed) profiles.remove(profile.id)
    }

}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

private sealed interface RootDestination {
    data object Home : RootDestination
    data object Profiles : RootDestination
    data object Settings : RootDestination
    data object CloudManager : RootDestination
    data object Search : RootDestination
    data class Browse(val filter: BrowseFilter) : RootDestination
    data class Details(val addonId: String, val type: String, val id: String) : RootDestination
    data class Streams(
        val type: String,
        val id: String,
        val title: String,
        val autoPick: Boolean = true,
        val posterUrl: String? = null,
        /** Landscape art for the play-handoff chrome; preferred over [posterUrl] when set. */
        val backdropUrl: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val addonId: String? = null,
        val parentId: String? = null,
        val parentType: String? = null,
        val resumePositionMs: Long = 0L,
        /** Comma-joined genres for AUTO engine (anime → MPV) before meta returns. */
        val contentGenres: String? = null,
        val contentLanguage: String? = null,
    ) : RootDestination
    /** Stremio-like "where to watch" surface when no debrid key is configured. */
    data class WatchHub(
        val type: String,
        val id: String,
        val title: String,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
    ) : RootDestination
}

private enum class BrowseFilter {
    MOVIES,
    TV_SHOWS,
    WATCHLIST,
}

/**
 * Compose shell for the rewrite-owned Home and Addons flows.
 *
 * More routes are added only as their data and handoff contracts are complete; this avoids
 * rendering a legacy screen behind a rewritten navigation façade.
 */
@Composable
fun AppShell(
    context: Context,
    graph: AppGraph,
    auth: SupabaseAuthGateway,
    sessions: SupabaseSessionStore,
    settingsFacade: SettingsFacade,
    watchProgressRepository: WatchProgressRepository,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    var accessGranted by remember { mutableStateOf(false) }
    var forceAuthChoice by remember { mutableStateOf(false) }
    var sessionBootKey by remember { mutableStateOf(0) }
    // Must live above the player route: navigating to app_player disposes app_root,
    // which would otherwise reset in-shell navigation to Home ("No home content…").
    var routeStack by remember {
        mutableStateOf(listOf<RootDestination>(RootDestination.Profiles))
    }
    // Survives app_root dispose/recreate when opening the retained player — otherwise
    // needsStartupProfilePick resets to true and Back/Play dumps you on Who's watching.
    var needsStartupProfilePick by remember { mutableStateOf(true) }
    var homeSplashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(sessionBootKey) {
        needsStartupProfilePick = true
        homeSplashVisible = false
    }
    val streamBadgeSettings by settingsFacade.streamBadgeSettings.collectAsState(
        initial = com.sluggyard.tv.core.streams.StreamBadgeSettings(),
    )
    LaunchedEffect(Unit) {
        runCatching { settingsFacade.ensureDefaultStreamBadgePack() }
    }
    NavHost(
        navController = navController,
        startDestination = "app_root",
        modifier = modifier,
    ) {
        composable("app_root") {
            if (accessGranted) {
                AppContent(
                    graph = graph,
                    settingsFacade = settingsFacade,
                    watchProgressRepository = watchProgressRepository,
                    routeStack = routeStack,
                    onRouteStackChange = { routeStack = it },
                    needsStartupProfilePick = needsStartupProfilePick,
                    onNeedsStartupProfilePickChange = { needsStartupProfilePick = it },
                    homeSplashVisible = homeSplashVisible,
                    onHomeSplashVisibleChange = { homeSplashVisible = it },
                    onSignedOut = {
                        accessGranted = false
                        forceAuthChoice = false
                        sessionBootKey += 1
                        routeStack = listOf(RootDestination.Profiles)
                    },
                    onOpenAuth = { forceAuthChoice = true; accessGranted = false },
                    sessionBootKey = sessionBootKey,
                    onLaunchRetainedPlayer = { source, title, type, id, posterUrl, backdropUrl, season, episode, addonId, parentId, parentType, resumePositionMs, contentGenres, contentLanguage ->
                        runCatching {
                            navController.navigate(
                                PlayerRoute.create(
                                    graph.stagePlaybackSource(source),
                                    title,
                                    id,
                                    type,
                                    posterUrl,
                                    backdropUrl,
                                    season,
                                    episode,
                                    addonId,
                                    parentId,
                                    parentType,
                                    resumePositionMs,
                                    contentGenres,
                                    contentLanguage,
                                ),
                            )
                        }.onFailure { failure ->
                            android.util.Log.e("AppShell", "Failed to open player", failure)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                    AuthGate(
                    context = context,
                    auth = auth,
                    sessions = sessions,
                        guestSession = graph.guestSession,
                        forceChoice = forceAuthChoice,
                        onAuthenticated = {
                            sessionBootKey += 1
                            routeStack = listOf(RootDestination.Profiles)
                            accessGranted = true
                        },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composable(
            route = PlayerRoute.pattern,
            arguments = listOf(
                navArgument("playbackKey") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("contentId") { type = NavType.StringType; defaultValue = "" },
                navArgument("contentType") { type = NavType.StringType; defaultValue = "" },
                navArgument("contentName") { type = NavType.StringType; defaultValue = "" },
                navArgument("videoId") { type = NavType.StringType; defaultValue = "" },
                navArgument("posterUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("backdrop") { type = NavType.StringType; defaultValue = "" },
                navArgument("season") { type = NavType.StringType; defaultValue = "" },
                navArgument("episode") { type = NavType.StringType; defaultValue = "" },
                navArgument("addonId") { type = NavType.StringType; defaultValue = "" },
                navArgument("parentId") { type = NavType.StringType; defaultValue = "" },
                navArgument("parentType") { type = NavType.StringType; defaultValue = "" },
                navArgument("startPositionMs") { type = NavType.StringType; defaultValue = "0" },
                navArgument("contentGenres") { type = NavType.StringType; defaultValue = "" },
                navArgument("contentLanguage") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            val playbackKey = it.arguments?.getString("playbackKey").orEmpty()
            val source = graph.playbackSource(playbackKey)
            if (source == null) {
                PlaybackExpiredScreen(onBack = { navController.popBackStack() })
            } else {
                RetainedPlayerHost(
                    entry = it,
                    initialSource = source,
                    progressRepository = graph.playbackProgress,
                    streamsDataSource = graph.streamsDataSource,
                    subtitleDataSource = graph.subtitleDataSource,
                    detailsDataSource = graph.detailsDataSource,
                    manualResolution = graph.manualResolution,
                    configuredDebrid = {
                        graph.debridRuntime.configuredService()
                            .takeIf { settingsFacade.debridSettings.first().enabled }
                    },
                    digitalReleaseLookup = graph.digitalReleaseLookup,
                    streamBadgeRules = streamBadgeSettings.rules,
                    onBack = {
                        graph.clearPlaybackSource(playbackKey)
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}

/** Independent route contract for the retained player boundary; it has no legacy navigation dependency. */
private object PlayerRoute {
    const val pattern = "app_player/{playbackKey}/{title}?contentId={contentId}&contentType={contentType}&contentName={contentName}&videoId={videoId}&posterUrl={posterUrl}&backdrop={backdrop}&season={season}&episode={episode}&addonId={addonId}&parentId={parentId}&parentType={parentType}&startPositionMs={startPositionMs}&contentGenres={contentGenres}&contentLanguage={contentLanguage}"

    fun create(
        playbackKey: String,
        title: String,
        contentId: String,
        contentType: String,
        posterUrl: String = "",
        backdropUrl: String = "",
        season: Int? = null,
        episode: Int? = null,
        addonId: String? = null,
        parentId: String? = null,
        parentType: String? = null,
        startPositionMs: Long = 0L,
        contentGenres: String? = null,
        contentLanguage: String? = null,
    ): String =
        "app_player/${Uri.encode(playbackKey)}/${Uri.encode(title)}?contentId=${Uri.encode(contentId)}&contentType=${Uri.encode(contentType)}&contentName=${Uri.encode(title)}&videoId=${Uri.encode(contentId)}&posterUrl=${Uri.encode(posterUrl)}&backdrop=${Uri.encode(backdropUrl)}&season=${season?.toString().orEmpty()}&episode=${episode?.toString().orEmpty()}&addonId=${Uri.encode(addonId.orEmpty())}&parentId=${Uri.encode(parentId.orEmpty())}&parentType=${Uri.encode(parentType.orEmpty())}&startPositionMs=$startPositionMs&contentGenres=${Uri.encode(contentGenres.orEmpty())}&contentLanguage=${Uri.encode(contentLanguage.orEmpty())}"
}

@Composable
private fun PlaybackExpiredScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedButton(onClick = onBack) { Text("Playback session expired") }
    }
}

@Composable
private fun AppContent(
    graph: AppGraph,
    settingsFacade: SettingsFacade,
    watchProgressRepository: WatchProgressRepository,
    routeStack: List<RootDestination>,
    onRouteStackChange: (List<RootDestination>) -> Unit,
    needsStartupProfilePick: Boolean,
    onNeedsStartupProfilePickChange: (Boolean) -> Unit,
    homeSplashVisible: Boolean,
    onHomeSplashVisibleChange: (Boolean) -> Unit,
    onSignedOut: () -> Unit,
    onOpenAuth: () -> Unit,
    sessionBootKey: Int,
    onLaunchRetainedPlayer: (ResolvedPlaybackSource, String, String, String, String, String, Int?, Int?, String?, String?, String?, Long, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val registry by graph.registry.state.collectAsState(initial = com.sluggyard.tv.core.addonprotocol.AddonRegistryState())
    val playbackCheckpoints by graph.playbackProgress.checkpoints.collectAsState(initial = emptyList())
    val traktAuth by settingsFacade.traktAuth.collectAsState(initial = TraktAuthState())
    val watchProgressSource by settingsFacade.traktSettings.watchProgressSource.collectAsState(
        initial = WatchProgressSource.TRAKT,
    )
    val useTraktCw = useTraktContinueWatching(watchProgressSource, traktAuth.isAuthenticated)
    val traktContinueWatching by watchProgressRepository.continueWatching.collectAsState(initial = emptyList())
    val continueWatchingCheckpoints = remember(useTraktCw, traktContinueWatching, playbackCheckpoints) {
        if (useTraktCw) {
            traktContinueWatching.map { it.toContinueWatchingCheckpoint() }
        } else {
            playbackCheckpoints
        }
    }
    val libraryWatch by graph.libraryWatch.state.collectAsState(initial = LibraryWatchState())
    val profiles by graph.profiles.state.collectAsState(initial = com.sluggyard.tv.ui.app.data.ProfileState())
    val homeSettings by graph.homeSettings.settings.collectAsState(initial = com.sluggyard.tv.ui.app.data.HomeSettings())
    val dataCorruptionNotices by graph.dataIntegrity.notices.collectAsState(initial = emptyList())
    val debridSettings by settingsFacade.debridSettings.collectAsState(initial = DebridSettings())
    val tmdbSettings by settingsFacade.tmdbSettings.collectAsState(initial = com.sluggyard.tv.domain.model.TmdbSettings())
    // Focus polish defaults — not user-facing settings (smooth scroll-into-view on;
    // horizontal D-pad hold uses the standard throttle, not the "fast" 48ms gate).
    val smoothFocusMovement = true
    val fastHorizontalNavigation = false
    val playerSettings by settingsFacade.playerSettings.collectAsState(
        initial = com.sluggyard.tv.data.local.PlayerSettings(),
    )
    val streamBadgeSettings by settingsFacade.streamBadgeSettings.collectAsState(
        initial = com.sluggyard.tv.core.streams.StreamBadgeSettings(),
    )
    val debridConnection by graph.debridRuntime.connection.collectAsState(initial = DebridConnection())
    val landscapePostersOnFocus by settingsFacade.layoutPreferences.modernLandscapePostersEnabled.collectAsState(initial = true)
    val destination = routeStack.last()
    var catalogState by remember { mutableStateOf(HomeCatalogState(emptyList())) }
    var browseCatalogState by remember { mutableStateOf(HomeCatalogState(emptyList())) }
    // Held outside the `when(destination)` branch below so Home's (and Browse's) per-row
    // horizontal scroll positions and hero rotation index survive navigating to Details/Streams
    // and back -- HomeScreen itself is fully disposed/recomposed on that round trip since
    // only one destination branch renders at a time.
    val homeRowScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val homeHeroIndexState = remember { mutableStateOf(0) }
    val homeLastHeroKeyState = remember { mutableStateOf<String?>(null) }
    val browseRowScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val browseHeroIndexState = remember { mutableStateOf(0) }
    val browseLastHeroKeyState = remember { mutableStateOf<String?>(null) }
    var heroEnrichments by remember { mutableStateOf<Map<String, HeroEnrichment>>(emptyMap()) }
    var continueWatchingTmdbPosters by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val communityProvisioningReport by graph.communityProvisioningReport.collectAsState()
    var detailsState by remember { mutableStateOf<DetailsState?>(null) }
    var detailsMessage by remember { mutableStateOf<String?>(null) }
    var detailsReloadNonce by remember { mutableStateOf(0) }
    var detailsRelated by remember { mutableStateOf<List<DetailsRelatedPoster>>(emptyList()) }
    var detailsRelatedLoading by remember { mutableStateOf(false) }
    var detailsActionNotice by remember { mutableStateOf<String?>(null) }
    var streamGroups by remember { mutableStateOf<List<StreamGroup>>(emptyList()) }
    var streamDiscoveryComplete by remember { mutableStateOf(false) }
    var streamMessage by remember { mutableStateOf<String?>(null) }
    var autoPickFinishedFor by remember { mutableStateOf<String?>(null) }
    var autoPickRejectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var profileBridgeReady by remember { mutableStateOf(false) }
    var homeReloadNonce by remember { mutableStateOf(0) }
    var streamResolutionJob by remember { mutableStateOf<Job?>(null) }
    var resolvingCandidateId by remember { mutableStateOf<String?>(null) }
    var continueWatchingMenuPoster by remember { mutableStateOf<HomePoster?>(null) }
    var continueWatchingFocusNonce by remember { mutableStateOf(0) }
    var suppressHomeHeroFocusRestore by remember { mutableStateOf(false) }
    var navigationRoot by remember { mutableStateOf<RootDestination>(RootDestination.Home) }
    // Who's watching / splash flags are owned by AppShell so retained-player navigation
    // (which disposes app_root) cannot reset them and force Profiles again.
    // Timestamp is owned by the dismiss effect when splash becomes visible — never leave it at
    // 0 while visible (that used to short-circuit the gate and hang forever after profile pick).
    var homeSplashStartedAt by remember(sessionBootKey) { mutableStateOf(0L) }
    var watchHubPlatforms by remember { mutableStateOf<List<WatchHubPlatform>>(emptyList()) }
    var watchHubLoading by remember { mutableStateOf(false) }
    var watchHubMessage by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // A saved debrid credential is enough — do not also require the legacy enabled flag.
    // WatchHub is only for Play when no provider key is connected.
    val hasActiveDebrid = debridConnection.activeService != null ||
        debridConnection.configuredServices.isNotEmpty()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ExperimentalDiagnostics.logDeviceSnapshot(context)
        ExperimentalDiagnostics.event("app", "composition_started", mapOf("destination" to destination.javaClass.simpleName))
    }
    LaunchedEffect(destination) {
        ExperimentalDiagnostics.event(
            "navigation",
            "destination_changed",
            mapOf(
                "destination" to destination.javaClass.simpleName,
                "routeDepth" to routeStack.size,
            ),
        )
    }

    fun resumeFor(contentId: String, season: Int? = null, episode: Int? = null): Long {
        val matchedCw = continueWatchingCheckpoints.firstOrNull {
            (it.contentId == contentId || it.parentId == contentId) &&
                it.season == season &&
                it.episode == episode
        }
        if (matchedCw != null) {
            // Trakt percent-only rows often have positionMs=0; keep 0 so the player resolves
            // from WatchProgressRepository instead of blending in a SlugYard local checkpoint.
            return matchedCw.positionMs.takeIf { it > 0L } ?: 0L
        }
        if (useTraktCw) return 0L
        return playbackCheckpoints.firstOrNull {
            it.contentId == contentId && it.season == season && it.episode == episode
        }?.positionMs ?: 0L
    }

    fun openRoot(next: RootDestination) {
        ExperimentalDiagnostics.event("navigation", "open_root", mapOf("destination" to next.javaClass.simpleName))
        onRouteStackChange(listOf(next))
        navigationRoot = when (next) {
            is RootDestination.Details,
            is RootDestination.Streams,
            is RootDestination.WatchHub,
            -> RootDestination.Home
            else -> next
        }
    }

    fun push(next: RootDestination) {
        ExperimentalDiagnostics.event(
            "navigation",
            "push",
            mapOf(
                "destination" to next.javaClass.simpleName,
                "previousDepth" to routeStack.size,
            ),
        )
        onRouteStackChange(routeStack + next)
    }

    fun replaceCurrent(next: RootDestination) {
        ExperimentalDiagnostics.event(
            "navigation",
            "replace_current",
            mapOf(
                "destination" to next.javaClass.simpleName,
                "previousDepth" to routeStack.size,
            ),
        )
        onRouteStackChange(routeStack.dropLast(1) + next)
    }

    fun popRoute() {
        ExperimentalDiagnostics.event("navigation", "pop", mapOf("previousDepth" to routeStack.size))
        if (routeStack.size <= 1) {
            openRoot(RootDestination.Home)
            return
        }
        // Who's watching must never sit under Details/Home after a content Back.
        val next = routeStack.dropLast(1).filterNot { it is RootDestination.Profiles }
        when {
            next.isEmpty() -> openRoot(RootDestination.Home)
            next.last() is RootDestination.Profiles -> openRoot(RootDestination.Home)
            else -> onRouteStackChange(next)
        }
    }

    /** No debrid → WatchHub "where to watch"; otherwise torrent auto-pick Streams. */
    fun playOrWatchHubDestination(
        type: String,
        id: String,
        title: String,
        posterUrl: String? = null,
        backdropUrl: String? = null,
        season: Int? = null,
        episode: Int? = null,
        addonId: String? = null,
        parentId: String? = null,
        parentType: String? = null,
        resumePositionMs: Long = 0L,
        contentGenres: String? = null,
        contentLanguage: String? = null,
    ): RootDestination =
        if (SlugYardCommunitySourcePolicy.shouldRoutePlayToWatchHub(hasActiveDebrid)) {
            RootDestination.WatchHub(
                type = type,
                id = id,
                title = title,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
            )
        } else {
            RootDestination.Streams(
                type = type,
                id = id,
                title = title,
                autoPick = true,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                season = season,
                episode = episode,
                addonId = addonId,
                parentId = parentId,
                parentType = parentType,
                resumePositionMs = resumePositionMs,
                contentGenres = contentGenres,
                contentLanguage = contentLanguage,
            )
        }

    fun pushPlayOrWatchHub(
        type: String,
        id: String,
        title: String,
        posterUrl: String? = null,
        backdropUrl: String? = null,
        season: Int? = null,
        episode: Int? = null,
        addonId: String? = null,
        parentId: String? = null,
        parentType: String? = null,
        resumePositionMs: Long = 0L,
        contentGenres: String? = null,
        contentLanguage: String? = null,
    ) {
        push(
            playOrWatchHubDestination(
                type = type,
                id = id,
                title = title,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                season = season,
                episode = episode,
                addonId = addonId,
                parentId = parentId,
                parentType = parentType,
                resumePositionMs = resumePositionMs,
                contentGenres = contentGenres,
                contentLanguage = contentLanguage,
            ),
        )
    }

    /** After Streams hands off to the player, Back must land on Details whenever possible. */
    fun stackForPlayerExit(streams: RootDestination.Streams): List<RootDestination> {
        // Profiles belongs to Who's watching only — never under the content Back stack.
        var stack = routeStack
            .dropLastWhile { it is RootDestination.Streams }
            .filterNot { it is RootDestination.Profiles }
        val detailsId = streams.parentId
            ?: inferSeriesParentId(streams.id, streams.season, streams.episode)
            ?: streams.id.takeIf { streams.season == null && streams.episode == null }
        val detailsType = streams.parentType
            ?: streams.type.takeIf { streams.season != null || streams.episode != null }?.let { "series" }
            ?: streams.type.takeIf { streams.season == null && streams.episode == null }
            ?: "movie"
        val addonId = streams.addonId ?: resolveMetadataAddonId(registry)
        val existing = stack.indexOfLast { dest ->
            dest is RootDestination.Details && dest.id == detailsId
        }
        if (existing >= 0) {
            // Keep Details, drop anything after it (stale routes), then ensure it's on top.
            stack = stack.take(existing + 1)
        } else if (detailsId != null && addonId != null) {
            stack = stack + RootDestination.Details(
                addonId = addonId,
                type = detailsType,
                id = detailsId,
            )
        }
        // Hero Play uses replaceCurrent(Streams) which drops Home — restore it under Details
        // so Back from Details never falls through to Profiles or an empty stack.
        val hasContentRoot = stack.any {
            it is RootDestination.Home ||
                it is RootDestination.Search ||
                it is RootDestination.Browse ||
                it is RootDestination.Settings ||
                it is RootDestination.CloudManager
        }
        if (!hasContentRoot) {
            stack = listOf(RootDestination.Home) + stack
        }
        return stack.ifEmpty { listOf(RootDestination.Home) }
    }
    // Every persistent header control owns an explicit Down target. The content surfaces own their
    // return focus and vertical row graph, so header traversal does not fall back to geometry.
    val homeContentFocusRequester = remember { FocusRequester() }
    val homeHeaderFocusRequester = remember { FocusRequester() }
    val profilesContentFocusRequester = remember { FocusRequester() }
    val settingsContentFocusRequester = remember { FocusRequester() }
    val cloudManagerFocusRequester = remember { FocusRequester() }
    val settingsHeaderFocusRequester = remember { FocusRequester() }
    val cloudHeaderFocusRequester = remember { FocusRequester() }
    val detailsContentFocusRequester = remember { FocusRequester() }
    val streamsContentFocusRequester = remember { FocusRequester() }
    val watchHubContentFocusRequester = remember { FocusRequester() }
    val searchContentFocusRequester = remember { FocusRequester() }
    val searchHeaderFocusRequester = remember { FocusRequester() }
    var homeInitialFocusRequested by remember { mutableStateOf(false) }
    var profilesReturnDestination by remember { mutableStateOf<RootDestination>(RootDestination.Home) }

    LaunchedEffect(graph) {
        graph.synchronizeProfiles()
        profileBridgeReady = true
    }

    // Who's watching is mandatory on cold start — do not re-apply effectiveProfileId
    // after the user picks (that used to overwrite the choice when rememberLastProfile=false).

    LaunchedEffect(needsStartupProfilePick) {
        if (needsStartupProfilePick && destination !is RootDestination.Profiles) {
            openRoot(RootDestination.Profiles)
        }
    }

    LaunchedEffect(
        graph,
        profiles.activeProfileId,
        debridSettings.communityAddonEnabled,
        debridConnection.activeService,
        debridConnection.configuredServices,
        registry.addons.size,
    ) {
        val hasActiveDebrid = debridConnection.activeService != null ||
            debridConnection.configuredServices.isNotEmpty()
        val packInstalled = SlugYardCommunitySourcePolicy.isCommunityPackInstalled(registry.addons)
        when {
            debridSettings.communityAddonEnabled -> {
                graph.provisionCommunitySources(hasActiveDebrid)
            }
            packInstalled -> {
                // Legacy installs predate the install flag — mark enabled so Uninstall sticks.
                settingsFacade.setCommunityAddonEnabled(true)
                graph.provisionCommunitySources(hasActiveDebrid)
            }
            // Uninstalled (flag false + empty registry): stay clear until Install or WatchHub ensure.
        }
    }

    BackHandler(
        enabled = when {
            needsStartupProfilePick && destination is RootDestination.Profiles -> false
            destination is RootDestination.Home -> false
            else -> true
        },
    ) {
        streamResolutionJob?.cancel()
        streamResolutionJob = null
        resolvingCandidateId = null
        (destination as? RootDestination.Streams)?.let { streams ->
            autoPickFinishedFor = streams.id
        }
        popRoute()
    }

    LaunchedEffect(destination, detailsReloadNonce) {
        if (destination !is RootDestination.Streams) {
            streamResolutionJob?.cancel()
            streamResolutionJob = null
            resolvingCandidateId = null
        }
    }

    // Use only the values that affect catalogRequests as the effect key. The registry and
    // settings flows can emit equivalent snapshots with fresh collection instances while a row
    // is settling; keying the effect directly to those objects cancels the fan-out and puts every
    // row back into Loading before the completed snapshot can reach the screen.
    val homeCatalogReloadKey = remember(registry.addons, homeSettings, tmdbSettings.enabled, tmdbSettings.modernHomeEnabled, tmdbSettings.language) {
        buildString {
            registry.enabledAddons.forEach { addon ->
                append(addon.manifest.id)
                    .append('\u0001')
                    .append(addon.configuredManifestUrl ?: addon.manifestUrl)
                    .append('\u0001')
                addon.manifest.catalogs.forEach { catalog ->
                    append(catalog.id)
                        .append(':')
                        .append(catalog.type)
                        .append(':')
                        .append(catalog.displayName)
                        .append(':')
                        .append(catalog.extras.joinToString(",") { extra -> "${extra.name}=${extra.required}" })
                        .append('\u0002')
                }
                append('\u0003')
            }
            append(homeSettings.hideUnreleased)
                .append('\u0001')
                .append(homeSettings.excludedCatalogKeys.sorted().joinToString(","))
                .append('\u0001')
                .append(homeSettings.catalogOrderKeys.joinToString(","))
                .append('\u0001')
                .append(tmdbSettings.enabled)
                .append('\u0001')
                .append(tmdbSettings.modernHomeEnabled)
                .append('\u0001')
                .append(tmdbSettings.language)
        }
    }
    LaunchedEffect(homeCatalogReloadKey, homeReloadNonce) {
        val requests = graph.homeDataSource.catalogRequests()
        com.sluggyard.tv.ui.app.home.loadHomeCatalogs(
            requests,
            savedOrder = homeSettings.savedCatalogOrder(),
        ).collect { state ->
            catalogState = state
        }
    }
    LaunchedEffect(destination, homeCatalogReloadKey, homeReloadNonce) {
        val browse = destination as? RootDestination.Browse ?: return@LaunchedEffect
        if (browse.filter == BrowseFilter.WATCHLIST) return@LaunchedEffect
        val requests = graph.homeDataSource.catalogRequests()
        com.sluggyard.tv.ui.app.home.loadHomeCatalogs(
            requests,
            savedOrder = homeSettings.savedCatalogOrder(),
        ).collect { state ->
            browseCatalogState = state
        }
    }
    val rawHomeState = remember(catalogState, continueWatchingCheckpoints) {
        catalogState.toScreenState(continueWatchingCheckpoints)
    }
    // Catalog rows can transiently flicker load state as later refinements arrive, which would
    // otherwise flip the computed hero to null for a frame and force-recreate the hero panel
    // (losing its focus/scroll handoff). Stick with the last real hero across those blips.
    var stickyHero by remember { mutableStateOf<com.sluggyard.tv.ui.app.home.Hero?>(null) }
    if (rawHomeState.hero != null) stickyHero = rawHomeState.hero
    val baseHomeState = if (rawHomeState.hero == null && stickyHero != null) {
        rawHomeState.copy(hero = stickyHero)
    } else {
        rawHomeState
    }
    val heroCandidateKey = baseHomeState.heroCandidates.joinToString(separator = "|") { hero ->
        "${hero.addonId}:${hero.contentType}:${hero.id}"
    }
    val communityStatusMessage = communityProvisioningReport
        ?.takeIf { it.unavailableCount > 0 }
        ?.let { report ->
            if (report.installedCount == 0) {
                report.unavailableReasons.firstOrNull()?.let { reason ->
                    "Community sources are unavailable: $reason"
                } ?: "Community sources are unavailable. Check Debrid settings or retry."
            } else {
                report.unavailableReasons.firstOrNull()?.let { reason ->
                    "${report.unavailableCount} community source${if (report.unavailableCount == 1) "" else "s"} unavailable: $reason"
                } ?: "${report.unavailableCount} community source${if (report.unavailableCount == 1) "" else "s"} unavailable."
            }
        }
    val integrityStatusMessage = dataCorruptionNotices.takeIf { it.isNotEmpty() }?.let {
        "Some local settings could not be read. They were kept for recovery; review Settings or retry."
    }
    val cloudSyncNotice by CloudSyncNotices.latestNotice.collectAsState(initial = null)
    val enrichedHomeState = baseHomeState
        .withHeroEnrichments(heroEnrichments)
        .withContinueWatchingPosters(continueWatchingTmdbPosters)
        .let { state ->
            state.copy(
                statusMessage = state.statusMessage
                    ?: cloudSyncNotice
                    ?: communityStatusMessage
                    ?: integrityStatusMessage,
            )
        }
    LaunchedEffect(
        continueWatchingCheckpoints,
        tmdbSettings.enabled,
        tmdbSettings.enrichContinueWatching,
        tmdbSettings.useArtwork,
    ) {
        val tmdb = graph.tmdbService
        if (
            tmdb == null ||
            !tmdbSettings.enabled ||
            !tmdbSettings.enrichContinueWatching ||
            !tmdbSettings.useArtwork
        ) {
            continueWatchingTmdbPosters = emptyMap()
            return@LaunchedEffect
        }
        val grouped = groupPlaybackCheckpoints(continueWatchingCheckpoints)
        val posters = enrichContinueWatchingPosters(grouped, tmdb)
        continueWatchingTmdbPosters = posters
        if (!useTraktCw) {
            for (checkpoint in grouped) {
                val poster = posters[checkpoint.contentId] ?: continue
                if (checkpoint.posterUrl == poster) continue
                runCatching {
                    graph.playbackProgress.save(checkpoint.copy(posterUrl = poster))
                }
            }
        }
    }
    LaunchedEffect(heroCandidateKey, homeHeroIndexState.value) {
        // Enrich the visible hero first — not all carousel candidates — so cold start
        // isn't blocked by up to 8 parallel meta fetches.
        val candidates = baseHomeState.heroCandidates
        if (candidates.isEmpty()) {
            heroEnrichments = emptyMap()
            return@LaunchedEffect
        }
        val index = homeHeroIndexState.value.coerceIn(0, candidates.lastIndex)
        val primary = candidates[index]
        val upgrade = graph.heroEnrichmentDataSource.enrich(primary)
        if (upgrade != null) {
            heroEnrichments = heroEnrichments + (primary.id to upgrade)
        }
        val next = candidates.getOrNull((index + 1) % candidates.size)
            ?.takeIf { it.id != primary.id }
            ?: return@LaunchedEffect
        graph.heroEnrichmentDataSource.enrich(next)?.let { nextUpgrade ->
            heroEnrichments = heroEnrichments + (next.id to nextUpgrade)
        }
    }

    LaunchedEffect(homeReloadNonce) {
        if (homeReloadNonce > 0) {
            onHomeSplashVisibleChange(true)
        }
    }
    // Single dismiss gate: start the clock when splash is shown, then leave on catalog settle,
    // empty-home after the min duration, or the hard max — never hang on a zeroed timestamp.
    LaunchedEffect(homeSplashVisible, homeReloadNonce) {
        if (!homeSplashVisible) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        homeSplashStartedAt = startedAt
        while (homeSplashVisible) {
            val rows = catalogState.rows
            val pending = rows.count { it.loadState is CatalogLoadState.Loading }
            val elapsed = System.currentTimeMillis() - startedAt
            // Settled rows, or still-empty after min (no catalogs / hung request builder).
            val catalogsReady = when {
                pending > 0 -> false
                rows.isNotEmpty() -> true
                elapsed >= HomeSplashMinDurationMs -> true
                else -> false
            }
            if (elapsed >= HomeSplashMinDurationMs && catalogsReady) {
                onHomeSplashVisibleChange(false)
                break
            }
            if (elapsed >= HomeSplashMaxDurationMs) {
                onHomeSplashVisibleChange(false)
                break
            }
            delay(100)
        }
    }
    LaunchedEffect(destination, detailsReloadNonce) {
        val details = destination as? RootDestination.Details ?: return@LaunchedEffect
        detailsState = null
        detailsMessage = null
        detailsRelated = emptyList()
        detailsRelatedLoading = true
        detailsActionNotice = null
        when (val result = graph.detailsDataSource.load(details.addonId, details.type, details.id)) {
            is DetailsLoadResult.Ready -> detailsState = result.state
            is DetailsLoadResult.Unavailable -> detailsMessage = result.message
        }
    }
    LaunchedEffect(detailsState?.id, detailsState?.isSeries, detailsState?.contentLanguage, destination) {
        val details = destination as? RootDestination.Details ?: return@LaunchedEffect
        val state = detailsState ?: return@LaunchedEffect
        detailsRelatedLoading = true
        val apiRelated = graph.related?.load(
            contentType = details.type,
            contentId = state.id,
            language = state.contentLanguage,
        ).orEmpty()
        detailsRelated = apiRelated.ifEmpty {
            // Catalog fallback only when TMDB/Trakt return nothing.
            val seriesTypes = setOf("series", "show", "shows", "tv", "tvshow", "tvshows")
            val wantSeries = state.isSeries
            val genreTokens = state.genres.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
            enrichedHomeState.rows
                .asSequence()
                .filter { it.id != "continue_watching" }
                .flatMap { it.posters.asSequence() }
                .filter { it.id != state.id }
                .filter { poster ->
                    val isSeries = poster.contentType.orEmpty().trim().lowercase() in seriesTypes
                    isSeries == wantSeries
                }
                .distinctBy { it.id }
                .sortedByDescending { poster ->
                    if (genreTokens.isEmpty()) 0
                    else genreTokens.count { token -> token in poster.contentGenres.orEmpty().lowercase() }
                }
                .take(12)
                .map {
                    DetailsRelatedPoster(
                        id = it.id,
                        title = it.title,
                        imageUrl = com.sluggyard.tv.ui.app.preferLargePosterUrl(it.imageUrl),
                        contentType = it.contentType,
                        addonId = it.addonId,
                    )
                }
                .toList()
        }
        detailsRelatedLoading = false
    }
    // Key only on the Streams target + debrid — NOT registry.addons. Community provision
    // mutates the addon list while Play is already finding streams; restarting this effect
    // resets streamDiscoveryComplete=false after the 20s ceiling has already fired once,
    // which left the UI stuck on "Finding a playable stream…" forever.
    val streamsDiscoveryKey = remember(destination, debridConnection.activeService, debridConnection.configuredServices) {
        val streams = destination as? RootDestination.Streams
        listOf(
            streams?.type,
            streams?.id,
            streams?.season,
            streams?.episode,
            debridConnection.activeService,
            debridConnection.configuredServices,
        )
    }
    LaunchedEffect(streamsDiscoveryKey) {
        val streams = destination as? RootDestination.Streams ?: return@LaunchedEffect
        streamGroups = emptyList()
        streamDiscoveryComplete = false
        streamMessage = null
        autoPickFinishedFor = null
        autoPickRejectedIds = emptySet()
        // After install/force-stop the curated stream addons can be missing until provision runs.
        if (hasActiveDebrid) {
            runCatching { graph.provisionCommunitySources(true) }
        }
        graph.streamsDataSource.streamGroups(
            streams.type,
            streams.id,
            configuredDebrid = debridConnection.activeService,
        ).collect { groups ->
            streamGroups = groups
            // Mark discovery done as soon as every addon left Loading — do not wait for TorBox
            // cache probes (those can take a long time and previously left the UI stuck on
            // "Finding a playable stream…").
            if (groups.isNotEmpty() &&
                groups.none { it.state is com.sluggyard.tv.ui.app.streams.StreamGroupState.Loading }
            ) {
                streamDiscoveryComplete = true
            }
        }
        streamDiscoveryComplete = true
    }

    LaunchedEffect(Unit) {
        runCatching { settingsFacade.ensureDefaultStreamBadgePack() }
    }
    // Cap how long we wait on CHECKING badges (auto-pick AND the Sources list) so a slow/broken
    // cache API cannot leave Play stuck on "Finding…" or Sources stuck on "Checking".
    var cacheWaitExpired by remember(streamsDiscoveryKey) { mutableStateOf(false) }
    LaunchedEffect(streamsDiscoveryKey, streamDiscoveryComplete) {
        cacheWaitExpired = false
        val streams = destination as? RootDestination.Streams ?: return@LaunchedEffect
        if (!streamDiscoveryComplete) return@LaunchedEffect
        delay(8_000)
        cacheWaitExpired = true
    }
    // Once at least one addon has settled (Content/Empty/Error), do not wait forever for hung
    // addons that often return empty — unblock auto-pick after a short grace.
    // Anime: wait on AIOStreams only while no cached ASS/softsub winner exists yet.
    LaunchedEffect(streamsDiscoveryKey, streamGroups, playerSettings.subtitleStyle.preferredLanguage) {
        val streams = destination as? RootDestination.Streams ?: return@LaunchedEffect
        if (streamDiscoveryComplete) return@LaunchedEffect
        val hasSettled = streamGroups.any {
            it.state !is com.sluggyard.tv.ui.app.streams.StreamGroupState.Loading
        }
        val stillLoading = streamGroups.any {
            it.state is com.sluggyard.tv.ui.app.streams.StreamGroupState.Loading
        }
        if (!hasSettled || !stillLoading) return@LaunchedEffect
        delay(4_000)
        if (destination != streams || streamDiscoveryComplete) return@LaunchedEffect
        val softsubReady = hasEligibleAnimeSoftsub(
            streams,
            streamGroups,
            playerSettings.subtitleStyle.preferredLanguage,
        )
        val animeWaitOnAio = !softsubReady &&
            isAnimeAutoPlayWait(streams) &&
            streamGroups.any { it.isAioStreamsGroup() && it.state is com.sluggyard.tv.ui.app.streams.StreamGroupState.Loading }
        if (animeWaitOnAio) return@LaunchedEffect
        streamDiscoveryComplete = true
    }
    // Hard ceiling: last-resort unblock if an addon never leaves Loading.
    // Softsub-ready anime paths exit earlier; this is not the primary wait gate.
    var discoveryHardCeilingReached by remember(streamsDiscoveryKey) { mutableStateOf(false) }
    LaunchedEffect(streamsDiscoveryKey) {
        val streams = destination as? RootDestination.Streams ?: return@LaunchedEffect
        discoveryHardCeilingReached = false
        delay(20_000)
        if (destination == streams) {
            streamDiscoveryComplete = true
            discoveryHardCeilingReached = true
        }
    }
    // Anime fast-path: once any addon already has a cached ASS/softsub auto-play winner,
    // stop idling on AIOStreams Loading (the old path waited ~20–30s every time).
    LaunchedEffect(streamsDiscoveryKey, streamGroups, playerSettings.subtitleStyle.preferredLanguage) {
        val streams = destination as? RootDestination.Streams ?: return@LaunchedEffect
        if (streamDiscoveryComplete || !isAnimeAutoPlayWait(streams)) return@LaunchedEffect
        if (
            hasEligibleAnimeSoftsub(
                streams,
                streamGroups,
                playerSettings.subtitleStyle.preferredLanguage,
            )
        ) {
            streamDiscoveryComplete = true
        }
    }
    val displayStreamGroups = remember(streamGroups, cacheWaitExpired, streamBadgeSettings.rules) {
        val remapped = if (!cacheWaitExpired) streamGroups else remapCheckingCacheStates(streamGroups)
        StreamBadgeApplicator.apply(remapped, streamBadgeSettings.rules)
            .filterNot {
                SlugYardCommunitySourcePolicy.isPlayFlixStreamAddon(it.addonId, it.addonName)
            }
    }

    fun resolveStreamCandidate(candidate: StreamCandidate, streams: RootDestination.Streams) {
        if (resolvingCandidateId != null) return
        // Auto-pick: stop after a few failed resolves so debrid timeouts cannot look like an
        // infinite "Finding a playable stream…" spinner.
        if (streams.autoPick && autoPickRejectedIds.size >= MaxAutoPickResolveAttempts) {
            autoPickFinishedFor = streams.id
            if (streamMessage.isNullOrBlank()) {
                streamMessage = "No cached playable stream was found. Open Sources and pick an Instant/Cached option."
            }
            return
        }
        resolvingCandidateId = candidate.id
        if (streams.autoPick) streamMessage = null
        streamResolutionJob = scope.launch {
            try {
                when (val result = withTimeout(45_000L) {
                    graph.manualResolution.prepare(
                        candidate.toManualSelection(
                            season = streams.season,
                            episode = streams.episode,
                            forceDebridForTorrent = streams.autoPick && !candidate.infoHash.isNullOrBlank(),
                        ),
                        configuredService = debridConnection.activeService,
                    )
                }) {
                    is ManualResolutionResult.Ready -> {
                        if (routeStack.lastOrNull() != streams) return@launch
                        streamMessage = null
                        // Launch the retained player BEFORE rewriting the route stack. Changing
                        // stack first briefly reveals Details under Streams and flashes ~0.5s of
                        // non-preparing chrome between "Finding a stream" and "Building player".
                        val exitStack = stackForPlayerExit(streams)
                        onLaunchRetainedPlayer(
                            result.source,
                            streams.title,
                            streams.type,
                            streams.id,
                            streams.posterUrl ?: detailsState?.posterUrl.orEmpty(),
                            playPreparingArtUrl(
                                streams.backdropUrl ?: detailsState?.backdropUrl,
                                streams.posterUrl ?: detailsState?.posterUrl,
                            ).orEmpty(),
                            streams.season,
                            streams.episode,
                            streams.addonId,
                            streams.parentId,
                            streams.parentType,
                            streams.resumePositionMs,
                            streams.contentGenres
                                ?: detailsState?.genres?.takeIf { it.isNotEmpty() }?.joinToString(","),
                            streams.contentLanguage,
                        )
                        onRouteStackChange(exitStack)
                    }
                    is ManualResolutionResult.Unavailable -> {
                        streamMessage = result.message
                        if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
                    }
                    is ManualResolutionResult.Failed -> {
                        streamMessage = result.message
                        if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
                    }
                }
            } catch (_: TimeoutCancellationException) {
                streamMessage = "The configured debrid provider did not resolve this stream within 45 seconds. Choose another source."
                if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                streamMessage = failure.message?.takeIf { it.isNotBlank() }
                    ?: "The selected stream could not be resolved. Choose another source."
                if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
            } finally {
                resolvingCandidateId = null
                streamResolutionJob = null
            }
        }
    }

    LaunchedEffect(destination, streamGroups, streamDiscoveryComplete, autoPickRejectedIds, cacheWaitExpired, displayStreamGroups, discoveryHardCeilingReached) {
        val streams = destination as? RootDestination.Streams ?: return@LaunchedEffect
        if (!streams.autoPick || autoPickFinishedFor == streams.id || resolvingCandidateId != null) return@LaunchedEffect
        if (autoPickRejectedIds.size >= MaxAutoPickResolveAttempts) {
            autoPickFinishedFor = streams.id
            if (streamMessage.isNullOrBlank()) {
                streamMessage = "No cached playable stream was found. Open Sources and pick an Instant/Cached option."
            }
            return@LaunchedEffect
        }
        val hasCachedAlready = streamGroups
            .flatMap { (it.state as? com.sluggyard.tv.ui.app.streams.StreamGroupState.Content)?.streams.orEmpty() }
            .any {
                it.cacheState == com.sluggyard.tv.core.streamresolution.StreamCacheState.CACHED ||
                    (!it.directUrl.isNullOrBlank() && it.infoHash.isNullOrBlank())
            }
        // Always wait for every enabled addon to leave Loading before choosing a winner. A cached
        // result from one provider must not hide a slower source that can return better streams.
        if (!streamDiscoveryComplete) return@LaunchedEffect
        // Anime: wait on AIOStreams only until a cached ASS/softsub winner exists (or ceiling).
        val softsubReady = hasEligibleAnimeSoftsub(
            streams,
            streamGroups,
            playerSettings.subtitleStyle.preferredLanguage,
        )
        if (!discoveryHardCeilingReached &&
            !softsubReady &&
            isAnimeAutoPlayWait(streams) &&
            streamGroups.any {
                it.isAioStreamsGroup() &&
                    it.state is com.sluggyard.tv.ui.app.streams.StreamGroupState.Loading
            }
        ) {
            return@LaunchedEffect
        }
        // Once addon fanout has settled, a confirmed cached hit can bypass the separate cache-probe
        // wait without delaying auto-pick for unrelated uncached candidates.
        if (!cacheWaitExpired && !hasCachedAlready && hasPendingCacheChecks(streamGroups)) {
            return@LaunchedEffect
        }
        val groupsForPick = (if (cacheWaitExpired) displayStreamGroups else streamGroups)
            .filterNot {
                SlugYardCommunitySourcePolicy.isPlayFlixStreamAddon(it.addonId, it.addonName)
            }
        val genreList = streams.contentGenres
            ?.split(',', '|', '/')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
            .ifEmpty { detailsState?.genres.orEmpty() }
        // Resolve + score off the main thread — ranking hundreds of candidates on Main skipped
        // frames and made the Finding animation freeze until playback finally started.
        val pickOutcome = withContext(Dispatchers.Default) {
            val releaseLookupId = runCatching {
                graph.resolveMetaContentId(streams.type, streams.id)
            }.getOrDefault(streams.id)
            val digitalReleaseStatus = graph.digitalReleaseLookup?.movieStatus(
                contentId = releaseLookupId,
                contentType = streams.type,
            ) ?: DigitalReleasePolicy.Status.UNKNOWN
            android.util.Log.i(
                "DigitalRelease",
                "auto-pick id=${streams.id} lookupId=$releaseLookupId type=${streams.type} status=$digitalReleaseStatus",
            )
            if (digitalReleaseStatus == DigitalReleasePolicy.Status.NOT_YET) {
                return@withContext AutoPickOutcome.NotDigitallyReleased
            }
            val contentKey = "${streams.type.lowercase()}|${streams.id}"
            val cacheHours = playerSettings.streamReuseLastLinkCacheHours.coerceIn(1, 168)
            val lastPlayed = runCatching {
                graph.streamLinkCache?.getValid(
                    contentKey = contentKey,
                    maxAgeMs = cacheHours * 60L * 60L * 1000L,
                )
            }.getOrNull()
            if (lastPlayed != null) {
                val listed = groupsForPick
                    .flatMap { (it.state as? com.sluggyard.tv.ui.app.streams.StreamGroupState.Content)?.streams.orEmpty() }
                    .filter { it.id !in autoPickRejectedIds }
                val lastMatch = listed.firstOrNull { it.matchesLastPlayed(lastPlayed) }
                if (lastMatch != null) {
                    val playable =
                        lastMatch.cacheState == com.sluggyard.tv.core.streamresolution.StreamCacheState.CACHED ||
                            (!lastMatch.directUrl.isNullOrBlank() && lastMatch.infoHash.isNullOrBlank()) ||
                            (
                                !lastMatch.infoHash.isNullOrBlank() &&
                                    lastMatch.cacheState == com.sluggyard.tv.core.streamresolution.StreamCacheState.NOT_APPLICABLE
                                )
                    if (playable) {
                        return@withContext AutoPickOutcome.Play(lastMatch)
                    }
                    val liveMatchState = streamGroups
                        .flatMap { (it.state as? com.sluggyard.tv.ui.app.streams.StreamGroupState.Content)?.streams.orEmpty() }
                        .firstOrNull { it.id == lastMatch.id }
                        ?.cacheState
                    if (liveMatchState == com.sluggyard.tv.core.streamresolution.StreamCacheState.CHECKING ||
                        hasPendingCacheChecks(streamGroups)
                    ) {
                        return@withContext AutoPickOutcome.Wait
                    }
                    return@withContext AutoPickOutcome.LastSourceUnavailable
                }
                if (streamGroups.any { it.state is com.sluggyard.tv.ui.app.streams.StreamGroupState.Loading } ||
                    hasPendingCacheChecks(streamGroups)
                ) {
                    return@withContext AutoPickOutcome.Wait
                }
                return@withContext AutoPickOutcome.LastSourceUnavailable
            }
            val candidate = selectAutoPlayCandidate(
                groups = groupsForPick,
                context = com.sluggyard.tv.ui.app.streams.StreamScoringEngine.Context(
                    title = streams.title,
                    contentType = streams.type,
                    genres = genreList,
                    language = streams.contentLanguage ?: detailsState?.contentLanguage,
                    preferredSubtitleLanguage = playerSettings.subtitleStyle.preferredLanguage,
                    digitalReleaseStatus = digitalReleaseStatus,
                ),
                excludedCandidateIds = autoPickRejectedIds,
                preferLastPlayed = null,
            )
            if (candidate != null) return@withContext AutoPickOutcome.Play(candidate)
            if (!streamDiscoveryComplete) return@withContext AutoPickOutcome.Wait
            if (streamGroups.isEmpty()) return@withContext AutoPickOutcome.Wait
            if (streamGroups.any { it.state is com.sluggyard.tv.ui.app.streams.StreamGroupState.Loading } ||
                hasPendingCacheChecks(streamGroups)
            ) {
                return@withContext AutoPickOutcome.Wait
            }
            AutoPickOutcome.NoneAvailable
        }
        when (pickOutcome) {
            AutoPickOutcome.Wait -> return@LaunchedEffect
            AutoPickOutcome.NotDigitallyReleased -> {
                autoPickFinishedFor = streams.id
                streamMessage =
                    "This title is not digitally released yet. Open Sources if you still want to browse listings."
            }
            AutoPickOutcome.LastSourceUnavailable -> {
                autoPickFinishedFor = streams.id
                streamMessage = "Your last source is not available right now. Open Sources to pick another."
                replaceCurrent(streams.copy(autoPick = false))
            }
            AutoPickOutcome.NoneAvailable -> {
                autoPickFinishedFor = streams.id
                streamMessage = if (detailsState?.availability?.isNotEmpty() == true) {
                    "No cached playable stream. Watch on: ${detailsState?.availability?.joinToString(", ")}"
                } else {
                    "No cached playable stream was found. Open Sources and pick an Instant/Cached option."
                }
            }
            is AutoPickOutcome.Play -> resolveStreamCandidate(pickOutcome.candidate, streams)
        }
    }

    SlugYardTvTheme {
        CompositionLocalProvider(
            LocalContentColor provides SlugYardPalette.OnCanvas,
            LocalFastHorizontalNavigationEnabled provides fastHorizontalNavigation,
        ) {
        val navDestination = when (destination) {
            is RootDestination.Details,
            is RootDestination.Streams,
            is RootDestination.WatchHub,
            -> navigationRoot
            RootDestination.Home -> RootDestination.Home
            RootDestination.Profiles -> RootDestination.Profiles
            RootDestination.Settings -> RootDestination.Settings
            RootDestination.CloudManager -> RootDestination.CloudManager
            RootDestination.Search -> RootDestination.Search
            is RootDestination.Browse -> destination
        }
        val showRootNav = destination !is RootDestination.Profiles &&
            destination !is RootDestination.Details &&
            destination !is RootDestination.Streams &&
            destination !is RootDestination.WatchHub
        // Home: Netflix-style floating nav over the hero. Other tabs keep a reserved top inset
        // so list content never sits under the icons.
        val immersiveHomeNav = destination == RootDestination.Home
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SlugYardPalette.Canvas)
                .onPreviewKeyEvent { event ->
                    ExperimentalDiagnostics.event(
                        "input",
                        "key_event",
                        mapOf(
                            "destination" to destination.javaClass.simpleName,
                            "type" to event.type,
                            "key" to event.key,
                        ),
                    )
                    false
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showRootNav && !immersiveHomeNav) {
                    Spacer(modifier = Modifier.height(SlugYardTvMetrics.RootNavBarHeight))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
            // Shared by Home's catalog rows/Continue Watching and Search results: posters that
            // carry a catalog addonId go to Details, resume tiles/search hits without one route
            // straight to Streams (see the branch comment below for why).
            val onHomePosterSelected: (com.sluggyard.tv.ui.app.home.HomePoster) -> Unit = { poster ->
                ExperimentalDiagnostics.event(
                    "home",
                    "poster_selected",
                    mapOf(
                        "contentType" to poster.contentType,
                        "season" to poster.season,
                        "episode" to poster.episode,
                        "hasAddon" to (poster.addonId != null),
                        "hasResume" to (poster.resumePositionMs > 0L),
                    ),
                )
                poster.addonId?.let { addonId ->
                    push(RootDestination.Details(
                        addonId = addonId,
                        type = poster.contentType ?: "movie",
                        id = poster.id,
                    ))
                } ?: run {
                    pushPlayOrWatchHub(
                        type = poster.contentType ?: "movie",
                        id = poster.id,
                        title = poster.title,
                        posterUrl = poster.imageUrl,
                        backdropUrl = poster.backdropUrl,
                        season = poster.season,
                        episode = poster.episode,
                        addonId = poster.addonId,
                        parentId = poster.parentId,
                        parentType = poster.parentType,
                        resumePositionMs = poster.resumePositionMs,
                    )
                }
            }
            val onContinueWatchingResume: (com.sluggyard.tv.ui.app.home.HomePoster) -> Unit = { poster ->
                ExperimentalDiagnostics.event(
                    "home",
                    "continue_watching_selected",
                    mapOf(
                        "contentType" to poster.contentType,
                        "season" to poster.season,
                        "episode" to poster.episode,
                        "resumePositionMs" to poster.resumePositionMs,
                    ),
                )
                val isEpisode = poster.season != null || poster.episode != null
                val parentId = poster.parentId
                    ?: inferSeriesParentId(poster.id, poster.season, poster.episode)
                val addonId = poster.addonId ?: resolveMetadataAddonId(registry)
                pushPlayOrWatchHub(
                    // Checkpoints created by older entry points can carry the episode id with a
                    // movie/episode content type. Episode playback always uses the series stream
                    // endpoint; sending that id through the movie endpoint is what makes Continue
                    // Watching diverge from the same episode opened from Search/Details.
                    type = if (isEpisode) "series" else poster.contentType ?: "movie",
                    id = poster.id,
                    title = poster.title,
                    posterUrl = poster.imageUrl,
                    backdropUrl = poster.backdropUrl,
                    season = poster.season,
                    episode = poster.episode,
                    addonId = addonId,
                    parentId = parentId,
                    parentType = if (isEpisode) "series" else poster.parentType,
                    resumePositionMs = poster.resumePositionMs.takeIf { it > 0L }
                        ?: resumeFor(poster.id, poster.season, poster.episode),
                    contentGenres = poster.contentGenres,
                    contentLanguage = poster.contentLanguage,
                )
            }
            val browseScreenState = remember(destination, browseCatalogState, baseHomeState, libraryWatch) {
                val browse = destination as? RootDestination.Browse ?: return@remember null
                if (browse.filter == BrowseFilter.WATCHLIST) {
                    baseHomeState.forBrowse(browse.filter, libraryWatch)
                } else {
                    browseCatalogState.toScreenState(emptyList()).forBrowse(browse.filter, libraryWatch)
                }
            }
            when (destination) {
                RootDestination.Home -> HomeScreen(
                    state = enrichedHomeState,
                    smoothFocusMovement = smoothFocusMovement,
                     contentFocusRequester = homeContentFocusRequester,
                     headerFocusRequester = homeHeaderFocusRequester,
                     requestInitialFocus = !homeInitialFocusRequested && !homeSplashVisible,
                     onInitialFocusRequested = { homeInitialFocusRequested = true },
                    expandOnFocus = landscapePostersOnFocus,
                    rowScrollStates = homeRowScrollStates,
                    heroIndexState = homeHeroIndexState,
                    lastHeroKeyState = homeLastHeroKeyState,
                    continueWatchingFocusNonce = continueWatchingFocusNonce,
                    suppressHeroFocusRestore = suppressHomeHeroFocusRestore,
                    onContinueWatchingFocusRestored = { suppressHomeHeroFocusRestore = false },
                    onPlay = { hero ->
                        // Keep Home under Details/Streams so Back never falls through to Profiles.
                        push(if (hero.contentType == "series") {
                            RootDestination.Details(
                                addonId = hero.addonId,
                                type = hero.contentType,
                                id = hero.id,
                            )
                        } else {
                            playOrWatchHubDestination(
                                type = hero.contentType,
                                id = hero.id,
                                title = hero.title,
                                posterUrl = hero.posterUrl,
                                backdropUrl = hero.backdropUrl,
                                addonId = hero.addonId,
                                parentId = hero.id,
                                parentType = hero.contentType,
                                resumePositionMs = resumeFor(hero.id),
                            )
                        })
                    },
                    onDetails = { hero ->
                        push(RootDestination.Details(
                            addonId = hero.addonId,
                            type = hero.contentType,
                            id = hero.id,
                        ))
                    },
                    onPosterSelected = onHomePosterSelected,
                    onContinueWatchingSelected = onContinueWatchingResume,
                    onContinueWatchingLongPress = { continueWatchingMenuPoster = it },
                    onRetry = { homeReloadNonce++ },
                    modifier = Modifier.fillMaxSize(),
                )
                RootDestination.Search -> com.sluggyard.tv.ui.app.search.SearchScreen(
                    search = { query -> graph.searchDataSource.search(query) },
                    onPosterSelected = onHomePosterSelected,
                    contentFocusRequester = searchContentFocusRequester,
                    headerFocusRequester = searchHeaderFocusRequester,
                    onBack = { popRoute() },
                    modifier = Modifier.fillMaxSize(),
                )
                RootDestination.CloudManager -> CloudManagerScreen(
                    contentFocusRequester = cloudManagerFocusRequester,
                    headerFocusRequester = cloudHeaderFocusRequester,
                    connection = debridConnection,
                    onConnect = { service, apiKey ->
                        graph.debridRuntime.connect(service, apiKey)
                        settingsFacade.setDebridEnabled(true)
                        graph.providerCredentialSyncBridge.recordLocalConnect(
                            profileId = profiles.activeProfileId,
                            service = service,
                            apiKey = apiKey,
                        )
                        graph.provisionCommunitySources(true)
                    },
                    onSelect = { service -> graph.debridRuntime.select(service) },
                    onDisconnect = { service ->
                        graph.debridRuntime.disconnect(service)
                        val stillConnected = graph.debridRuntime.configuredService() != null
                        if (!stillConnected) {
                            settingsFacade.setDebridEnabled(false)
                        }
                        graph.provisionCommunitySources(stillConnected)
                    },
                    onOpenSettings = { openRoot(RootDestination.Settings) },
                    loadTorboxCloudFiles = { graph.debridRuntime.listTorboxCloudFiles() },
                    onPlayCloudItem = { item ->
                        val source = graph.debridRuntime.resolveTorboxCloudPlayback(item)
                        onLaunchRetainedPlayer(
                            source,
                            item.name,
                            "movie",
                            "torbox-cloud-${item.id}",
                            "",
                            "",
                            null,
                            null,
                            null,
                            null,
                            null,
                            0L,
                            null,
                            null,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                is RootDestination.Browse -> HomeScreen(
                          state = browseScreenState ?: baseHomeState.forBrowse(destination.filter, libraryWatch),
                    smoothFocusMovement = smoothFocusMovement,
                    contentFocusRequester = homeContentFocusRequester,
                    headerFocusRequester = homeHeaderFocusRequester,
                    expandOnFocus = landscapePostersOnFocus && destination.filter != BrowseFilter.WATCHLIST,
                    rowScrollStates = browseRowScrollStates,
                    heroIndexState = browseHeroIndexState,
                    lastHeroKeyState = browseLastHeroKeyState,
                    onPlay = { hero ->
                        replaceCurrent(
                            if (hero.contentType == "series") {
                                RootDestination.Details(hero.addonId, hero.contentType, hero.id)
                            } else {
                                playOrWatchHubDestination(
                                    type = hero.contentType,
                                    id = hero.id,
                                    title = hero.title,
                                    posterUrl = hero.posterUrl,
                                    backdropUrl = hero.backdropUrl,
                                    addonId = hero.addonId,
                                    parentId = hero.id,
                                    parentType = hero.contentType,
                                    resumePositionMs = resumeFor(hero.id),
                                )
                            },
                        )
                    },
                    onDetails = { hero ->
                        push(RootDestination.Details(hero.addonId, hero.contentType, hero.id))
                    },
                    onPosterSelected = onHomePosterSelected,
                    onContinueWatchingLongPress = { continueWatchingMenuPoster = it },
                    onRetry = { homeReloadNonce++ },
                    modifier = Modifier.fillMaxSize(),
                )
                RootDestination.Profiles -> ProfilesScreen(
                    state = profiles,
                    onSelect = { profile ->
                        scope.launch {
                            graph.selectProfile(profile.id)
                            if (needsStartupProfilePick) {
                                onNeedsStartupProfilePickChange(false)
                                // Dismiss LaunchedEffect starts the timer when visible flips true.
                                onHomeSplashVisibleChange(true)
                                // Replace Who's watching entirely — never leave Profiles under Home.
                                onRouteStackChange(listOf(RootDestination.Home))
                                navigationRoot = RootDestination.Home
                            } else {
                                openRoot(profilesReturnDestination)
                            }
                        }
                    },
                    onCreate = { name -> scope.launch { graph.createProfile(name) } },
                    onRename = { profile, name -> scope.launch { graph.renameProfile(profile, name) } },
                    onRemove = { profile -> scope.launch { graph.removeProfile(profile) } },
                    onBack = {
                        if (!needsStartupProfilePick) popRoute()
                    },
                    contentFocusRequester = profilesContentFocusRequester,
                    modifier = Modifier.fillMaxSize(),
                )
                RootDestination.Settings -> SettingsScreen(
                    facade = settingsFacade,
                    onSignedOut = onSignedOut,
                    onOpenAuth = onOpenAuth,
                    homeSettings = homeSettings,
                    profileState = profiles,
                    onHideUnreleasedChanged = { enabled -> scope.launch { graph.homeSettings.setHideUnreleased(enabled) } },
                    onRememberProfileChanged = { enabled -> scope.launch { graph.setRememberLastProfile(enabled) } },
                    onCatalogOrderChanged = { order -> scope.launch { graph.homeSettings.setCatalogOrder(order) } },
                    debridConnection = debridConnection,
                    onConnect = { service, apiKey ->
                        graph.debridRuntime.connect(service, apiKey)
                        settingsFacade.setDebridEnabled(true)
                        graph.providerCredentialSyncBridge.recordLocalConnect(
                            profileId = profiles.activeProfileId,
                            service = service,
                            apiKey = apiKey,
                        )
                        graph.provisionCommunitySources(true)
                    },
                    onDisconnect = { service ->
                        graph.debridRuntime.disconnect(service)
                        val stillConnected = graph.debridRuntime.configuredService() != null
                        if (!stillConnected) {
                            settingsFacade.setDebridEnabled(false)
                        }
                        graph.provisionCommunitySources(stillConnected)
                    },
                    onDebridEnabledChanged = { _ ->
                        val stillConnected = debridConnection.activeService != null ||
                            debridConnection.configuredServices.isNotEmpty()
                        graph.provisionCommunitySources(stillConnected)
                    },
                    onSelect = { service ->
                        graph.debridRuntime.select(service)
                        graph.provisionCommunitySources(true)
                    },
                    addons = registry.addons,
                    onInstallAddon = { manifestUrl ->
                        graph.installAllowlistedAddon(manifestUrl)
                    },
                    onUninstallAddon = { manifestUrl ->
                        graph.uninstallAllowlistedAddon(manifestUrl)
                    },
                    onOpenProfiles = {
                        profilesReturnDestination = RootDestination.Settings
                        push(RootDestination.Profiles)
                    },
                    contentFocusRequester = settingsContentFocusRequester,
                    headerFocusRequester = settingsHeaderFocusRequester,
                    modifier = Modifier.fillMaxSize(),
                )
                is RootDestination.Details -> {
                    val state = detailsState
                    if (state != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                         DetailsScreen(
                             state = state,
                            onPlay = {
                                 (destination as? RootDestination.Details)?.let { details ->
                                     val target = state.playbackTarget(details.type, details.id)
                                     if (target == null) {
                                         detailsMessage = "No playable episode is available for this series."
                                         detailsState = null
                                     } else {
                                         // Push Streams/WatchHub on top of Details so Back lands on Details.
                                         pushPlayOrWatchHub(
                                             type = target.type,
                                             id = target.id,
                                             title = state.title,
                                             posterUrl = state.posterUrl,
                                             backdropUrl = state.backdropUrl,
                                             season = target.season,
                                             episode = target.episode,
                                              addonId = details.addonId,
                                              parentId = details.id,
                                              parentType = details.type,
                                              resumePositionMs = resumeFor(target.id, target.season, target.episode),
                                              contentGenres = state.genres.takeIf { it.isNotEmpty() }?.joinToString(","),
                                              contentLanguage = state.contentLanguage,
                                          )
                                     }
                                 }
                            },
                             onLibraryChanged = { inLibrary ->
                                 detailsState = state.copy(inLibrary = inLibrary)
                                  val details = destination as? RootDestination.Details
                                  scope.launch {
                                      graph.libraryWatch.setInLibrary(
                                          id = state.id,
                                          inLibrary = inLibrary,
                                          entry = if (inLibrary) {
                                              LibraryEntry(
                                                  id = state.id,
                                                  title = state.title,
                                                  imageUrl = state.posterUrl,
                                                  contentType = details?.type,
                                                  addonId = details?.addonId,
                                              )
                                          } else {
                                              null
                                          },
                                      )
                                  }
                             },
                            onSeasonSelected = { season ->
                                detailsState = state.copy(selectedSeason = season)
                            },
                            onMarkSeasonWatched = { season ->
                                scope.launch {
                                    val updated = DetailsWatchPolicy.markSeasonWatched(
                                        state.toWatchPolicyEpisodes(),
                                        season,
                                    )
                                      detailsState = state.withWatchPolicy(updated)
                                      updated.filter { it.watched }.forEach { episode ->
                                          graph.libraryWatch.setWatched(
                                              id = episode.id,
                                              watched = true,
                                              contentType = "series",
                                              title = state.title,
                                              season = episode.season,
                                              episode = episode.episode,
                                          )
                                      }
                                }
                            },
                             onEpisodeSelected = { seasonNumber, episode ->
                                 val details = destination as? RootDestination.Details
                                  // Keep Details under Streams/WatchHub so exiting returns here.
                                  pushPlayOrWatchHub(
                                     type = "series",
                                     id = episode.id,
                                     title = state.title,
                                     posterUrl = state.posterUrl,
                                     backdropUrl = state.backdropUrl,
                                     season = seasonNumber,
                                     episode = episode.number,
                                      addonId = details?.addonId,
                                      parentId = details?.id,
                                      parentType = details?.type,
                                      resumePositionMs = resumeFor(episode.id, seasonNumber, episode.number),
                                      contentGenres = state.genres.takeIf { it.isNotEmpty() }?.joinToString(","),
                                      contentLanguage = state.contentLanguage,
                                  )
                            },
                             onEpisodeWatchedChanged = { episode, watched ->
                                 val currentEpisodes = state.toWatchPolicyEpisodes()
                                 val updated = if (watched) {
                                     DetailsWatchPolicy.markEpisodeWatched(currentEpisodes, episode.id)
                                 } else {
                                     currentEpisodes.map { candidate ->
                                         if (candidate.id == episode.id) candidate.copy(watched = false) else candidate
                                     }
                                 }
                                 detailsState = state.withWatchPolicy(updated)
                                 scope.launch {
                                     updated.filter { next ->
                                         next.id == episode.id || (watched && next.watched)
                                      }.forEach { next ->
                                          graph.libraryWatch.setWatched(
                                              id = next.id,
                                              watched = next.watched,
                                              contentType = "series",
                                              title = state.title,
                                              season = next.season,
                                              episode = next.episode,
                                          )
                                      }
                                 }
                             },
                             contentFocusRequester = detailsContentFocusRequester,
                             related = detailsRelated,
                             relatedLoading = detailsRelatedLoading,
                             onRelatedSelected = { poster ->
                                 // TMDB/Trakt related posters carry no addonId. Details already
                                 // falls back to every enabled META addon, so navigate with an
                                 // empty origin instead of dead-ending on a toast.
                                 detailsActionNotice = null
                                 push(
                                     RootDestination.Details(
                                         addonId = poster.addonId
                                             ?: resolveMetadataAddonId(registry)
                                             ?: "",
                                         type = poster.contentType ?: "movie",
                                         id = poster.id,
                                     ),
                                 )
                             },
                             modifier = Modifier.fillMaxSize(),
                         )
                         detailsActionNotice?.let { notice ->
                             LaunchedEffect(notice) {
                                 delay(3_500)
                                 if (detailsActionNotice == notice) detailsActionNotice = null
                             }
                             Text(
                                 notice,
                                 modifier = Modifier
                                     .align(Alignment.BottomCenter)
                                     .padding(horizontal = 48.dp, vertical = 28.dp)
                                     .background(SlugYardPalette.SurfaceElevated, RoundedCornerShape(8.dp))
                                     .padding(horizontal = 18.dp, vertical = 12.dp),
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = SlugYardPalette.OnCanvas,
                             )
                         }
                        }
                     } else {
                         DetailsStatusPanel(
                             isLoading = detailsMessage == null,
                             message = detailsMessage,
                             onRetry = { detailsReloadNonce++ },
                             onBack = ::popRoute,
                             focusRequester = detailsContentFocusRequester,
                         )
                    }
                }
                is RootDestination.WatchHub -> {
                    val hub = destination
                    LaunchedEffect(hub.id, hub.type, detailsReloadNonce) {
                        watchHubLoading = true
                        watchHubMessage = null
                        watchHubPlatforms = emptyList()
                        runCatching {
                            if (!SlugYardCommunitySourcePolicy.isWatchHubInstalled(registry.addons)) {
                                settingsFacade.setCommunityAddonEnabled(true)
                            }
                            graph.provisionCommunitySources(hasActiveDebrid)
                            graph.watchHubDataSource.loadPlatforms(hub.type, hub.id)
                        }.onSuccess { platforms ->
                            watchHubPlatforms = platforms
                            if (platforms.isEmpty()) {
                                watchHubMessage = "No streaming platforms found for this title."
                            }
                        }.onFailure { error ->
                            watchHubMessage = error.message?.takeIf { it.isNotBlank() }
                                ?: "WatchHub could not load. Check your connection and try again."
                        }
                        watchHubLoading = false
                    }
                    WatchHubScreen(
                        title = hub.title,
                        posterUrl = hub.posterUrl,
                        platforms = watchHubPlatforms,
                        loading = watchHubLoading,
                        message = watchHubMessage,
                        onBack = ::popRoute,
                        onInstallCommunityAddons = {
                            scope.launch {
                                settingsFacade.setCommunityAddonEnabled(true)
                                graph.provisionCommunitySources(hasActiveDebrid)
                                watchHubLoading = true
                                watchHubPlatforms = runCatching {
                                    graph.watchHubDataSource.loadPlatforms(hub.type, hub.id)
                                }.getOrDefault(emptyList())
                                watchHubLoading = false
                            }
                        },
                        contentFocusRequester = watchHubContentFocusRequester,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is RootDestination.Streams -> {
                    val streams = destination as RootDestination.Streams
                    val exitStreams: () -> Unit = {
                        streamResolutionJob?.cancel()
                        streamResolutionJob = null
                        resolvingCandidateId = null
                        autoPickFinishedFor = streams.id
                        popRoute()
                    }
                    // Play is a single handoff. The raw source list remains available only for
                    // explicit episode/source selection, never as a surprise after Play.
                    if (streams.autoPick) {
                        BuildingPlayerScreen(
                            artUrl = playPreparingArtUrl(
                                streams.backdropUrl ?: detailsState?.backdropUrl,
                                streams.posterUrl ?: detailsState?.posterUrl,
                            ),
                            title = streams.title,
                            statusMessage = streamMessage
                                ?: if (
                                    resolvingCandidateId != null ||
                                    !streamDiscoveryComplete ||
                                    (!cacheWaitExpired && hasPendingCacheChecks(streamGroups))
                                ) {
                                    "Finding a playable stream..."
                                } else {
                                    null
                                },
                            availability = detailsState?.availability.orEmpty(),
                            onBack = exitStreams,
                            onChooseSource = { replaceCurrent(streams.copy(autoPick = false)) },
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            streamMessage?.let { message ->
                                Text(
                                    message,
                                    modifier = Modifier.padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SlugYardPalette.Danger,
                                )
                            }
                             StreamsScreen(
                                 title = streams.title,
                                 groups = displayStreamGroups,
                                  onStreamSelected = { candidate ->
                                      resolveStreamCandidate(candidate, streams)
                                  },
                                  onBack = exitStreams,
                                  contentFocusRequester = streamsContentFocusRequester,
                                 modifier = Modifier.weight(1f),
                             )
                        }
                    }
                }
            }
            if (destination == RootDestination.Home && homeSplashVisible) {
                // Logo mark only — dark canvas, no blue tile, no wordmark / status chrome.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SlugYardPalette.Canvas)
                        .zIndex(8f),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            id = com.sluggyard.tv.R.drawable.app_logo_mark_clear,
                        ),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.size(168.dp),
                    )
                }
            }
            continueWatchingMenuPoster?.let { poster ->
                ContinueWatchingOptionsDialog(
                    title = poster.title,
                    onViewDetails = {
                        val addonId = poster.addonId ?: resolveMetadataAddonId(registry)
                        if (addonId != null) {
                            val (detailsId, detailsType) = detailsTarget(
                                contentId = poster.id,
                                contentType = poster.contentType,
                                parentId = poster.parentId,
                                parentType = poster.parentType,
                                season = poster.season,
                                episode = poster.episode,
                            )
                            push(
                                RootDestination.Details(
                                    addonId = addonId,
                                    type = detailsType,
                                    id = detailsId,
                                ),
                            )
                        } else {
                            onHomePosterSelected(poster)
                        }
                        continueWatchingMenuPoster = null
                    },
                    onRemove = {
                        // Keep CW row focus after the dialog dismisses — removing a tile used to
                        // dispose the focused card, dump focus onto the header bridge, and
                        // teleport scroll/focus back to the hero.
                        suppressHomeHeroFocusRestore = true
                        continueWatchingFocusNonce += 1
                        scope.launch {
                            if (useTraktCw) {
                                val removeId = poster.parentId?.takeIf {
                                    poster.season != null || poster.episode != null
                                } ?: poster.id
                                watchProgressRepository.removeProgress(
                                    contentId = removeId,
                                    season = poster.season,
                                    episode = poster.episode,
                                )
                            } else {
                                graph.playbackProgress.remove(
                                    contentId = poster.id,
                                    season = poster.season,
                                    episode = poster.episode,
                                )
                            }
                        }
                        continueWatchingMenuPoster = null
                    },
                    onDismiss = { continueWatchingMenuPoster = null },
                )
            }
            }
            }
            if (showRootNav) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // Match Home catalog focus inset so leftover shelf art cannot show through
                        // a transparent fade under the icons.
                        .height(
                            if (immersiveHomeNav) {
                                SlugYardTvMetrics.RootNavBarHeight + 20.dp
                            } else {
                                SlugYardTvMetrics.RootNavBarHeight
                            },
                        )
                        .background(
                            Brush.verticalGradient(
                                colors = if (immersiveHomeNav) {
                                    listOf(
                                        SlugYardPalette.Canvas,
                                        SlugYardPalette.Canvas.copy(alpha = 0.98f),
                                        SlugYardPalette.Canvas.copy(alpha = 0.92f),
                                        SlugYardPalette.Canvas.copy(alpha = 0.0f),
                                    )
                                } else {
                                    listOf(
                                        SlugYardPalette.Canvas.copy(alpha = 0.96f),
                                        SlugYardPalette.Canvas.copy(alpha = 0.82f),
                                        SlugYardPalette.Canvas.copy(alpha = 0.0f),
                                    )
                                },
                            ),
                        )
                        .zIndex(4f),
                ) {
                    RootNavigation(
                        destination = navDestination,
                        onDestinationChanged = { next ->
                            if (next == RootDestination.Profiles) {
                                profilesReturnDestination = navigationRoot
                            }
                            openRoot(next)
                        },
                        profileInitial = profiles.profiles.find { it.id == profiles.activeProfileId }
                            ?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "S",
                        searchFocusRequester = searchHeaderFocusRequester,
                        homeFocusRequester = homeHeaderFocusRequester,
                        downFocusRequester = when (destination) {
                            RootDestination.Home -> homeContentFocusRequester
                            RootDestination.Profiles -> profilesContentFocusRequester
                            RootDestination.Settings -> settingsContentFocusRequester
                            RootDestination.CloudManager -> cloudManagerFocusRequester
                            RootDestination.Search -> searchContentFocusRequester
                            is RootDestination.Browse -> homeContentFocusRequester
                            is RootDestination.Details -> detailsContentFocusRequester
                            is RootDestination.Streams -> streamsContentFocusRequester
                            is RootDestination.WatchHub -> watchHubContentFocusRequester
                        },
                        settingsHeaderFocusRequester = settingsHeaderFocusRequester,
                        cloudHeaderFocusRequester = cloudHeaderFocusRequester,
                    )
                }
            }
        }
        }
    }
}

/**
 * Shown while the auto-picker is resolving a playable link. Shares prepare chrome with the
 * retained player so find → build → buffer reads as one poster-backed window.
 */
@Composable
private fun BuildingPlayerScreen(
    artUrl: String?,
    title: String,
    statusMessage: String?,
    availability: List<String> = emptyList(),
    onBack: () -> Unit,
    onChooseSource: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val backFocusRequester = remember { FocusRequester() }
    val chooseSourceFocusRequester = remember { FocusRequester() }
    val effectiveStatus = statusMessage
        ?: "Finding a playable stream..."
    val showChooser = effectiveStatus.isNotBlank() &&
        !effectiveStatus.contains("Finding a playable stream", ignoreCase = true) &&
        !effectiveStatus.contains("Building", ignoreCase = true) &&
        !effectiveStatus.contains("Buffering", ignoreCase = true) &&
        !effectiveStatus.contains("Preparing", ignoreCase = true)
    LaunchedEffect(showChooser) {
        runCatching {
            if (showChooser) chooseSourceFocusRequester.requestFocus()
            else backFocusRequester.requestFocus()
        }
    }
    PlayPreparingSurface(
        artUrl = artUrl,
        title = title,
        statusMessage = effectiveStatus,
        showChooser = showChooser,
        availability = availability,
        onChooseSource = onChooseSource,
        onBack = onBack,
        backFocusRequester = backFocusRequester,
        chooseSourceFocusRequester = chooseSourceFocusRequester,
    )
}

/** Prefer a full-bleed backdrop decode when the URL still points at a small poster size. */
// (URL upgrades live in PlayPreparingSurface / ImageUrls.)

private fun remapCheckingCacheStates(
    groups: List<com.sluggyard.tv.ui.app.streams.StreamGroup>,
): List<com.sluggyard.tv.ui.app.streams.StreamGroup> =
    groups.map { group ->
        val content = group.state as? com.sluggyard.tv.ui.app.streams.StreamGroupState.Content
            ?: return@map group
        group.copy(
            state = com.sluggyard.tv.ui.app.streams.StreamGroupState.Content(
                content.streams.map { candidate ->
                    if (candidate.cacheState == com.sluggyard.tv.core.streamresolution.StreamCacheState.CHECKING) {
                        candidate.copy(
                            cacheState = com.sluggyard.tv.core.streamresolution.StreamCacheState.UNKNOWN,
                        )
                    } else {
                        candidate
                    }
                },
            ),
        )
    }

@Composable
private fun DetailsStatusPanel(
    isLoading: Boolean,
    message: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            message ?: "Getting title details…",
            style = MaterialTheme.typography.bodyLarge,
            color = SlugYardPalette.OnCanvasMuted,
        )
        if (isLoading) {
            OutlinedButton(onClick = onBack, modifier = Modifier.focusRequester(focusRequester)) {
                Text("Back")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(focusRequester)) { Text("Retry") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

private fun DetailsState.toWatchPolicyEpisodes() = seasons.flatMap { season ->
    season.episodes.map { episode ->
        com.sluggyard.tv.core.watchstate.DetailsEpisode(
            id = episode.id,
            season = season.number,
            episode = episode.number,
            watched = episode.watched,
        )
    }
}

private fun DetailsState.withWatchPolicy(
    updated: List<com.sluggyard.tv.core.watchstate.DetailsEpisode>,
): DetailsState {
    val watchedById = updated.associateBy { it.id }
    return copy(
        seasons = seasons.map { season ->
            val episodes = season.episodes.map { episode ->
                episode.copy(watched = watchedById[episode.id]?.watched ?: episode.watched)
            }
            season.copy(
                episodes = episodes,
                fullyWatched = episodes.isNotEmpty() && episodes.all(DetailsEpisode::watched),
            )
        },
    )
}


@Composable
private fun RootNavigation(
    destination: RootDestination,
    onDestinationChanged: (RootDestination) -> Unit,
    profileInitial: String = "S",
    searchFocusRequester: FocusRequester? = null,
    homeFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    settingsHeaderFocusRequester: FocusRequester? = null,
    cloudHeaderFocusRequester: FocusRequester? = null,
) {
    // Only steal DirectionDown when focus actually lands. A coroutine handoff can't report
    // success back to onPreviewKeyEvent, so consuming optimistically trapped the user on the
    // header whenever the content FocusRequester wasn't attached yet (scrolled-away hero).
    fun forceDownToContent(): Boolean {
        val target = downFocusRequester ?: return false
        return runCatching { target.requestFocus() }.getOrDefault(false)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 30.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val avatarInteraction = remember { MutableInteractionSource() }
        val avatarFocused by avatarInteraction.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer(
                    scaleX = if (avatarFocused) 1.1f else 1f,
                    scaleY = if (avatarFocused) 1.1f else 1f,
                )
                .clip(RoundedCornerShape(50))
                .focusProperties { downFocusRequester?.let { down = it } }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && downFocusRequester != null) {
                        forceDownToContent()
                    } else {
                        false
                    }
                }
                .background(SlugYardPalette.Accent)
                .clickable(
                    interactionSource = avatarInteraction,
                    indication = null,
                    onClick = {
                        ExperimentalDiagnostics.event("navigation", "profile_button_clicked")
                        onDestinationChanged(RootDestination.Profiles)
                    },
                )
                .semantics { contentDescription = "Switch profile"; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = profileInitial,
                color = Color(0xFF181818),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f))
        RootIconItem(
            icon = Icons.Filled.Search,
            label = "Search",
            selected = destination == RootDestination.Search,
            downFocusRequester = downFocusRequester,
            focusRequester = searchFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RootDestination.Search) }
        RootNavigationItem(
            label = "Home",
            selected = destination == RootDestination.Home,
            downFocusRequester = downFocusRequester,
            upFocusRequester = homeFocusRequester,
            focusRequester = homeFocusRequester,
            onForceDown = ::forceDownToContent,
        ) {
            onDestinationChanged(RootDestination.Home)
        }
        RootNavigationItem(
            label = "Watchlist",
            selected = destination == RootDestination.Browse(BrowseFilter.WATCHLIST),
            downFocusRequester = downFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RootDestination.Browse(BrowseFilter.WATCHLIST)) }
        // Movies / TV Shows stay off the top header on purpose — browse those from Search.
        RootIconItem(
            icon = Icons.Filled.Cloud,
            label = "Cloud Manager",
            selected = destination == RootDestination.CloudManager,
            downFocusRequester = downFocusRequester,
            focusRequester = cloudHeaderFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RootDestination.CloudManager) }
        RootIconItem(
            icon = Icons.Filled.Settings,
            label = "Settings",
            selected = destination == RootDestination.Settings,
            downFocusRequester = downFocusRequester,
            focusRequester = settingsHeaderFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RootDestination.Settings) }
    }
}

@Composable
private fun RootIconItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    downFocusRequester: FocusRequester?,
    focusRequester: FocusRequester? = null,
    /** Returns true only when the handoff landed; a failed handoff must not eat the key. */
    onForceDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = if (focused) 1.12f else 1f
                scaleY = if (focused) 1.12f else 1f
            }
            .clip(RoundedCornerShape(50))
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties { downFocusRequester?.let { down = it } }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onForceDown != null) {
                    onForceDown()
                } else {
                    false
                }
            }
            .background(
                if (focused || selected) SlugYardPalette.SurfaceElevated else Color.Transparent,
                RoundedCornerShape(50),
            )
            .then(
                when {
                    focused -> Modifier.border(3.dp, SlugYardPalette.FocusRing, RoundedCornerShape(50))
                    selected -> Modifier.border(2.dp, SlugYardPalette.Accent, RoundedCornerShape(50))
                    else -> Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    ExperimentalDiagnostics.event("navigation", "root_button_clicked", mapOf("label" to label))
                    onClick()
                },
            )
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused || selected) SlugYardPalette.OnCanvas else SlugYardPalette.OnCanvasMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RootNavigationItem(
    label: String,
    selected: Boolean,
    downFocusRequester: FocusRequester?,
    upFocusRequester: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
    /** Returns true only when the handoff landed; a failed handoff must not eat the key. */
    onForceDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .graphicsLayer(
                scaleX = if (focused) 1.06f else 1f,
                scaleY = if (focused) 1.06f else 1f,
            )
            .clip(RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius))
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties {
                downFocusRequester?.let { down = it }
                upFocusRequester?.let { up = it }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onForceDown != null) {
                    onForceDown()
                } else {
                    false
                }
            }
            .background(if (focused || selected) SlugYardPalette.SurfaceElevated else Color.Transparent)
            .then(
                when {
                    focused -> Modifier.border(3.dp, SlugYardPalette.FocusRing, RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius))
                    selected -> Modifier.border(2.dp, SlugYardPalette.Accent, RoundedCornerShape(SlugYardTvMetrics.PillCornerRadius))
                    else -> Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    ExperimentalDiagnostics.event("navigation", "root_button_clicked", mapOf("label" to label))
                    onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                selected -> SlugYardPalette.OnCanvas
                focused -> SlugYardPalette.OnCanvas
                else -> SlugYardPalette.OnCanvasMuted
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun resolveMetadataAddonId(registry: AddonRegistryState): String? {
    fun looksLikeCinemeta(addon: com.sluggyard.tv.core.addonprotocol.ManagedAddon): Boolean {
        val haystacks = listOf(
            addon.manifest.id,
            addon.manifest.name,
            addon.manifestUrl,
            addon.configuredManifestUrl.orEmpty(),
        )
        return haystacks.any { it.contains("cinemeta", ignoreCase = true) }
    }
    return registry.enabledAddons.firstOrNull(::looksLikeCinemeta)?.manifest?.id
        ?: registry.enabledAddons.firstOrNull { addon ->
            com.sluggyard.tv.core.addonprotocol.AddonResource.META in addon.manifest.resources
        }?.manifest?.id
}

/** HotD-style episode ids are often `tt1234567:1:1` or `tt1234567:34` — parent is before the first `:`. */
private fun inferSeriesParentId(contentId: String, season: Int?, episode: Int?): String? {
    if (season == null && episode == null) return null
    val base = com.sluggyard.tv.data.repository.StreamMergeUtils
        .deriveInlineMetaId(contentId)
        .trim()
    return base.takeIf { it.isNotBlank() && it != contentId }
}

private const val MaxHomeRowPosters = 32
/** Cap how many debrid resolve attempts auto-pick will burn before surfacing Sources. */
private const val MaxAutoPickResolveAttempts = 3

private sealed class AutoPickOutcome {
    data object Wait : AutoPickOutcome()
    data object NotDigitallyReleased : AutoPickOutcome()
    data object LastSourceUnavailable : AutoPickOutcome()
    data object NoneAvailable : AutoPickOutcome()
    data class Play(val candidate: StreamCandidate) : AutoPickOutcome()
}

/** Anime Play should wait on AIOStreams — Comet alone often lacks Eng ASS metadata. */
private fun isAnimeAutoPlayWait(streams: RootDestination.Streams): Boolean {
    if (streams.type.equals("anime", ignoreCase = true)) return true
    val genres = streams.contentGenres
        ?.split(',', '|', '/')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    if (genres.any { it.contains("anime") || it == "animation" }) return true
    return StreamScoringEngine.contentKind(
        StreamScoringEngine.Context(
            title = streams.title,
            contentType = streams.type,
            genres = genres,
            language = streams.contentLanguage,
        ),
    ) == StreamScoringEngine.ContentKind.ANIME
}

private fun hasEligibleAnimeSoftsub(
    streams: RootDestination.Streams,
    groups: List<com.sluggyard.tv.ui.app.streams.StreamGroup>,
    preferredSubtitleLanguage: String?,
): Boolean {
    val genreList = streams.contentGenres
        ?.split(',', '|', '/')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return hasEligibleSoftsubAutoPlay(
        groups = groups,
        context = StreamScoringEngine.Context(
            title = streams.title,
            contentType = streams.type,
            genres = genreList,
            language = streams.contentLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
        ),
    )
}

private fun StreamGroup.isAioStreamsGroup(): Boolean {
    val haystack = "$addonId $addonName".lowercase()
    return "aiostreams" in haystack
}

/** Home cold-start splash: let TMDB/addon fanout finish before focus hits a partial shelf list. */
private const val HomeSplashMinDurationMs = 1_800L
private const val HomeSplashMaxDurationMs = 12_000L

private fun HomeCatalogState.toScreenState(
    playbackCheckpoints: List<com.sluggyard.tv.ui.app.data.PlaybackCheckpoint>,
): HomeState {
    val catalogRows = rows.mapNotNull { row ->
        val content = row.loadState as? CatalogLoadState.Content ?: return@mapNotNull null
        HomeRow(
            id = "${row.key.addonId}:${row.key.catalogId}",
            title = row.title,
            // Cap eagerly so Home LazyRows stay bounded even when an addon dumps a huge catalog.
            posters = content.posters.take(MaxHomeRowPosters),
        )
    }
    val catalogPostersById = catalogRows.flatMap { it.posters }.associateBy(HomePoster::id)
    val hasContent = catalogRows.isNotEmpty()
    val isLoading = !hasContent && (rows.isEmpty() || rows.any { it.loadState is CatalogLoadState.Loading })
    val errorCount = rows.count { it.loadState is CatalogLoadState.Error }
    val statusMessage = if (errorCount > 0) {
        if (hasContent) "Some catalogs could not load. Check your connection and try again."
        else "Home catalogs could not load. Check your connection and try again."
    } else {
        null
    }
    fun heroFor(poster: com.sluggyard.tv.ui.app.home.HomePoster): com.sluggyard.tv.ui.app.home.Hero? {
        val type = poster.contentType ?: "movie"
        val addonId = poster.addonId ?: return null
        return com.sluggyard.tv.ui.app.home.Hero(
            id = poster.id,
            title = poster.title,
            // Catalog poster art is portrait and low-resolution. Keep it separate from the
            // landscape metadata background, which is filled in by hero enrichment.
            backdropUrl = null,
            posterUrl = poster.imageUrl,
            summary = "",
            contextTag = type.replaceFirstChar(Char::titlecase),
            addonId = addonId,
            contentType = type,
        )
    }
    val heroCandidates = catalogRows
        .flatMap { it.posters }
        .distinctBy { it.id }
        .take(8)
        .mapNotNull(::heroFor)
    return HomeState(
        hero = heroCandidates.firstOrNull(),
        heroCandidates = heroCandidates,
        rows = buildList {
        if (playbackCheckpoints.isNotEmpty()) {
            add(
                HomeRow(
                    id = "continue_watching",
                    title = "Continue Watching",
                    posters = groupPlaybackCheckpoints(playbackCheckpoints).map { checkpoint ->
                        val checkpointParentId = checkpoint.parentId
                            ?: inferSeriesParentId(checkpoint.contentId, checkpoint.season, checkpoint.episode)
                        val catalogMatch = checkpointParentId?.let(catalogPostersById::get)
                            ?: catalogPostersById[checkpoint.contentId]
                        com.sluggyard.tv.ui.app.home.HomePoster(
                            id = checkpoint.contentId,
                            title = catalogMatch?.title ?: checkpoint.title,
                            imageUrl = checkpoint.posterUrl ?: catalogMatch?.imageUrl,
                            // Continue Watching stays portrait — never landscape-expand on focus.
                            backdropUrl = null,
                            progressFraction = checkpoint.progressFraction?.toFloat(),
                            contentType = checkpoint.contentType,
                            addonId = checkpoint.addonId ?: catalogMatch?.addonId,
                            season = checkpoint.season,
                            episode = checkpoint.episode,
                            parentId = checkpointParentId ?: catalogMatch?.parentId,
                            parentType = checkpoint.parentType ?: catalogMatch?.parentType,
                            resumePositionMs = checkpoint.positionMs,
                            contentGenres = checkpoint.contentGenres,
                            contentLanguage = checkpoint.contentLanguage,
                        )
                    },
                ),
            )
        }
        addAll(catalogRows)
        },
        isLoading = isLoading,
        statusMessage = statusMessage,
    )
}

private fun HomeState.forBrowse(
    filter: BrowseFilter,
    libraryWatch: LibraryWatchState,
): HomeState {
    val posters = if (filter == BrowseFilter.WATCHLIST && libraryWatch.libraryEntries.isNotEmpty()) {
        libraryWatch.libraryEntries.map { entry ->
            com.sluggyard.tv.ui.app.home.HomePoster(
                id = entry.id,
                title = entry.title,
                imageUrl = entry.imageUrl,
                contentType = entry.contentType,
                addonId = entry.addonId,
            )
        }
    } else {
        rows.filterNot { it.id == "continue_watching" }.flatMap { it.posters }
    }
        .filter { poster ->
            val contentType = poster.contentType.orEmpty().trim().lowercase()
            when (filter) {
                BrowseFilter.MOVIES -> contentType in setOf("movie", "movies", "film", "films")
                BrowseFilter.TV_SHOWS -> contentType in setOf("series", "show", "shows", "tv", "tvshow", "tvshows")
                BrowseFilter.WATCHLIST -> poster.id in libraryWatch.libraryIds
            }
        }
    val rows = if (posters.isEmpty()) {
        emptyList()
    } else {
        listOf(HomeRow(filter.name, filter.title(), posters.distinctBy { it.id }))
    }
    return copy(
        hero = null,
        heroCandidates = emptyList(),
        rows = rows,
        statusMessage = if (rows.isEmpty()) {
            when (filter) {
                BrowseFilter.WATCHLIST -> "Your watchlist is empty."
                else -> "No ${filter.title().lowercase()} are available yet."
            }
        } else null,
        isLoading = false,
    )
}

private fun BrowseFilter.title(): String = when (this) {
    BrowseFilter.MOVIES -> "Movies"
    BrowseFilter.TV_SHOWS -> "TV Shows"
    BrowseFilter.WATCHLIST -> "Watchlist"
}
