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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sluggyard.tv.core.addonprotocol.StremioAddonTransport
import com.sluggyard.tv.core.addonprotocol.AddonRegistryAction
import com.sluggyard.tv.core.profile.ProfileManager
import com.sluggyard.tv.core.streamresolution.ManualStreamSelection
import com.sluggyard.tv.core.streamresolution.ManualResolutionResult
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.watchstate.DetailsWatchPolicy
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.design.SlugYardTvTheme
import com.sluggyard.tv.ui.rewrite.data.RewriteCommunityAddonProvisioner
import com.sluggyard.tv.ui.rewrite.data.RewriteAddonRegistryStore
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchStore
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchState
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackProgressStore
import com.sluggyard.tv.core.sync.RewriteProgressSyncBridge
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileStore
import com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettingsStore
import com.sluggyard.tv.ui.rewrite.data.RewriteGuestSessionStore
import com.sluggyard.tv.ui.rewrite.data.PriorProfileSnapshot
import com.sluggyard.tv.ui.rewrite.data.RewriteProfile
import com.sluggyard.tv.ui.rewrite.data.RewriteLibraryEntry
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsDataSource
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsEpisode
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsLoadResult
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsScreen
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsState
import com.sluggyard.tv.ui.rewrite.details.playbackTarget
import com.sluggyard.tv.ui.rewrite.home.RewriteCatalogLoadState
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeCatalogState
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeDataSource
import com.sluggyard.tv.ui.rewrite.home.RewriteHeroEnrichment
import com.sluggyard.tv.ui.rewrite.home.RewriteHeroEnrichmentDataSource
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeRow
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeScreen
import com.sluggyard.tv.ui.rewrite.home.RewriteHomeState
import com.sluggyard.tv.ui.rewrite.home.withHeroEnrichments
import com.sluggyard.tv.ui.rewrite.settings.effectiveProfileId
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamCandidate
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsDataSource
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsScreen
import com.sluggyard.tv.ui.rewrite.streams.selectRewriteAutoPlayCandidate
import com.sluggyard.tv.ui.rewrite.streams.hasRewritePendingCacheChecks
import com.sluggyard.tv.ui.rewrite.profiles.RewriteProfilesScreen
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsScreen
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsFacade
import com.sluggyard.tv.ui.rewrite.player.RewriteRetainedPlayerHost
import com.sluggyard.tv.ui.rewrite.auth.RewriteAuthGate
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridRuntime
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridConnection
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridCredentialStore
import com.sluggyard.tv.ui.rewrite.debrid.RewriteCloudManagerScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Runtime composition root for the rewritten application path. */
class RewriteAppGraph(
    context: Context,
    private val profileManager: ProfileManager,
    private val rewriteDataStore: DataStore<Preferences>,
    progressSyncBridge: RewriteProgressSyncBridge,
) {
    val registry = RewriteAddonRegistryStore(rewriteDataStore)
    val profiles = RewriteProfileStore(rewriteDataStore)
    val libraryWatch = RewriteLibraryWatchStore(rewriteDataStore, profiles)
    val playbackProgress = RewritePlaybackProgressStore(rewriteDataStore, profiles, progressSyncBridge)
    private val debridCredentials = RewriteDebridCredentialStore(rewriteDataStore, profiles)
    val debridRuntime = RewriteDebridRuntime(debridCredentials)
    val homeSettings = RewriteHomeSettingsStore(rewriteDataStore, profiles)
    val guestSession = RewriteGuestSessionStore(rewriteDataStore)
    private val gateway = StremioAddonTransport(RewriteNetworkClient.create())
    private val communityProvisioner = RewriteCommunityAddonProvisioner(gateway, registry)
    val homeDataSource = RewriteHomeDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
        settingsSnapshot = { homeSettings.settings.firstValue() },
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
    val searchDataSource = com.sluggyard.tv.ui.rewrite.search.RewriteSearchDataSource(
        registrySnapshot = { registry.state.firstValue() },
        gateway = gateway,
    )
    val manualResolution = debridRuntime.manualResolution

    /** Best-effort network bootstrap; UI is already composed while this runs on the background IO path. */
    suspend fun provisionCommunitySources(debridEnabled: Boolean = true) {
        communityProvisioner.provision(if (debridEnabled) debridRuntime.configuredAddonUrls() else null)
    }

    suspend fun synchronizeProfiles() {
        // The first rewrite build stored its default profile under "default". Preserve those
        // values before importing the legacy integer profile list, otherwise Home preferences,
        // progress, and credentials would become invisible after the id changes to "1".
        rewriteDataStore.edit { preferences ->
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
        profiles.migrateIfDefault(
            profiles = profileManager.profiles.value.map { PriorProfileSnapshot(it.id.toString(), it.name) },
            activeProfileId = profileManager.activeProfileId.value.toString(),
            rememberLastProfile = profileManager.rememberLastProfileEnabled.value,
        )
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
        val season: Int? = null,
        val episode: Int? = null,
        val addonId: String? = null,
        val parentId: String? = null,
        val parentType: String? = null,
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
                    onSignedOut = { accessGranted = false },
                    onLaunchRetainedPlayer = { source, title, type, id, posterUrl, season, episode, addonId, parentId, parentType ->
                        navController.navigate(
                            RewritePlayerRoute.create(
                                source.url,
                                title,
                                id,
                                type,
                                posterUrl,
                                season,
                                episode,
                                addonId,
                                parentId,
                                parentType,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                RewriteAuthGate(
                    context = context,
                    auth = auth,
                    sessions = sessions,
                    guestSession = graph.guestSession,
                    onAuthenticated = { accessGranted = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composable(RewritePlayerRoute.pattern) {
            RewriteRetainedPlayerHost(
                entry = it,
                progressRepository = graph.playbackProgress,
                streamsDataSource = graph.streamsDataSource,
                detailsDataSource = graph.detailsDataSource,
                manualResolution = graph.manualResolution,
                configuredDebrid = { graph.debridRuntime.configuredService() },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Independent route contract for the retained player boundary; it has no legacy navigation dependency. */
private object RewritePlayerRoute {
    const val pattern = "rewrite_player/{streamUrl}/{title}?contentId={contentId}&contentType={contentType}&contentName={contentName}&videoId={videoId}&posterUrl={posterUrl}&season={season}&episode={episode}&addonId={addonId}&parentId={parentId}&parentType={parentType}"

    fun create(
        streamUrl: String,
        title: String,
        contentId: String,
        contentType: String,
        posterUrl: String = "",
        season: Int? = null,
        episode: Int? = null,
        addonId: String? = null,
        parentId: String? = null,
        parentType: String? = null,
    ): String =
        "rewrite_player/${Uri.encode(streamUrl)}/${Uri.encode(title)}?contentId=${Uri.encode(contentId)}&contentType=${Uri.encode(contentType)}&contentName=${Uri.encode(title)}&videoId=${Uri.encode(contentId)}&posterUrl=${Uri.encode(posterUrl)}&season=${season?.toString().orEmpty()}&episode=${episode?.toString().orEmpty()}&addonId=${Uri.encode(addonId.orEmpty())}&parentId=${Uri.encode(parentId.orEmpty())}&parentType=${Uri.encode(parentType.orEmpty())}"
}

@Composable
private fun RewriteAppContent(
    graph: RewriteAppGraph,
    settingsFacade: RewriteSettingsFacade,
    onSignedOut: () -> Unit,
    onLaunchRetainedPlayer: (ResolvedPlaybackSource, String, String, String, String, Int?, Int?, String?, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val registry by graph.registry.state.collectAsState(initial = com.sluggyard.tv.core.addonprotocol.AddonRegistryState())
    val playbackCheckpoints by graph.playbackProgress.checkpoints.collectAsState(initial = emptyList())
    val libraryWatch by graph.libraryWatch.state.collectAsState(initial = RewriteLibraryWatchState())
    val profiles by graph.profiles.state.collectAsState(initial = com.sluggyard.tv.ui.rewrite.data.RewriteProfileState())
    val homeSettings by graph.homeSettings.settings.collectAsState(initial = com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettings())
    val debridSettings by settingsFacade.debridSettings.collectAsState(initial = DebridSettings())
    val smoothFocusMovement by settingsFacade.layoutPreferences.smoothBringIntoViewEnabled.collectAsState(initial = true)
    val debridConnection by graph.debridRuntime.connection.collectAsState(initial = RewriteDebridConnection())
    var routeStack by remember { mutableStateOf(listOf<RewriteRootDestination>(RewriteRootDestination.Home)) }
    val destination = routeStack.last()
    var catalogState by remember { mutableStateOf(RewriteHomeCatalogState(emptyList())) }
    // Held outside the `when(destination)` branch below so Home's (and Browse's) per-row
    // horizontal scroll positions and hero rotation index survive navigating to Details/Streams
    // and back -- RewriteHomeScreen itself is fully disposed/recomposed on that round trip since
    // only one destination branch renders at a time.
    val homeRowScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.ScrollState>() }
    val homeHeroIndexState = remember { mutableStateOf(0) }
    val homeLastHeroKeyState = remember { mutableStateOf<String?>(null) }
    val browseRowScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.ScrollState>() }
    val browseHeroIndexState = remember { mutableStateOf(0) }
    val browseLastHeroKeyState = remember { mutableStateOf<String?>(null) }
    var heroEnrichments by remember { mutableStateOf<Map<String, RewriteHeroEnrichment>>(emptyMap()) }
    var detailsState by remember { mutableStateOf<RewriteDetailsState?>(null) }
    var detailsMessage by remember { mutableStateOf<String?>(null) }
    var detailsReloadNonce by remember { mutableStateOf(0) }
    var streamGroups by remember { mutableStateOf<List<RewriteStreamGroup>>(emptyList()) }
    var streamDiscoveryComplete by remember { mutableStateOf(false) }
    var streamMessage by remember { mutableStateOf<String?>(null) }
    var autoPickFinishedFor by remember { mutableStateOf<String?>(null) }
    var autoPickRejectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var profileBridgeReady by remember { mutableStateOf(false) }
    var homeReloadNonce by remember { mutableStateOf(0) }
    var streamResolutionJob by remember { mutableStateOf<Job?>(null) }
    var resolvingCandidateId by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun openRoot(next: RewriteRootDestination) {
        routeStack = listOf(next)
    }

    fun push(next: RewriteRootDestination) {
        routeStack = routeStack + next
    }

    fun replaceCurrent(next: RewriteRootDestination) {
        routeStack = routeStack.dropLast(1) + next
    }

    fun popRoute() {
        if (routeStack.size > 1) routeStack = routeStack.dropLast(1)
        else openRoot(RewriteRootDestination.Home)
    }
    // Every persistent header control owns an explicit Down target. The content surfaces own their
    // return focus and vertical row graph, so header traversal does not fall back to geometry.
    val homeContentFocusRequester = remember { FocusRequester() }
    val homeHeaderFocusRequester = remember { FocusRequester() }
    val profilesContentFocusRequester = remember { FocusRequester() }
    val settingsContentFocusRequester = remember { FocusRequester() }
    val detailsContentFocusRequester = remember { FocusRequester() }
    val streamsContentFocusRequester = remember { FocusRequester() }
    val searchContentFocusRequester = remember { FocusRequester() }
    val searchHeaderFocusRequester = remember { FocusRequester() }

    LaunchedEffect(graph) {
        graph.synchronizeProfiles()
        profileBridgeReady = true
    }

    LaunchedEffect(graph, profiles.rememberLastProfile, profileBridgeReady) {
        if (!profileBridgeReady) return@LaunchedEffect
        val profileId = effectiveProfileId(
            activeProfileId = profiles.activeProfileId,
            rememberLastProfile = profiles.rememberLastProfile,
            profileIds = profiles.profiles.map { it.id },
        )
        if (profileId != profiles.activeProfileId) graph.selectProfile(profileId)
    }

    LaunchedEffect(graph, profiles.activeProfileId, debridSettings.enabled) {
        graph.provisionCommunitySources(debridSettings.enabled)
    }

    BackHandler(enabled = destination !is RewriteRootDestination.Home) {
        streamResolutionJob?.cancel()
        streamResolutionJob = null
        resolvingCandidateId = null
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
    val homeCatalogReloadKey = remember(registry.addons, homeSettings) {
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
        }
    }
    LaunchedEffect(homeCatalogReloadKey, homeReloadNonce) {
        android.util.Log.d("SlugYardDebug", "HomeEffect(RESTART) addons=${registry.addons.size} settings=$homeSettings")
        val requests = graph.homeDataSource.catalogRequests()
        com.sluggyard.tv.ui.rewrite.home.loadRewriteHomeCatalogs(
            requests,
            savedOrder = homeSettings.savedCatalogOrder(),
        ).collect { state ->
            catalogState = state
            android.util.Log.d(
                "SlugYardDebug",
                "HomeEffect(EMIT) rows=${state.rows.map { "${it.key}:${it.loadState.javaClass.simpleName}" }}",
            )
        }
    }
    val rawHomeState = catalogState.toScreenState(playbackCheckpoints)
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
    LaunchedEffect(heroCandidateKey) {
        heroEnrichments = baseHomeState.heroCandidates.mapNotNull { hero ->
            graph.heroEnrichmentDataSource.enrich(hero)?.let { hero.id to it }
        }.toMap()
    }
    val enrichedHomeState = baseHomeState.withHeroEnrichments(heroEnrichments)
    LaunchedEffect(destination, detailsReloadNonce) {
        val details = destination as? RewriteRootDestination.Details ?: return@LaunchedEffect
        detailsState = null
        detailsMessage = null
        when (val result = graph.detailsDataSource.load(details.addonId, details.type, details.id)) {
            is RewriteDetailsLoadResult.Ready -> detailsState = result.state
            is RewriteDetailsLoadResult.Unavailable -> detailsMessage = result.message
        }
    }
    LaunchedEffect(destination, registry.addons) {
        val streams = destination as? RewriteRootDestination.Streams ?: return@LaunchedEffect
        streamGroups = emptyList()
        streamDiscoveryComplete = false
        streamMessage = null
        autoPickFinishedFor = null
        autoPickRejectedIds = emptySet()
        android.util.Log.d(
            "SlugYardPlayback",
            "discover start id=${streams.id} type=${streams.type} activeDebrid=${debridConnection.activeService} legacyDebridEnabled=${debridSettings.enabled}",
        )
        graph.streamsDataSource.streamGroups(
            streams.type,
            streams.id,
            configuredDebrid = debridConnection.activeService,
        ).collect { groups ->
            streamGroups = groups
            android.util.Log.d(
                "SlugYardPlayback",
                "discover update id=${streams.id} groups=${groups.map { group -> "${group.addonId}:${group.state::class.simpleName}" }}",
            )
        }
        streamDiscoveryComplete = true
        android.util.Log.d("SlugYardPlayback", "discover complete id=${streams.id}")
    }

    fun resolveStreamCandidate(candidate: RewriteStreamCandidate, streams: RewriteRootDestination.Streams) {
        if (resolvingCandidateId != null) return
        resolvingCandidateId = candidate.id
        streamResolutionJob = scope.launch {
            try {
                android.util.Log.d(
                    "SlugYardPlayback",
                    "resolve start candidate=${candidate.id} addon=${candidate.sourceLabel} direct=${!candidate.directUrl.isNullOrBlank()} torrent=${!candidate.infoHash.isNullOrBlank()} cache=${candidate.cacheState} debrid=${debridConnection.activeService}",
                )
                when (val result = withTimeout(45_000L) {
                    graph.manualResolution.prepare(
                        ManualStreamSelection(candidate.id, candidate.directUrl, candidate.infoHash, candidate.fileIndex),
                        configuredService = debridConnection.activeService,
                    )
                }) {
                    is ManualResolutionResult.Ready -> {
                        android.util.Log.d("SlugYardPlayback", "resolve ready candidate=${candidate.id}")
                        if (routeStack.lastOrNull() != streams) return@launch
                        streamMessage = null
                        routeStack = routeStack.dropLast(1).ifEmpty { listOf(RewriteRootDestination.Home) }
                        onLaunchRetainedPlayer(
                            result.source,
                            streams.title,
                            streams.type,
                            streams.id,
                            streams.posterUrl ?: detailsState?.posterUrl.orEmpty(),
                            streams.season,
                            streams.episode,
                            streams.addonId,
                            streams.parentId,
                            streams.parentType,
                        )
                    }
                    is ManualResolutionResult.Unavailable -> {
                        android.util.Log.w("SlugYardPlayback", "resolve unavailable candidate=${candidate.id}: ${result.message}")
                        streamMessage = result.message
                        if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
                    }
                    is ManualResolutionResult.Failed -> {
                        android.util.Log.e("SlugYardPlayback", "resolve failed candidate=${candidate.id}: ${result.message}", result.cause)
                        streamMessage = result.message
                        if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
                    }
                }
            } catch (_: TimeoutCancellationException) {
                android.util.Log.e("SlugYardPlayback", "resolve timed out candidate=${candidate.id}")
                streamMessage = "TorBox did not resolve this stream within 45 seconds. Choose another source."
                if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
            } catch (failure: Exception) {
                android.util.Log.e("SlugYardPlayback", "resolve crashed candidate=${candidate.id}", failure)
                streamMessage = failure.message?.takeIf { it.isNotBlank() }
                    ?: "The selected stream could not be resolved. Choose another source."
                if (streams.autoPick) autoPickRejectedIds = autoPickRejectedIds + candidate.id
            } finally {
                resolvingCandidateId = null
                streamResolutionJob = null
            }
        }
    }

    LaunchedEffect(destination, streamGroups, streamDiscoveryComplete, autoPickRejectedIds) {
        val streams = destination as? RewriteRootDestination.Streams ?: return@LaunchedEffect
        if (!streams.autoPick || autoPickFinishedFor == streams.id || resolvingCandidateId != null) return@LaunchedEffect
        // Do not lock onto the first fast addon response. A later addon may return a compatible
        // 1080p/HDR10 or cached TorBox stream while the first addon is still returning a 2160p DV
        // URL that this TV cannot decode.
        if (!streamDiscoveryComplete) return@LaunchedEffect
        val candidate = selectRewriteAutoPlayCandidate(
            groups = streamGroups,
            context = com.sluggyard.tv.ui.rewrite.streams.RewriteStreamScoringEngine.Context(
                title = streams.title,
                contentType = streams.type,
                genres = detailsState?.genres.orEmpty(),
            ),
            excludedCandidateIds = autoPickRejectedIds,
        )
        if (candidate == null) {
            if (streamGroups.isEmpty() && !streamDiscoveryComplete) return@LaunchedEffect
            if (streamGroups.any { it.state is com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState.Loading } ||
                hasRewritePendingCacheChecks(streamGroups)
            ) {
                return@LaunchedEffect
            }
            autoPickFinishedFor = streams.id
            streamMessage = "No playable stream was found. Choose a source or try again."
            return@LaunchedEffect
        }
        android.util.Log.d(
            "SlugYardPlayback",
            "auto-pick id=${streams.id} candidate=${candidate.id} addon=${candidate.sourceLabel} direct=${!candidate.directUrl.isNullOrBlank()} torrent=${!candidate.infoHash.isNullOrBlank()} cache=${candidate.cacheState}",
        )
        resolveStreamCandidate(candidate, streams)
    }

    SlugYardTvTheme {
        CompositionLocalProvider(LocalContentColor provides SlugYardPalette.OnCanvas) {
        val navDestination = when (destination) {
            RewriteRootDestination.Home, is RewriteRootDestination.Details, is RewriteRootDestination.Streams -> RewriteRootDestination.Home
            RewriteRootDestination.Profiles -> RewriteRootDestination.Profiles
            RewriteRootDestination.Settings -> RewriteRootDestination.Settings
            RewriteRootDestination.CloudManager -> RewriteRootDestination.CloudManager
            RewriteRootDestination.Search -> RewriteRootDestination.Search
            is RewriteRootDestination.Browse -> destination
        }
        // Keep the navigation bar as a real layout band. Details and Streams may highlight Home,
        // but their content still starts below the band instead of rendering underneath it.
        Column(modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas)) {
            if (destination !is RewriteRootDestination.Profiles && destination != RewriteRootDestination.Search) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SlugYardTvMetrics.RootNavBarHeight)
                        .background(SlugYardPalette.Surface)
                        .zIndex(4f),
                ) {
                    RewriteRootNavigation(
                        destination = navDestination,
                        onDestinationChanged = ::openRoot,
                        profileInitial = profiles.profiles.find { it.id == profiles.activeProfileId }
                            ?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "S",
                        searchFocusRequester = searchHeaderFocusRequester,
                        homeFocusRequester = homeHeaderFocusRequester,
                        downFocusRequester = when (destination) {
                            RewriteRootDestination.Home -> homeContentFocusRequester
                            RewriteRootDestination.Profiles -> profilesContentFocusRequester
                            RewriteRootDestination.Settings -> settingsContentFocusRequester
                            RewriteRootDestination.CloudManager -> settingsContentFocusRequester
                            RewriteRootDestination.Search -> searchContentFocusRequester
                            is RewriteRootDestination.Browse -> homeContentFocusRequester
                            is RewriteRootDestination.Details -> detailsContentFocusRequester
                            is RewriteRootDestination.Streams -> streamsContentFocusRequester
                        },
                    )
                }
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
                    // Resume tiles are locally persisted playback state, so they do not
                    // have a catalog-specific addon id. They must still enter the
                    // rewrite stream flow instead of becoming a dead-end focus target.
                    push(RewriteRootDestination.Streams(
                        type = poster.contentType ?: "movie",
                        id = poster.id,
                        title = poster.title,
                        autoPick = true,
                        posterUrl = poster.imageUrl,
                        season = poster.season,
                        episode = poster.episode,
                    ))
                }
            }
            when (destination) {
                RewriteRootDestination.Home -> RewriteHomeScreen(
                    state = enrichedHomeState,
                    smoothFocusMovement = smoothFocusMovement,
                     contentFocusRequester = homeContentFocusRequester,
                     headerFocusRequester = homeHeaderFocusRequester,
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
                            RewriteRootDestination.Streams(
                                type = hero.contentType,
                                id = hero.id,
                                title = hero.title,
                                autoPick = true,
                                posterUrl = hero.posterUrl,
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
                    onRetry = { homeReloadNonce++ },
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Search -> com.sluggyard.tv.ui.rewrite.search.RewriteSearchScreen(
                    search = { query -> graph.searchDataSource.search(query) },
                    onPosterSelected = onHomePosterSelected,
                    onBrowseMovies = { push(RewriteRootDestination.Browse(RewriteBrowseFilter.MOVIES)) },
                    onBrowseShows = { push(RewriteRootDestination.Browse(RewriteBrowseFilter.TV_SHOWS)) },
                    contentFocusRequester = searchContentFocusRequester,
                    headerFocusRequester = null,
                    onBack = { openRoot(RewriteRootDestination.Home) },
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.CloudManager -> RewriteCloudManagerScreen(
                    contentFocusRequester = settingsContentFocusRequester,
                    connection = debridConnection,
                    onConnect = { service, apiKey -> graph.debridRuntime.connect(service, apiKey) },
                    onSelect = { service -> graph.debridRuntime.select(service) },
                    onDisconnect = { service -> graph.debridRuntime.disconnect(service) },
                    onOpenSettings = { openRoot(RewriteRootDestination.Settings) },
                    modifier = Modifier.fillMaxSize(),
                )
                is RewriteRootDestination.Browse -> RewriteHomeScreen(
                         state = baseHomeState.forBrowse(destination.filter, libraryWatch),
                    smoothFocusMovement = smoothFocusMovement,
                    contentFocusRequester = homeContentFocusRequester,
                    rowScrollStates = browseRowScrollStates,
                    heroIndexState = browseHeroIndexState,
                    lastHeroKeyState = browseLastHeroKeyState,
                    onPlay = { hero ->
                        replaceCurrent(
                            if (hero.contentType == "series") {
                                RewriteRootDestination.Details(hero.addonId, hero.contentType, hero.id)
                            } else {
                                RewriteRootDestination.Streams(
                                    type = hero.contentType,
                                    id = hero.id,
                                    title = hero.title,
                                    autoPick = true,
                                    posterUrl = hero.posterUrl,
                                )
                            },
                        )
                    },
                    onDetails = { hero ->
                        push(RewriteRootDestination.Details(hero.addonId, hero.contentType, hero.id))
                    },
                    onPosterSelected = onHomePosterSelected,
                    onRetry = { homeReloadNonce++ },
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Profiles -> RewriteProfilesScreen(
                    state = profiles,
                    onSelect = { profile ->
                        scope.launch {
                            graph.selectProfile(profile.id)
                            openRoot(RewriteRootDestination.Home)
                        }
                    },
                    onCreate = { name -> scope.launch { graph.createProfile(name) } },
                    onRename = { profile, name -> scope.launch { graph.renameProfile(profile, name) } },
                    onRemove = { profile -> scope.launch { graph.removeProfile(profile) } },
                    contentFocusRequester = profilesContentFocusRequester,
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Settings -> RewriteSettingsScreen(
                    facade = settingsFacade,
                    onSignedOut = onSignedOut,
                    onOpenAuth = {
                        scope.launch {
                            // Explicitly opening auth suspends guest restoration until the user
                            // chooses Guest again or completes real authentication.
                            graph.guestSession.setGuest(false)
                            onSignedOut()
                        }
                    },
                    homeSettings = homeSettings,
                    profileState = profiles,
                    onHideUnreleasedChanged = { enabled -> scope.launch { graph.homeSettings.setHideUnreleased(enabled) } },
                     onRememberProfileChanged = { enabled -> scope.launch { graph.setRememberLastProfile(enabled) } },
                    debridConnection = debridConnection,
                     onConnect = {
                         service, apiKey ->
                         graph.debridRuntime.connect(service, apiKey)
                         graph.provisionCommunitySources(settingsFacade.debridSettings.first().enabled)
                     },
                     onDisconnect = { service ->
                         graph.debridRuntime.disconnect(service)
                         graph.provisionCommunitySources(settingsFacade.debridSettings.first().enabled)
                     },
                     onDebridEnabledChanged = { enabled -> graph.provisionCommunitySources(enabled) },
                     onSelect = {
                        service ->
                        graph.debridRuntime.select(service)
                         graph.provisionCommunitySources(settingsFacade.debridSettings.first().enabled)
                     },
                      rewriteAddons = registry.addons,
                       onAddonEnabledChanged = { addonId, enabled ->
                           scope.launch { graph.registry.dispatch(AddonRegistryAction.SetEnabled(addonId, enabled)) }
                       },
                       onConfigureAutoSources = {
                           graph.provisionCommunitySources(settingsFacade.debridSettings.first().enabled)
                       },
                        onOpenProfiles = { push(RewriteRootDestination.Profiles) },
                     contentFocusRequester = settingsContentFocusRequester,
                     modifier = Modifier.fillMaxSize(),
                 )
                is RewriteRootDestination.Details -> {
                    val state = detailsState
                    if (state != null) {
                         RewriteDetailsScreen(
                             state = state,
                            onPlay = {
                                (destination as? RewriteRootDestination.Details)?.let { details ->
                                    val target = state.playbackTarget(details.type, details.id)
                                    replaceCurrent(RewriteRootDestination.Streams(
                                        type = target.type,
                                        id = target.id,
                                        title = state.title,
                                        autoPick = true,
                                        posterUrl = state.posterUrl,
                                        season = target.season,
                                        episode = target.episode,
                                        addonId = details.addonId,
                                        parentId = details.id,
                                        parentType = details.type,
                                    ))
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
                                         graph.libraryWatch.setWatched(episode.id, true)
                                     }
                                }
                            },
                             onEpisodeSelected = { seasonNumber, episode ->
                                 val details = destination as? RewriteRootDestination.Details
                                  replaceCurrent(RewriteRootDestination.Streams(
                                     type = "series",
                                     id = episode.id,
                                     title = state.title,
                                     autoPick = true,
                                     posterUrl = state.posterUrl,
                                     season = seasonNumber,
                                     episode = episode.number,
                                     addonId = details?.addonId,
                                     parentId = details?.id,
                                     parentType = details?.type,
                                 ))
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
                                     }.forEach { next -> graph.libraryWatch.setWatched(next.id, next.watched) }
                                 }
                             },
                             contentFocusRequester = detailsContentFocusRequester,
                             modifier = Modifier.fillMaxSize(),
                         )
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
                is RewriteRootDestination.Streams -> {
                    val streams = destination as RewriteRootDestination.Streams
                    // Play is a single handoff. The raw source list remains available only for
                    // explicit episode/source selection, never as a surprise after Play.
                    if (streams.autoPick) {
                        RewriteBuildingPlayerScreen(
                            posterUrl = streams.posterUrl,
                            title = streams.title,
                            statusMessage = streamMessage,
                            onBack = ::popRoute,
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
                                 groups = streamGroups,
                                  onStreamSelected = { candidate ->
                                      resolveStreamCandidate(candidate, streams)
                                  },
                                  onBack = ::popRoute,
                                  contentFocusRequester = streamsContentFocusRequester,
                                 modifier = Modifier.weight(1f),
                             )
                        }
                    }
                }
            }
            }
        }
        }
    }
}

/**
 * Shown while the auto-picker is resolving a playable link. The chosen poster fills the
 * background (scrimmed for text contrast) so the user sees the title they picked instead of
 * a blank/black screen or a raw link list while sources are being searched and ranked.
 */
@Composable
private fun RewriteBuildingPlayerScreen(
    posterUrl: String?,
    title: String,
    statusMessage: String?,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!posterUrl.isNullOrBlank()) {
            coil3.compose.AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.85f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = SlugYardTvMetrics.ScreenHorizontalInset, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Text(
                    statusMessage ?: "Finding a playable stream...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (statusMessage == null) Color.White.copy(alpha = 0.9f) else SlugYardPalette.Danger,
                )
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 30.dp,
                vertical = 8.dp,
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
        ) { onDestinationChanged(RewriteRootDestination.Search) }
        RewriteRootNavigationItem(
            label = "Home",
            selected = destination == RewriteRootDestination.Home,
            downFocusRequester = downFocusRequester,
            upFocusRequester = homeFocusRequester,
            focusRequester = homeFocusRequester,
        ) {
            onDestinationChanged(RewriteRootDestination.Home)
        }
        RewriteRootNavigationItem(
            "Watchlist",
            destination == RewriteRootDestination.Browse(RewriteBrowseFilter.WATCHLIST),
            downFocusRequester,
        ) { onDestinationChanged(RewriteRootDestination.Browse(RewriteBrowseFilter.WATCHLIST)) }
        RewriteRootIconItem(
            icon = Icons.Filled.Cloud,
            label = "Cloud Manager",
            selected = false,
            downFocusRequester = downFocusRequester,
         ) { onDestinationChanged(RewriteRootDestination.CloudManager) }
        RewriteRootIconItem(
            icon = Icons.Filled.Settings,
            label = "Settings",
            selected = destination == RewriteRootDestination.Settings,
            downFocusRequester = downFocusRequester,
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
            .background(if (selected) SlugYardPalette.SurfaceElevated else Color.Transparent, RoundedCornerShape(50))
            .then(if (selected) Modifier.border(2.dp, SlugYardPalette.Accent, RoundedCornerShape(50)) else Modifier)
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
            .clip(RoundedCornerShape(8.dp))
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties {
                downFocusRequester?.let { down = it }
                upFocusRequester?.let { up = it }
            }
            .background(if (selected) SlugYardPalette.SurfaceElevated else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, SlugYardPalette.Accent, RoundedCornerShape(8.dp)) else Modifier)
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

private fun RewriteHomeCatalogState.toScreenState(
    playbackCheckpoints: List<com.sluggyard.tv.ui.rewrite.data.RewritePlaybackCheckpoint>,
): RewriteHomeState {
    val catalogRows = rows.mapNotNull { row ->
        val content = row.loadState as? RewriteCatalogLoadState.Content ?: return@mapNotNull null
        RewriteHomeRow(id = "${row.key.addonId}:${row.key.catalogId}", title = row.title, posters = content.posters)
    }
    val hasContent = catalogRows.isNotEmpty()
    val isLoading = !hasContent && (rows.isEmpty() || rows.any { it.loadState is RewriteCatalogLoadState.Loading })
    val errorCount = rows.count { it.loadState is RewriteCatalogLoadState.Error }
    val statusMessage = if (!hasContent && !isLoading && errorCount > 0) {
        "Home catalogs could not load. Check your connection and try again."
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
                    posters = playbackCheckpoints.map { checkpoint ->
                        com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster(
                            id = checkpoint.contentId,
                            title = checkpoint.title,
                            imageUrl = checkpoint.posterUrl,
                            progressFraction = checkpoint.progressFraction?.toFloat(),
                            contentType = checkpoint.contentType,
                            season = checkpoint.season,
                            episode = checkpoint.episode,
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
        statusMessage = if (rows.isEmpty()) "No ${filter.title().lowercase()} are available yet." else null,
        isLoading = false,
    )
}

private fun RewriteBrowseFilter.title(): String = when (this) {
    RewriteBrowseFilter.MOVIES -> "Movies"
    RewriteBrowseFilter.TV_SHOWS -> "TV Shows"
    RewriteBrowseFilter.WATCHLIST -> "Watchlist"
}
