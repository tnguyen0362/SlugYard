package com.sluggyard.tv.ui.rewrite

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import com.sluggyard.tv.ui.rewrite.requestFocusReliably
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
import com.sluggyard.tv.ui.rewrite.streams.toManualSelection
import com.sluggyard.tv.core.streamresolution.ManualResolutionResult
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.watchstate.DetailsWatchPolicy
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.design.SlugYardTvTheme
import com.sluggyard.tv.ui.rewrite.data.RewriteCommunityAddonProvisioner
import com.sluggyard.tv.ui.rewrite.data.CommunityProvisioningReport
import com.sluggyard.tv.ui.rewrite.data.RewriteAddonRegistryStore
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchStore
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchRepository
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchState
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackProgressStore
import com.sluggyard.tv.ui.rewrite.data.groupRewritePlaybackCheckpoints
import com.sluggyard.tv.core.sync.RewriteProgressSyncBridge
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileStore
import com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettingsStore
import com.sluggyard.tv.ui.rewrite.data.RewriteGuestSessionStore
import com.sluggyard.tv.ui.rewrite.data.RewriteDataIntegrityStore
import com.sluggyard.tv.ui.rewrite.data.PriorProfileSnapshot
import com.sluggyard.tv.ui.rewrite.data.RewriteProfile
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileCodec
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileState
import com.sluggyard.tv.ui.rewrite.data.RewriteCloudSyncNotices
import com.sluggyard.tv.ui.rewrite.data.remapNonIntegerProfileIds
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryEntry
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsDataSource
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsEpisode
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsLoadResult
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsRelatedPoster
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsScreen
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsState
import com.sluggyard.tv.ui.rewrite.details.RewriteRelatedDataSource
import com.sluggyard.tv.ui.rewrite.details.playbackTarget
import com.sluggyard.tv.ui.rewrite.home.RewriteCatalogLoadState
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeCatalogState
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeDataSource
import com.sluggyard.tv.ui.rewrite.home.RewriteHeroEnrichment
import com.sluggyard.tv.ui.rewrite.home.RewriteHeroEnrichmentDataSource
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeRow
import com.sluggyard.tv.ui.rewrite.home.RewriteContinueWatchingOptionsDialog
import com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeScreen
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeState
import com.sluggyard.tv.ui.rewrite.home.RewriteTmdbHomeDataSource
import com.sluggyard.tv.ui.rewrite.home.withHeroEnrichments
import com.sluggyard.tv.ui.rewrite.home.RewriteCatalogRequest
import com.sluggyard.tv.ui.rewrite.settings.effectiveProfileId
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamCandidate
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsDataSource
import com.sluggyard.tv.ui.rewrite.streams.RewriteSubtitleDataSource
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsScreen
import com.sluggyard.tv.ui.rewrite.streams.selectRewriteAutoPlayCandidate
import com.sluggyard.tv.ui.rewrite.streams.hasRewritePendingCacheChecks
import com.sluggyard.tv.ui.rewrite.profiles.RewriteProfilesScreen
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsScreen
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsFacade
import com.sluggyard.tv.ui.rewrite.player.RewriteRetainedPlayerHost
import com.sluggyard.tv.ui.rewrite.player.RewritePlayPreparingSurface
import com.sluggyard.tv.ui.rewrite.player.rewritePlayPreparingArtUrl
import com.sluggyard.tv.ui.rewrite.auth.RewriteAuthGate
import com.sluggyard.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridRuntime
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridConnection
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridCredentialStore
import com.sluggyard.tv.ui.rewrite.debrid.RewriteCloudManagerScreen
import com.sluggyard.tv.core.addonprotocol.SlugYardCommunitySourcePolicy
import com.sluggyard.tv.ui.rewrite.watchhub.RewriteWatchHubDataSource
import com.sluggyard.tv.ui.rewrite.watchhub.RewriteWatchHubPlatform
import com.sluggyard.tv.ui.rewrite.watchhub.RewriteWatchHubScreen
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
class RewriteAppGraph(
    context: Context,
    private val profileManager: ProfileManager,
    private val rewriteDataStore: DataStore<Preferences>,
    progressSyncBridge: RewriteProgressSyncBridge,
    libraryWatchRepository: RewriteLibraryWatchRepository,
    relatedDataSource: RewriteRelatedDataSource? = null,
    tmdbHomeDataSource: RewriteTmdbHomeDataSource? = null,
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
    val registry = RewriteAddonRegistryStore(rewriteDataStore)
    val profiles = RewriteProfileStore(rewriteDataStore)
    val libraryWatch = libraryWatchRepository
    val playbackProgress = RewritePlaybackProgressStore(rewriteDataStore, profiles, progressSyncBridge)
    private val debridCredentials = RewriteDebridCredentialStore(rewriteDataStore, profiles)
    val debridRuntime = RewriteDebridRuntime(debridCredentials)
    val homeSettings = RewriteHomeSettingsStore(rewriteDataStore, profiles)
    val dataIntegrity = RewriteDataIntegrityStore(rewriteDataStore)
    val guestSession = RewriteGuestSessionStore(rewriteDataStore)
    private val gateway = StremioAddonTransport(RewriteNetworkClient.create())
    private val communityProvisioner = RewriteCommunityAddonProvisioner(gateway, registry)
    private val _communityProvisioningReport = MutableStateFlow<CommunityProvisioningReport?>(null)
    val communityProvisioningReport: StateFlow<CommunityProvisioningReport?> = _communityProvisioningReport
    val homeDataSource = RewriteHomeDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
        settingsSnapshot = { homeSettings.settings.firstValue() },
        tmdbCatalogRequests = {
            val metaAddonId = resolveMetadataAddonId(registry.state.firstValue())
            tmdbHomeDataSource?.catalogRequests().orEmpty().map { request ->
                RewriteCatalogRequest(
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
    val heroEnrichmentDataSource = RewriteHeroEnrichmentDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val detailsDataSource = RewriteDetailsDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
        watchStateSnapshot = { libraryWatch.state.firstValue() },
    )
    val streamsDataSource = RewriteStreamsDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
        proactiveCacheCheckers = debridRuntime.proactiveCacheCheckers,
    )
    val watchHubDataSource = RewriteWatchHubDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val subtitleDataSource = RewriteSubtitleDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val searchDataSource = com.sluggyard.tv.ui.rewrite.search.RewriteSearchDataSource(
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
        _communityProvisioningReport.value = communityProvisioner.provision(
            configured = configured,
            debridConfigured = configured != null,
        )
    }

    suspend fun uninstallCommunitySources() {
        _communityProvisioningReport.value = communityProvisioner.uninstall()
    }

    suspend fun synchronizeProfiles() {
        // Remap legacy "default"/UUID Rewrite profile ids onto integer ids required by
        // Supabase profile_id columns, and copy scoped DataStore blobs so Home prefs,
        // progress, library, and credentials stay visible after the id change.
        rewriteDataStore.edit { preferences ->
            val migrationMarker = stringPreferencesKey("rewrite_profile_migration_v3")
            if (preferences[migrationMarker] == "complete") return@edit
            val profileKey = stringPreferencesKey("rewrite_profiles_v1")
            val current = preferences[profileKey]?.let(RewriteProfileCodec::decode) ?: RewriteProfileState()
            val (remapped, remaps) = remapNonIntegerProfileIds(current)
            if (remaps.isNotEmpty()) {
                preferences[profileKey] = RewriteProfileCodec.encode(remapped)
                val prefixes = listOf(
                    "rewrite_home_settings_v2_",
                    "rewrite_library_watch_v2_",
                    "rewrite_playback_progress_v2_",
                    "rewrite_debrid_active_v1_",
                    "rewrite_debrid_v2_",
                )
                remaps.forEach { (fromId, toId) ->
                    prefixes.forEach { prefix ->
                        val from = stringPreferencesKey("$prefix$fromId")
                        val to = stringPreferencesKey("$prefix$toId")
                        val value = preferences[from]
                        if (value != null && preferences[to] == null) preferences[to] = value
                    }
                }
            } else {
                // Preserve the earlier default→1 blob copy for installs that already use "1".
                listOf(
                    "rewrite_home_settings_v2_",
                    "rewrite_library_watch_v2_",
                    "rewrite_playback_progress_v2_",
                    "rewrite_debrid_active_v1_",
                    "rewrite_debrid_v2_",
                ).forEach { prefix ->
                    val from = stringPreferencesKey("${prefix}default")
                    val to = stringPreferencesKey("${prefix}1")
                    val value = preferences[from]
                    if (value != null && preferences[to] == null) preferences[to] = value
                }
            }
            preferences[migrationMarker] = "complete"
        }
        profiles.migrateIfDefault(
            profiles = profileManager.profiles.value.map { PriorProfileSnapshot(it.id.toString(), it.name) },
            activeProfileId = profileManager.activeProfileId.value.toString(),
            rememberLastProfile = profileManager.rememberLastProfileEnabled.value,
        )
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

    suspend fun renameProfile(profile: RewriteProfile, name: String) {
        profile.id.toIntOrNull()?.let { legacyId ->
            profileManager.profiles.value.firstOrNull { it.id == legacyId }?.let { existing ->
                profileManager.updateProfile(existing.copy(name = name.trim().take(32).ifBlank { "Viewer" }))
            }
        }
        profiles.rename(profile.id, name)
    }

    suspend fun removeProfile(profile: RewriteProfile) {
        val removed = profile.id.toIntOrNull()?.let { legacyId ->
            profileManager.deleteProfile(legacyId)
        } ?: true
        if (removed) profiles.remove(profile.id)
    }

}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

private sealed interface RewriteRootDestination {
    data object Home : RewriteRootDestination
    data object Profiles : RewriteRootDestination
    data object Settings : RewriteRootDestination
    data object CloudManager : RewriteRootDestination
    data object Search : RewriteRootDestination
    data class Browse(val filter: RewriteBrowseFilter) : RewriteRootDestination
    data class Details(val addonId: String, val type: String, val id: String) : RewriteRootDestination
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
    ) : RewriteRootDestination
    /** Stremio-like "where to watch" surface when no debrid key is configured. */
    data class WatchHub(
        val type: String,
        val id: String,
        val title: String,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
    ) : RewriteRootDestination
}

private enum class RewriteBrowseFilter {
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
fun RewriteAppShell(
    context: Context,
    graph: RewriteAppGraph,
    auth: SupabaseAuthGateway,
    sessions: SupabaseSessionStore,
    settingsFacade: RewriteSettingsFacade,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    var accessGranted by remember { mutableStateOf(false) }
    var forceAuthChoice by remember { mutableStateOf(false) }
    // Must live above the player route: navigating to rewrite_player disposes rewrite_root,
    // which would otherwise reset in-shell navigation to Home ("No home content…").
    var routeStack by remember {
        mutableStateOf(listOf<RewriteRootDestination>(RewriteRootDestination.Home))
    }
    NavHost(
        navController = navController,
        startDestination = "rewrite_root",
        modifier = modifier,
    ) {
        composable("rewrite_root") {
            if (accessGranted) {
                RewriteAppContent(
                    graph = graph,
                    settingsFacade = settingsFacade,
                    routeStack = routeStack,
                    onRouteStackChange = { routeStack = it },
                    onSignedOut = { accessGranted = false; forceAuthChoice = false },
                    onOpenAuth = { forceAuthChoice = true; accessGranted = false },
                    onLaunchRetainedPlayer = { source, title, type, id, posterUrl, backdropUrl, season, episode, addonId, parentId, parentType, resumePositionMs, contentGenres, contentLanguage ->
                        runCatching {
                            navController.navigate(
                                RewritePlayerRoute.create(
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
                            android.util.Log.e("RewriteAppShell", "Failed to open player", failure)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                    RewriteAuthGate(
                    context = context,
                    auth = auth,
                    sessions = sessions,
                        guestSession = graph.guestSession,
                        forceChoice = forceAuthChoice,
                        onAuthenticated = { accessGranted = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composable(
            route = RewritePlayerRoute.pattern,
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
                RewritePlaybackExpiredScreen(onBack = { navController.popBackStack() })
            } else {
                RewriteRetainedPlayerHost(
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
private object RewritePlayerRoute {
    const val pattern = "rewrite_player/{playbackKey}/{title}?contentId={contentId}&contentType={contentType}&contentName={contentName}&videoId={videoId}&posterUrl={posterUrl}&backdrop={backdrop}&season={season}&episode={episode}&addonId={addonId}&parentId={parentId}&parentType={parentType}&startPositionMs={startPositionMs}&contentGenres={contentGenres}&contentLanguage={contentLanguage}"

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
        "rewrite_player/${Uri.encode(playbackKey)}/${Uri.encode(title)}?contentId=${Uri.encode(contentId)}&contentType=${Uri.encode(contentType)}&contentName=${Uri.encode(title)}&videoId=${Uri.encode(contentId)}&posterUrl=${Uri.encode(posterUrl)}&backdrop=${Uri.encode(backdropUrl)}&season=${season?.toString().orEmpty()}&episode=${episode?.toString().orEmpty()}&addonId=${Uri.encode(addonId.orEmpty())}&parentId=${Uri.encode(parentId.orEmpty())}&parentType=${Uri.encode(parentType.orEmpty())}&startPositionMs=$startPositionMs&contentGenres=${Uri.encode(contentGenres.orEmpty())}&contentLanguage=${Uri.encode(contentLanguage.orEmpty())}"
}

@Composable
private fun RewritePlaybackExpiredScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedButton(onClick = onBack) { Text("Playback session expired") }
    }
}

@Composable
private fun RewriteAppContent(
    graph: RewriteAppGraph,
    settingsFacade: RewriteSettingsFacade,
    routeStack: List<RewriteRootDestination>,
    onRouteStackChange: (List<RewriteRootDestination>) -> Unit,
    onSignedOut: () -> Unit,
    onOpenAuth: () -> Unit,
    onLaunchRetainedPlayer: (ResolvedPlaybackSource, String, String, String, String, String, Int?, Int?, String?, String?, String?, Long, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val registry by graph.registry.state.collectAsState(initial = com.sluggyard.tv.core.addonprotocol.AddonRegistryState())
    val playbackCheckpoints by graph.playbackProgress.checkpoints.collectAsState(initial = emptyList())
    val libraryWatch by graph.libraryWatch.state.collectAsState(initial = RewriteLibraryWatchState())
    val profiles by graph.profiles.state.collectAsState(initial = com.sluggyard.tv.ui.rewrite.data.RewriteProfileState())
    val homeSettings by graph.homeSettings.settings.collectAsState(initial = com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettings())
    val dataCorruptionNotices by graph.dataIntegrity.notices.collectAsState(initial = emptyList())
    val debridSettings by settingsFacade.debridSettings.collectAsState(initial = DebridSettings())
    val tmdbSettings by settingsFacade.tmdbSettings.collectAsState(initial = com.sluggyard.tv.domain.model.TmdbSettings())
    val smoothFocusMovement by settingsFacade.layoutPreferences.smoothBringIntoViewEnabled.collectAsState(initial = true)
    val fastHorizontalNavigation by settingsFacade.layoutPreferences.fastHorizontalNavigationEnabled.collectAsState(initial = false)
    val debridConnection by graph.debridRuntime.connection.collectAsState(initial = RewriteDebridConnection())
    val destination = routeStack.last()
    var catalogState by remember { mutableStateOf(RewriteHomeCatalogState(emptyList())) }
    var browseCatalogState by remember { mutableStateOf(RewriteHomeCatalogState(emptyList())) }
    // Held outside the `when(destination)` branch below so Home's (and Browse's) per-row
    // horizontal scroll positions and hero rotation index survive navigating to Details/Streams
    // and back -- RewriteHomeScreen itself is fully disposed/recomposed on that round trip since
    // only one destination branch renders at a time.
    val homeRowScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val homeHeroIndexState = remember { mutableStateOf(0) }
    val homeLastHeroKeyState = remember { mutableStateOf<String?>(null) }
    val browseRowScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val browseHeroIndexState = remember { mutableStateOf(0) }
    val browseLastHeroKeyState = remember { mutableStateOf<String?>(null) }
    var heroEnrichments by remember { mutableStateOf<Map<String, RewriteHeroEnrichment>>(emptyMap()) }
    val communityProvisioningReport by graph.communityProvisioningReport.collectAsState()
    var detailsState by remember { mutableStateOf<RewriteDetailsState?>(null) }
    var detailsMessage by remember { mutableStateOf<String?>(null) }
    var detailsReloadNonce by remember { mutableStateOf(0) }
    var detailsRelated by remember { mutableStateOf<List<RewriteDetailsRelatedPoster>>(emptyList()) }
    var detailsRelatedLoading by remember { mutableStateOf(false) }
    var detailsActionNotice by remember { mutableStateOf<String?>(null) }
    var streamGroups by remember { mutableStateOf<List<RewriteStreamGroup>>(emptyList()) }
    var streamDiscoveryComplete by remember { mutableStateOf(false) }
    var streamMessage by remember { mutableStateOf<String?>(null) }
    var autoPickFinishedFor by remember { mutableStateOf<String?>(null) }
    var autoPickRejectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var profileBridgeReady by remember { mutableStateOf(false) }
    var homeReloadNonce by remember { mutableStateOf(0) }
    var streamResolutionJob by remember { mutableStateOf<Job?>(null) }
    var resolvingCandidateId by remember { mutableStateOf<String?>(null) }
    var continueWatchingMenuPoster by remember { mutableStateOf<RewriteHomePoster?>(null) }
    var navigationRoot by remember { mutableStateOf<RewriteRootDestination>(RewriteRootDestination.Home) }
    // Cold-start splash: brief cover until the first catalog paint, not an extra forced wait.
    var homeSplashVisible by remember { mutableStateOf(true) }
    var homeSplashStartedAt by remember { mutableStateOf(0L) }
    var watchHubPlatforms by remember { mutableStateOf<List<RewriteWatchHubPlatform>>(emptyList()) }
    var watchHubLoading by remember { mutableStateOf(false) }
    var watchHubMessage by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hasActiveDebrid = debridSettings.enabled && debridConnection.activeService != null

    fun resumeFor(contentId: String, season: Int? = null, episode: Int? = null): Long =
        playbackCheckpoints.firstOrNull {
            it.contentId == contentId && it.season == season && it.episode == episode
        }?.positionMs ?: 0L

    fun openRoot(next: RewriteRootDestination) {
        onRouteStackChange(listOf(next))
        navigationRoot = when (next) {
            is RewriteRootDestination.Details,
            is RewriteRootDestination.Streams,
            is RewriteRootDestination.WatchHub,
            -> RewriteRootDestination.Home
            else -> next
        }
    }

    fun push(next: RewriteRootDestination) {
        onRouteStackChange(routeStack + next)
    }

    fun replaceCurrent(next: RewriteRootDestination) {
        onRouteStackChange(routeStack.dropLast(1) + next)
    }

    fun popRoute() {
        if (routeStack.size > 1) onRouteStackChange(routeStack.dropLast(1))
        else openRoot(RewriteRootDestination.Home)
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
    ): RewriteRootDestination =
        if (SlugYardCommunitySourcePolicy.shouldRoutePlayToWatchHub(hasActiveDebrid)) {
            RewriteRootDestination.WatchHub(
                type = type,
                id = id,
                title = title,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
            )
        } else {
            RewriteRootDestination.Streams(
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
    fun stackForPlayerExit(streams: RewriteRootDestination.Streams): List<RewriteRootDestination> {
        var stack = routeStack.dropLastWhile { it is RewriteRootDestination.Streams }
        val detailsId = streams.parentId
            ?: inferSeriesParentId(streams.id, streams.season, streams.episode)
            ?: streams.id.takeIf { streams.season == null && streams.episode == null }
        val detailsType = streams.parentType
            ?: streams.type.takeIf { streams.season != null || streams.episode != null }?.let { "series" }
            ?: streams.type.takeIf { streams.season == null && streams.episode == null }
            ?: "movie"
        val addonId = streams.addonId ?: resolveMetadataAddonId(registry)
        val existing = stack.indexOfLast { dest ->
            dest is RewriteRootDestination.Details && dest.id == detailsId
        }
        if (existing >= 0) {
            // Keep Details, drop anything after it (stale routes), then ensure it's on top.
            stack = stack.take(existing + 1)
        } else if (detailsId != null && addonId != null) {
            stack = stack + RewriteRootDestination.Details(
                addonId = addonId,
                type = detailsType,
                id = detailsId,
            )
        }
        return stack.ifEmpty { listOf(RewriteRootDestination.Home) }
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
    var profilesReturnDestination by remember { mutableStateOf<RewriteRootDestination>(RewriteRootDestination.Home) }

    LaunchedEffect(graph) {
        graph.synchronizeProfiles()
        profileBridgeReady = true
    }

    // Apply the startup preference once after legacy profiles are bridged. A later profile choice
    // is an in-session selection and must not be reset when the setting snapshot recomposes.
    LaunchedEffect(graph, profileBridgeReady) {
        if (!profileBridgeReady) return@LaunchedEffect
        val profileId = effectiveProfileId(
            activeProfileId = profiles.activeProfileId,
            rememberLastProfile = profiles.rememberLastProfile,
            profileIds = profiles.profiles.map { it.id },
        )
        if (profileId != profiles.activeProfileId) graph.selectProfile(profileId)
    }

    LaunchedEffect(graph, profiles.activeProfileId, debridSettings.enabled, debridSettings.communityAddonEnabled, debridConnection.activeService, registry.addons.size) {
        val hasActiveDebrid = debridSettings.enabled && debridConnection.activeService != null
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

    BackHandler(enabled = destination !is RewriteRootDestination.Home) {
        streamResolutionJob?.cancel()
        streamResolutionJob = null
        resolvingCandidateId = null
        (destination as? RewriteRootDestination.Streams)?.let { streams ->
            autoPickFinishedFor = streams.id
        }
        popRoute()
    }

    LaunchedEffect(destination, detailsReloadNonce) {
        if (destination !is RewriteRootDestination.Streams) {
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
        com.sluggyard.tv.ui.rewrite.home.loadRewriteHomeCatalogs(
            requests,
            savedOrder = homeSettings.savedCatalogOrder(),
        ).collect { state ->
            catalogState = state
        }
    }
    LaunchedEffect(destination, homeCatalogReloadKey, homeReloadNonce) {
        val browse = destination as? RewriteRootDestination.Browse ?: return@LaunchedEffect
        if (browse.filter == RewriteBrowseFilter.WATCHLIST) return@LaunchedEffect
        val requests = graph.homeDataSource.catalogRequests()
        com.sluggyard.tv.ui.rewrite.home.loadRewriteHomeCatalogs(
            requests,
            savedOrder = homeSettings.savedCatalogOrder(),
        ).collect { state ->
            browseCatalogState = state
        }
    }
    val rawHomeState = remember(catalogState, playbackCheckpoints) {
        catalogState.toScreenState(playbackCheckpoints)
    }
    // Catalog rows can transiently flicker load state as later refinements arrive, which would
    // otherwise flip the computed hero to null for a frame and force-recreate the hero panel
    // (losing its focus/scroll handoff). Stick with the last real hero across those blips.
    var stickyHero by remember { mutableStateOf<com.sluggyard.tv.ui.rewrite.home.RewriteHero?>(null) }
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
                "Community sources are unavailable. Check Debrid settings or retry."
            } else {
                "${report.unavailableCount} community source${if (report.unavailableCount == 1) "" else "s"} unavailable."
            }
        }
    val integrityStatusMessage = dataCorruptionNotices.takeIf { it.isNotEmpty() }?.let {
        "Some local settings could not be read. They were kept for recovery; review Settings or retry."
    }
    val cloudSyncNotice by RewriteCloudSyncNotices.latestNotice.collectAsState(initial = null)
    val enrichedHomeState = baseHomeState
        .withHeroEnrichments(heroEnrichments)
        .let { state ->
            state.copy(
                statusMessage = state.statusMessage
                    ?: cloudSyncNotice
                    ?: communityStatusMessage
                    ?: integrityStatusMessage,
            )
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

    LaunchedEffect(Unit) {
        homeSplashStartedAt = System.currentTimeMillis()
    }
    LaunchedEffect(homeReloadNonce) {
        if (homeReloadNonce > 0) {
            homeSplashVisible = true
            homeSplashStartedAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(catalogState, homeSplashStartedAt, homeReloadNonce) {
        if (homeSplashStartedAt == 0L) return@LaunchedEffect
        while (homeSplashVisible) {
            val rows = catalogState.rows
            val pending = rows.count { it.loadState is RewriteCatalogLoadState.Loading }
            val elapsed = System.currentTimeMillis() - homeSplashStartedAt
            // Settled = every row finished loading (Content/Empty/Error), not "must have posters".
            val catalogsReady = rows.isNotEmpty() && pending == 0
            if (elapsed >= HomeSplashMinDurationMs && catalogsReady) {
                homeSplashVisible = false
                break
            }
            if (elapsed >= HomeSplashMaxDurationMs) {
                homeSplashVisible = false
                break
            }
            delay(100)
        }
    }
    LaunchedEffect(destination, detailsReloadNonce) {
        val details = destination as? RewriteRootDestination.Details ?: return@LaunchedEffect
        detailsState = null
        detailsMessage = null
        detailsRelated = emptyList()
        detailsRelatedLoading = true
        detailsActionNotice = null
        when (val result = graph.detailsDataSource.load(details.addonId, details.type, details.id)) {
            is RewriteDetailsLoadResult.Ready -> detailsState = result.state
            is RewriteDetailsLoadResult.Unavailable -> detailsMessage = result.message
        }
    }
    LaunchedEffect(detailsState?.id, detailsState?.isSeries, detailsState?.contentLanguage, destination) {
        val details = destination as? RewriteRootDestination.Details ?: return@LaunchedEffect
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
                    RewriteDetailsRelatedPoster(
                        id = it.id,
                        title = it.title,
                        imageUrl = com.sluggyard.tv.ui.rewrite.preferLargePosterUrl(it.imageUrl),
                        contentType = it.contentType,
                        addonId = it.addonId,
                    )
                }
                .toList()
        }
        detailsRelatedLoading = false
    }
    LaunchedEffect(destination, registry.addons, debridSettings.enabled, debridConnection.activeService) {
        val streams = destination as? RewriteRootDestination.Streams ?: return@LaunchedEffect
        streamGroups = emptyList()
        streamDiscoveryComplete = false
        streamMessage = null
        autoPickFinishedFor = null
        autoPickRejectedIds = emptySet()
        graph.streamsDataSource.streamGroups(
            streams.type,
            streams.id,
            configuredDebrid = debridConnection.activeService.takeIf { debridSettings.enabled },
        ).collect { groups ->
            streamGroups = groups
            // Mark discovery done as soon as every addon left Loading — do not wait for TorBox
            // cache probes (those can take a long time and previously left the UI stuck on
            // "Finding a playable stream…").
            if (groups.isNotEmpty() &&
                groups.none { it.state is com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState.Loading }
            ) {
                streamDiscoveryComplete = true
            }
        }
        streamDiscoveryComplete = true
    }

    // Cap how long we wait on CHECKING badges (auto-pick AND the Sources list) so a slow/broken
    // cache API cannot leave Play stuck on "Finding…" or Sources stuck on "Checking".
    var cacheWaitExpired by remember(destination) { mutableStateOf(false) }
    LaunchedEffect(destination, streamDiscoveryComplete) {
        cacheWaitExpired = false
        val streams = destination as? RewriteRootDestination.Streams ?: return@LaunchedEffect
        if (!streamDiscoveryComplete) return@LaunchedEffect
        delay(8_000)
        cacheWaitExpired = true
    }
    // Hard ceiling: if an addon never leaves Loading, still unblock the building screen.
    LaunchedEffect(destination) {
        val streams = destination as? RewriteRootDestination.Streams ?: return@LaunchedEffect
        delay(20_000)
        if (destination == streams) streamDiscoveryComplete = true
    }
    val displayStreamGroups = remember(streamGroups, cacheWaitExpired) {
        if (!cacheWaitExpired) streamGroups else remapCheckingCacheStates(streamGroups)
    }

    fun resolveStreamCandidate(candidate: RewriteStreamCandidate, streams: RewriteRootDestination.Streams) {
        if (resolvingCandidateId != null) return
        resolvingCandidateId = candidate.id
        streamResolutionJob = scope.launch {
            try {
                when (val result = withTimeout(45_000L) {
                    graph.manualResolution.prepare(
                        candidate.toManualSelection(
                            season = streams.season,
                            episode = streams.episode,
                            forceDebridForTorrent = streams.autoPick && !candidate.infoHash.isNullOrBlank(),
                        ),
                        configuredService = debridConnection.activeService.takeIf { debridSettings.enabled },
                    )
                }) {
                    is ManualResolutionResult.Ready -> {
                        if (routeStack.lastOrNull() != streams) return@launch
                        streamMessage = null
                        // Drop Streams, then ensure Details is on top so Back from the
                        // retained player returns to View Details (Home/Search/CW flows).
                        onRouteStackChange(stackForPlayerExit(streams))
                        onLaunchRetainedPlayer(
                            result.source,
                            streams.title,
                            streams.type,
                            streams.id,
                            streams.posterUrl ?: detailsState?.posterUrl.orEmpty(),
                            rewritePlayPreparingArtUrl(
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

    LaunchedEffect(destination, streamGroups, streamDiscoveryComplete, autoPickRejectedIds, cacheWaitExpired, displayStreamGroups) {
        val streams = destination as? RewriteRootDestination.Streams ?: return@LaunchedEffect
        if (!streams.autoPick || autoPickFinishedFor == streams.id || resolvingCandidateId != null) return@LaunchedEffect
        if (!streamDiscoveryComplete) return@LaunchedEffect
        // Wait for cache probes unless the timeout elapsed — then proceed with what we know.
        if (!cacheWaitExpired && hasRewritePendingCacheChecks(streamGroups)) return@LaunchedEffect
        val groupsForPick = if (cacheWaitExpired) displayStreamGroups else streamGroups
        val candidate = selectRewriteAutoPlayCandidate(
            groups = groupsForPick,
            context = com.sluggyard.tv.ui.rewrite.streams.RewriteStreamScoringEngine.Context(
                title = streams.title,
                contentType = streams.type,
                genres = detailsState?.genres.orEmpty(),
            ),
            excludedCandidateIds = autoPickRejectedIds,
        )
        if (candidate == null) {
            if (streamGroups.isEmpty() && !streamDiscoveryComplete) return@LaunchedEffect
            if (!cacheWaitExpired && (
                    streamGroups.any { it.state is com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState.Loading } ||
                        hasRewritePendingCacheChecks(streamGroups)
                    )
            ) {
                return@LaunchedEffect
            }
            autoPickFinishedFor = streams.id
            streamMessage = if (detailsState?.availability?.isNotEmpty() == true) {
                "No cached playable stream. Watch on: ${detailsState?.availability?.joinToString(", ")}"
            } else {
                "No cached playable stream was found. Open Sources and pick an Instant/Cached option."
            }
            return@LaunchedEffect
        }
        resolveStreamCandidate(candidate, streams)
    }

    SlugYardTvTheme {
        CompositionLocalProvider(
            LocalContentColor provides SlugYardPalette.OnCanvas,
            LocalFastHorizontalNavigationEnabled provides fastHorizontalNavigation,
        ) {
        val navDestination = when (destination) {
            is RewriteRootDestination.Details,
            is RewriteRootDestination.Streams,
            is RewriteRootDestination.WatchHub,
            -> navigationRoot
            RewriteRootDestination.Home -> RewriteRootDestination.Home
            RewriteRootDestination.Profiles -> RewriteRootDestination.Profiles
            RewriteRootDestination.Settings -> RewriteRootDestination.Settings
            RewriteRootDestination.CloudManager -> RewriteRootDestination.CloudManager
            RewriteRootDestination.Search -> RewriteRootDestination.Search
            is RewriteRootDestination.Browse -> destination
        }
        val showRootNav = destination !is RewriteRootDestination.Profiles &&
            destination !is RewriteRootDestination.Details &&
            destination !is RewriteRootDestination.Streams &&
            destination !is RewriteRootDestination.WatchHub
        // Home: Netflix-style floating nav over the hero. Other tabs keep a reserved top inset
        // so list content never sits under the icons.
        val immersiveHomeNav = destination == RewriteRootDestination.Home
        Box(modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas)) {
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
            val onHomePosterSelected: (com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster) -> Unit = { poster ->
                poster.addonId?.let { addonId ->
                    push(RewriteRootDestination.Details(
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
            val onContinueWatchingResume: (com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster) -> Unit = { poster ->
                val parentId = poster.parentId
                    ?: inferSeriesParentId(poster.id, poster.season, poster.episode)
                val addonId = poster.addonId ?: resolveMetadataAddonId(registry)
                pushPlayOrWatchHub(
                    type = poster.contentType ?: "movie",
                    id = poster.id,
                    title = poster.title,
                    posterUrl = poster.imageUrl,
                    backdropUrl = poster.backdropUrl,
                    season = poster.season,
                    episode = poster.episode,
                    addonId = addonId,
                    parentId = parentId,
                    parentType = poster.parentType
                        ?: poster.contentType?.takeIf { poster.season != null || poster.episode != null }
                            ?.let { "series" },
                    resumePositionMs = poster.resumePositionMs.takeIf { it > 0L }
                        ?: resumeFor(poster.id, poster.season, poster.episode),
                    contentGenres = poster.contentGenres,
                    contentLanguage = poster.contentLanguage,
                )
            }
            val browseScreenState = remember(destination, browseCatalogState, baseHomeState, libraryWatch) {
                val browse = destination as? RewriteRootDestination.Browse ?: return@remember null
                if (browse.filter == RewriteBrowseFilter.WATCHLIST) {
                    baseHomeState.forBrowse(browse.filter, libraryWatch)
                } else {
                    browseCatalogState.toScreenState(emptyList()).forBrowse(browse.filter, libraryWatch)
                }
            }
            when (destination) {
                RewriteRootDestination.Home -> RewriteHomeScreen(
                    state = enrichedHomeState,
                    smoothFocusMovement = smoothFocusMovement,
                     contentFocusRequester = homeContentFocusRequester,
                     headerFocusRequester = homeHeaderFocusRequester,
                     requestInitialFocus = !homeInitialFocusRequested && !homeSplashVisible,
                     onInitialFocusRequested = { homeInitialFocusRequested = true },
                    rowScrollStates = homeRowScrollStates,
                    heroIndexState = homeHeroIndexState,
                    lastHeroKeyState = homeLastHeroKeyState,
                    onPlay = { hero ->
                        replaceCurrent(if (hero.contentType == "series") {
                            RewriteRootDestination.Details(
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
                        push(RewriteRootDestination.Details(
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
                RewriteRootDestination.Search -> com.sluggyard.tv.ui.rewrite.search.RewriteSearchScreen(
                    search = { query -> graph.searchDataSource.search(query) },
                    onPosterSelected = onHomePosterSelected,
                    contentFocusRequester = searchContentFocusRequester,
                    headerFocusRequester = searchHeaderFocusRequester,
                    onBack = { popRoute() },
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.CloudManager -> RewriteCloudManagerScreen(
                    contentFocusRequester = cloudManagerFocusRequester,
                    headerFocusRequester = cloudHeaderFocusRequester,
                    connection = debridConnection,
                    onConnect = { service, apiKey ->
                        graph.debridRuntime.connect(service, apiKey)
                        settingsFacade.setDebridEnabled(true)
                        graph.provisionCommunitySources(true)
                    },
                    onSelect = { service -> graph.debridRuntime.select(service) },
                    onDisconnect = { service -> graph.debridRuntime.disconnect(service) },
                    onOpenSettings = { openRoot(RewriteRootDestination.Settings) },
                    loadTorboxCloudFiles = { graph.debridRuntime.listTorboxCloudFiles() },
                    modifier = Modifier.fillMaxSize(),
                )
                is RewriteRootDestination.Browse -> RewriteHomeScreen(
                          state = browseScreenState ?: baseHomeState.forBrowse(destination.filter, libraryWatch),
                    smoothFocusMovement = smoothFocusMovement,
                    contentFocusRequester = homeContentFocusRequester,
                    headerFocusRequester = homeHeaderFocusRequester,
                    expandOnFocus = destination.filter != RewriteBrowseFilter.WATCHLIST,
                    rowScrollStates = browseRowScrollStates,
                    heroIndexState = browseHeroIndexState,
                    lastHeroKeyState = browseLastHeroKeyState,
                    onPlay = { hero ->
                        replaceCurrent(
                            if (hero.contentType == "series") {
                                RewriteRootDestination.Details(hero.addonId, hero.contentType, hero.id)
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
                        push(RewriteRootDestination.Details(hero.addonId, hero.contentType, hero.id))
                    },
                    onPosterSelected = onHomePosterSelected,
                    onContinueWatchingLongPress = { continueWatchingMenuPoster = it },
                    onRetry = { homeReloadNonce++ },
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Profiles -> RewriteProfilesScreen(
                    state = profiles,
                    onSelect = { profile ->
                        scope.launch {
                            graph.selectProfile(profile.id)
                            openRoot(profilesReturnDestination)
                        }
                    },
                    onCreate = { name -> scope.launch { graph.createProfile(name) } },
                    onRename = { profile, name -> scope.launch { graph.renameProfile(profile, name) } },
                    onRemove = { profile -> scope.launch { graph.removeProfile(profile) } },
                    onBack = ::popRoute,
                    contentFocusRequester = profilesContentFocusRequester,
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Settings -> RewriteSettingsScreen(
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
                        graph.provisionCommunitySources(true)
                    },
                    onDisconnect = { service ->
                        graph.debridRuntime.disconnect(service)
                        val enabled = settingsFacade.debridSettings.first().enabled
                        val stillConnected = graph.debridRuntime.configuredService() != null
                        graph.provisionCommunitySources(enabled && stillConnected)
                    },
                    onDebridEnabledChanged = { enabled ->
                        graph.provisionCommunitySources(enabled && debridConnection.activeService != null)
                    },
                    onSelect = { service ->
                        graph.debridRuntime.select(service)
                        graph.provisionCommunitySources(settingsFacade.debridSettings.first().enabled)
                    },
                    rewriteAddons = registry.addons,
                    onInstallCommunityAddons = {
                        settingsFacade.setCommunityAddonEnabled(true)
                        val hasDebrid = settingsFacade.debridSettings.first().enabled &&
                            graph.debridRuntime.configuredService() != null
                        graph.provisionCommunitySources(hasDebrid)
                    },
                    onUninstallCommunityAddons = {
                        settingsFacade.setCommunityAddonEnabled(false)
                        graph.uninstallCommunitySources()
                    },
                    onOpenProfiles = {
                        profilesReturnDestination = RewriteRootDestination.Settings
                        push(RewriteRootDestination.Profiles)
                    },
                    contentFocusRequester = settingsContentFocusRequester,
                    headerFocusRequester = settingsHeaderFocusRequester,
                    modifier = Modifier.fillMaxSize(),
                )
                is RewriteRootDestination.Details -> {
                    val state = detailsState
                    if (state != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                         RewriteDetailsScreen(
                             state = state,
                            onPlay = {
                                 (destination as? RewriteRootDestination.Details)?.let { details ->
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
                                  val details = destination as? RewriteRootDestination.Details
                                  scope.launch {
                                      graph.libraryWatch.setInLibrary(
                                          id = state.id,
                                          inLibrary = inLibrary,
                                          entry = if (inLibrary) {
                                              RewriteLibraryEntry(
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
                                 val details = destination as? RewriteRootDestination.Details
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
                                     RewriteRootDestination.Details(
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
                         RewriteDetailsStatusPanel(
                             isLoading = detailsMessage == null,
                             message = detailsMessage,
                             onRetry = { detailsReloadNonce++ },
                             onBack = ::popRoute,
                             focusRequester = detailsContentFocusRequester,
                         )
                    }
                }
                is RewriteRootDestination.WatchHub -> {
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
                    RewriteWatchHubScreen(
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
                is RewriteRootDestination.Streams -> {
                    val streams = destination as RewriteRootDestination.Streams
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
                        RewriteBuildingPlayerScreen(
                            artUrl = rewritePlayPreparingArtUrl(
                                streams.backdropUrl ?: detailsState?.backdropUrl,
                                streams.posterUrl ?: detailsState?.posterUrl,
                            ),
                            title = streams.title,
                            statusMessage = streamMessage
                                ?: if (
                                    resolvingCandidateId != null ||
                                    !streamDiscoveryComplete ||
                                    (!cacheWaitExpired && hasRewritePendingCacheChecks(streamGroups))
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
                             RewriteStreamsScreen(
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
            if (destination == RewriteRootDestination.Home && homeSplashVisible) {
                val catalogTotal = catalogState.rows.size
                val catalogDone = catalogState.rows.count { it.loadState !is RewriteCatalogLoadState.Loading }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SlugYardPalette.Canvas)
                        .zIndex(8f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(color = SlugYardPalette.Accent)
                        Text(
                            "Loading SlugYard…",
                            style = MaterialTheme.typography.titleLarge,
                            color = SlugYardPalette.OnCanvas,
                        )
                        Text(
                            if (catalogTotal > 0) {
                                "Loading catalogs ($catalogDone / $catalogTotal)"
                            } else {
                                "Getting your home catalogs ready"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlugYardPalette.OnCanvasMuted,
                        )
                    }
                }
            }
            continueWatchingMenuPoster?.let { poster ->
                RewriteContinueWatchingOptionsDialog(
                    title = poster.title,
                    onViewDetails = {
                        val addonId = poster.addonId ?: resolveMetadataAddonId(registry)
                        if (addonId != null) {
                            val (detailsId, detailsType) = rewriteDetailsTarget(
                                contentId = poster.id,
                                contentType = poster.contentType,
                                parentId = poster.parentId,
                                parentType = poster.parentType,
                                season = poster.season,
                                episode = poster.episode,
                            )
                            push(
                                RewriteRootDestination.Details(
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
                        scope.launch {
                            graph.playbackProgress.remove(
                                contentId = poster.id,
                                season = poster.season,
                                episode = poster.episode,
                            )
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
                    RewriteRootNavigation(
                        destination = navDestination,
                        onDestinationChanged = { next ->
                            if (next == RewriteRootDestination.Profiles) {
                                profilesReturnDestination = navigationRoot
                            }
                            openRoot(next)
                        },
                        profileInitial = profiles.profiles.find { it.id == profiles.activeProfileId }
                            ?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "S",
                        searchFocusRequester = searchHeaderFocusRequester,
                        homeFocusRequester = homeHeaderFocusRequester,
                        downFocusRequester = when (destination) {
                            RewriteRootDestination.Home -> homeContentFocusRequester
                            RewriteRootDestination.Profiles -> profilesContentFocusRequester
                            RewriteRootDestination.Settings -> settingsContentFocusRequester
                            RewriteRootDestination.CloudManager -> cloudManagerFocusRequester
                            RewriteRootDestination.Search -> searchContentFocusRequester
                            is RewriteRootDestination.Browse -> homeContentFocusRequester
                            is RewriteRootDestination.Details -> detailsContentFocusRequester
                            is RewriteRootDestination.Streams -> streamsContentFocusRequester
                            is RewriteRootDestination.WatchHub -> watchHubContentFocusRequester
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
private fun RewriteBuildingPlayerScreen(
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
    val showChooser = !statusMessage.isNullOrBlank() &&
        !statusMessage.contains("Finding a playable stream", ignoreCase = true) &&
        !statusMessage.contains("Building", ignoreCase = true) &&
        !statusMessage.contains("Buffering", ignoreCase = true) &&
        !statusMessage.contains("Preparing", ignoreCase = true)
    LaunchedEffect(showChooser) {
        runCatching {
            if (showChooser) chooseSourceFocusRequester.requestFocus()
            else backFocusRequester.requestFocus()
        }
    }
    RewritePlayPreparingSurface(
        artUrl = artUrl,
        title = title,
        statusMessage = statusMessage ?: "Finding a playable stream...",
        showChooser = showChooser,
        availability = availability,
        onChooseSource = onChooseSource,
        onBack = onBack,
        backFocusRequester = backFocusRequester,
        chooseSourceFocusRequester = chooseSourceFocusRequester,
    )
}

/** Prefer a full-bleed backdrop decode when the URL still points at a small poster size. */
// (URL upgrades live in RewritePlayPreparingSurface / RewriteImageUrls.)

private fun remapCheckingCacheStates(
    groups: List<com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup>,
): List<com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup> =
    groups.map { group ->
        val content = group.state as? com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState.Content
            ?: return@map group
        group.copy(
            state = com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState.Content(
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
private fun RewriteDetailsStatusPanel(
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

private fun RewriteDetailsState.toWatchPolicyEpisodes() = seasons.flatMap { season ->
    season.episodes.map { episode ->
        com.sluggyard.tv.core.watchstate.DetailsEpisode(
            id = episode.id,
            season = season.number,
            episode = episode.number,
            watched = episode.watched,
        )
    }
}

private fun RewriteDetailsState.withWatchPolicy(
    updated: List<com.sluggyard.tv.core.watchstate.DetailsEpisode>,
): RewriteDetailsState {
    val watchedById = updated.associateBy { it.id }
    return copy(
        seasons = seasons.map { season ->
            val episodes = season.episodes.map { episode ->
                episode.copy(watched = watchedById[episode.id]?.watched ?: episode.watched)
            }
            season.copy(
                episodes = episodes,
                fullyWatched = episodes.isNotEmpty() && episodes.all(RewriteDetailsEpisode::watched),
            )
        },
    )
}


@Composable
private fun RewriteRootNavigation(
    destination: RewriteRootDestination,
    onDestinationChanged: (RewriteRootDestination) -> Unit,
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
                    onClick = { onDestinationChanged(RewriteRootDestination.Profiles) },
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
        RewriteRootIconItem(
            icon = Icons.Filled.Search,
            label = "Search",
            selected = destination == RewriteRootDestination.Search,
            downFocusRequester = downFocusRequester,
            focusRequester = searchFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RewriteRootDestination.Search) }
        RewriteRootNavigationItem(
            label = "Home",
            selected = destination == RewriteRootDestination.Home,
            downFocusRequester = downFocusRequester,
            upFocusRequester = homeFocusRequester,
            focusRequester = homeFocusRequester,
            onForceDown = ::forceDownToContent,
        ) {
            onDestinationChanged(RewriteRootDestination.Home)
        }
        RewriteRootNavigationItem(
            label = "Watchlist",
            selected = destination == RewriteRootDestination.Browse(RewriteBrowseFilter.WATCHLIST),
            downFocusRequester = downFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RewriteRootDestination.Browse(RewriteBrowseFilter.WATCHLIST)) }
        // Movies / TV Shows stay off the top header on purpose — browse those from Search.
        RewriteRootIconItem(
            icon = Icons.Filled.Cloud,
            label = "Cloud Manager",
            selected = destination == RewriteRootDestination.CloudManager,
            downFocusRequester = downFocusRequester,
            focusRequester = cloudHeaderFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RewriteRootDestination.CloudManager) }
        RewriteRootIconItem(
            icon = Icons.Filled.Settings,
            label = "Settings",
            selected = destination == RewriteRootDestination.Settings,
            downFocusRequester = downFocusRequester,
            focusRequester = settingsHeaderFocusRequester,
            onForceDown = ::forceDownToContent,
        ) { onDestinationChanged(RewriteRootDestination.Settings) }
    }
}

@Composable
private fun RewriteRootIconItem(
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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
private fun RewriteRootNavigationItem(
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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

/** Home cold-start splash: let TMDB/addon fanout finish before focus hits a partial shelf list. */
private const val HomeSplashMinDurationMs = 1_800L
private const val HomeSplashMaxDurationMs = 12_000L

private fun RewriteHomeCatalogState.toScreenState(
    playbackCheckpoints: List<com.sluggyard.tv.ui.rewrite.data.RewritePlaybackCheckpoint>,
): RewriteHomeState {
    val catalogRows = rows.mapNotNull { row ->
        val content = row.loadState as? RewriteCatalogLoadState.Content ?: return@mapNotNull null
        RewriteHomeRow(
            id = "${row.key.addonId}:${row.key.catalogId}",
            title = row.title,
            // Cap eagerly so Home LazyRows stay bounded even when an addon dumps a huge catalog.
            posters = content.posters.take(MaxHomeRowPosters),
        )
    }
    val catalogPostersById = catalogRows.flatMap { it.posters }.associateBy(RewriteHomePoster::id)
    val hasContent = catalogRows.isNotEmpty()
    val isLoading = !hasContent && (rows.isEmpty() || rows.any { it.loadState is RewriteCatalogLoadState.Loading })
    val errorCount = rows.count { it.loadState is RewriteCatalogLoadState.Error }
    val statusMessage = if (errorCount > 0) {
        if (hasContent) "Some catalogs could not load. Check your connection and try again."
        else "Home catalogs could not load. Check your connection and try again."
    } else {
        null
    }
    fun heroFor(poster: com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster): com.sluggyard.tv.ui.rewrite.home.RewriteHero? {
        val type = poster.contentType ?: "movie"
        val addonId = poster.addonId ?: return null
        return com.sluggyard.tv.ui.rewrite.home.RewriteHero(
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
    return RewriteHomeState(
        hero = heroCandidates.firstOrNull(),
        heroCandidates = heroCandidates,
        rows = buildList {
        if (playbackCheckpoints.isNotEmpty()) {
            add(
                RewriteHomeRow(
                    id = "continue_watching",
                    title = "Continue Watching",
                    posters = groupRewritePlaybackCheckpoints(playbackCheckpoints).map { checkpoint ->
                        val checkpointParentId = checkpoint.parentId
                            ?: inferSeriesParentId(checkpoint.contentId, checkpoint.season, checkpoint.episode)
                        val catalogMatch = checkpointParentId?.let(catalogPostersById::get)
                            ?: catalogPostersById[checkpoint.contentId]
                        com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster(
                            id = checkpoint.contentId,
                            title = catalogMatch?.title ?: checkpoint.title,
                            imageUrl = checkpoint.posterUrl ?: catalogMatch?.imageUrl,
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

private fun RewriteHomeState.forBrowse(
    filter: RewriteBrowseFilter,
    libraryWatch: RewriteLibraryWatchState,
): RewriteHomeState {
    val posters = if (filter == RewriteBrowseFilter.WATCHLIST && libraryWatch.libraryEntries.isNotEmpty()) {
        libraryWatch.libraryEntries.map { entry ->
            com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster(
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
                RewriteBrowseFilter.MOVIES -> contentType in setOf("movie", "movies", "film", "films")
                RewriteBrowseFilter.TV_SHOWS -> contentType in setOf("series", "show", "shows", "tv", "tvshow", "tvshows")
                RewriteBrowseFilter.WATCHLIST -> poster.id in libraryWatch.libraryIds
            }
        }
    val rows = if (posters.isEmpty()) {
        emptyList()
    } else {
        listOf(RewriteHomeRow(filter.name, filter.title(), posters.distinctBy { it.id }))
    }
    return copy(
        hero = null,
        heroCandidates = emptyList(),
        rows = rows,
        statusMessage = if (rows.isEmpty()) {
            when (filter) {
                RewriteBrowseFilter.WATCHLIST -> "Your watchlist is empty."
                else -> "No ${filter.title().lowercase()} are available yet."
            }
        } else null,
        isLoading = false,
    )
}

private fun RewriteBrowseFilter.title(): String = when (this) {
    RewriteBrowseFilter.MOVIES -> "Movies"
    RewriteBrowseFilter.TV_SHOWS -> "TV Shows"
    RewriteBrowseFilter.WATCHLIST -> "Watchlist"
}
