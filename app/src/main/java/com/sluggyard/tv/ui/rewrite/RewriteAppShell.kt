package com.sluggyard.tv.ui.rewrite

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sluggyard.tv.core.addonprotocol.StremioAddonTransport
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
import com.sluggyard.tv.ui.rewrite.data.RewritePlaybackProgressStore
import com.sluggyard.tv.ui.rewrite.data.RewriteProfileStore
import com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettingsStore
import com.sluggyard.tv.domain.model.DebridSettings
import com.sluggyard.tv.ui.rewrite.details.RewriteDetailsDataSource
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
import com.sluggyard.tv.ui.rewrite.home.withHeroEnrichment
import com.sluggyard.tv.ui.rewrite.settings.effectiveProfileId
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamCandidate
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroup
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsDataSource
import com.sluggyard.tv.ui.rewrite.streams.RewriteStreamsScreen
import com.sluggyard.tv.ui.rewrite.streams.selectRewriteAutoPlayCandidate
import com.sluggyard.tv.ui.rewrite.profiles.RewriteProfilesScreen
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsScreen
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsFacade
import com.sluggyard.tv.ui.rewrite.player.RewriteRetainedPlayerHost
import com.sluggyard.tv.ui.rewrite.auth.RewriteAuthGate
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridRuntime
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridConnection
import com.sluggyard.tv.ui.rewrite.debrid.RewriteDebridCredentialStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Intentionally kept as "playflix_rewrite": this is the internal, never-user-visible
// DataStore file name. Renaming it would orphan every existing user's persisted
// rewrite-path preferences on upgrade (DataStore has no automatic rename migration).
private val Context.rewritePreferences by preferencesDataStore(name = "playflix_rewrite")

/** Runtime composition root for the rewritten application path. */
class RewriteAppGraph(
    context: Context,
) {
    val registry = RewriteAddonRegistryStore(context.applicationContext.rewritePreferences)
    val profiles = RewriteProfileStore(context.applicationContext.rewritePreferences)
    val libraryWatch = RewriteLibraryWatchStore(context.applicationContext.rewritePreferences, profiles)
    val playbackProgress = RewritePlaybackProgressStore(context.applicationContext.rewritePreferences, profiles)
    private val debridCredentials = RewriteDebridCredentialStore(context.applicationContext.rewritePreferences, profiles)
    val debridRuntime = RewriteDebridRuntime(debridCredentials)
    val homeSettings = RewriteHomeSettingsStore(context.applicationContext.rewritePreferences, profiles)
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

}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

private sealed interface RewriteRootDestination {
    data object Home : RewriteRootDestination
    data object Profiles : RewriteRootDestination
    data object Settings : RewriteRootDestination
    data object Search : RewriteRootDestination
    data class Details(val addonId: String, val type: String, val id: String) : RewriteRootDestination
    data class Streams(
        val type: String,
        val id: String,
        val title: String,
        val autoPick: Boolean = false,
        val posterUrl: String? = null,
    ) : RewriteRootDestination
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
                    onLaunchRetainedPlayer = { source, title, type, id, posterUrl ->
                        navController.navigate(
                            RewritePlayerRoute.create(source.url, title, id, type, posterUrl),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                RewriteAuthGate(
                    context = context,
                    auth = auth,
                    sessions = sessions,
                    onAuthenticated = { accessGranted = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composable(RewritePlayerRoute.pattern) {
            RewriteRetainedPlayerHost(
                entry = it,
                progressRepository = graph.playbackProgress,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Independent route contract for the retained player boundary; it has no legacy navigation dependency. */
private object RewritePlayerRoute {
    const val pattern = "rewrite_player/{streamUrl}/{title}?contentId={contentId}&contentType={contentType}&contentName={contentName}&videoId={videoId}&returnToHomeOnBack={returnToHomeOnBack}&posterUrl={posterUrl}"

    fun create(streamUrl: String, title: String, contentId: String, contentType: String, posterUrl: String = ""): String =
        "rewrite_player/${Uri.encode(streamUrl)}/${Uri.encode(title)}?contentId=${Uri.encode(contentId)}&contentType=${Uri.encode(contentType)}&contentName=${Uri.encode(title)}&videoId=${Uri.encode(contentId)}&returnToHomeOnBack=true&posterUrl=${Uri.encode(posterUrl)}"
}

@Composable
private fun RewriteAppContent(
    graph: RewriteAppGraph,
    settingsFacade: RewriteSettingsFacade,
    onLaunchRetainedPlayer: (ResolvedPlaybackSource, String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val registry by graph.registry.state.collectAsState(initial = com.sluggyard.tv.core.addonprotocol.AddonRegistryState())
    val libraryWatch by graph.libraryWatch.state.collectAsState(initial = com.sluggyard.tv.ui.rewrite.data.RewriteLibraryWatchState())
    val playbackCheckpoints by graph.playbackProgress.checkpoints.collectAsState(initial = emptyList())
    val profiles by graph.profiles.state.collectAsState(initial = com.sluggyard.tv.ui.rewrite.data.RewriteProfileState())
    val homeSettings by graph.homeSettings.settings.collectAsState(initial = com.sluggyard.tv.ui.rewrite.data.RewriteHomeSettings())
    val debridSettings by settingsFacade.debridSettings.collectAsState(initial = DebridSettings())
    val showPosterLabels by settingsFacade.layoutPreferences.posterLabelsEnabled.collectAsState(initial = true)
    val smoothFocusMovement by settingsFacade.layoutPreferences.smoothBringIntoViewEnabled.collectAsState(initial = true)
    val fastHorizontalNavigation by settingsFacade.layoutPreferences.fastHorizontalNavigationEnabled.collectAsState(initial = false)
    val debridConnection by graph.debridRuntime.connection.collectAsState(initial = RewriteDebridConnection())
    var destination by remember { mutableStateOf<RewriteRootDestination>(RewriteRootDestination.Home) }
    var catalogState by remember { mutableStateOf(RewriteHomeCatalogState(emptyList())) }
    var heroEnrichment by remember { mutableStateOf<RewriteHeroEnrichment?>(null) }
    var detailsState by remember { mutableStateOf<RewriteDetailsState?>(null) }
    var detailsMessage by remember { mutableStateOf<String?>(null) }
    var streamGroups by remember { mutableStateOf<List<RewriteStreamGroup>>(emptyList()) }
    var streamMessage by remember { mutableStateOf<String?>(null) }
    var autoPickAttemptedFor by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The floating nav header sits visually above Home's content but Compose's 2D focus search
    // does not reliably find the hero's Play button below it (the vertical gap and the header's
    // own focus group both interfere) -- pressing DPAD_DOWN from any header item (avatar, search,
    // Home/Profiles/Settings tabs) previously did nothing at all, trapping D-pad users in the
    // header forever with no way to reach Home's rows. Explicitly wiring the header's down
    // neighbor to the hero Play button's own FocusRequester fixes this for the common case where
    // Home has hero data loaded.
    val homeContentFocusRequester = remember { FocusRequester() }

    LaunchedEffect(graph, profiles.rememberLastProfile) {
        val profileId = effectiveProfileId(
            activeProfileId = profiles.activeProfileId,
            rememberLastProfile = profiles.rememberLastProfile,
            profileIds = profiles.profiles.map { it.id },
        )
        if (profileId != profiles.activeProfileId) graph.profiles.select(profileId)
    }

    LaunchedEffect(graph, profiles.activeProfileId, debridSettings.enabled) {
        graph.provisionCommunitySources(debridSettings.enabled)
    }

    BackHandler(enabled = destination !is RewriteRootDestination.Home) {
        destination = RewriteRootDestination.Home
    }

    LaunchedEffect(registry.addons, homeSettings) {
        val requests = graph.homeDataSource.catalogRequests()
        com.sluggyard.tv.ui.rewrite.home.loadRewriteHomeCatalogs(
            requests,
            savedOrder = homeSettings.savedCatalogOrder(),
        ).collect { state ->
            catalogState = state
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
    LaunchedEffect(baseHomeState.hero?.id, baseHomeState.hero?.addonId, baseHomeState.hero?.contentType) {
        val hero = baseHomeState.hero
        heroEnrichment = if (hero == null) null else graph.heroEnrichmentDataSource.enrich(hero)
    }
    LaunchedEffect(destination, libraryWatch) {
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
        streamMessage = null
        autoPickAttemptedFor = null
        graph.streamsDataSource.streamGroups(
            streams.type,
            streams.id,
            configuredDebrid = if (debridSettings.enabled) graph.debridRuntime.configuredService() else null,
        ).collect { groups ->
            streamGroups = groups
        }
    }

    fun resolveStreamCandidate(candidate: RewriteStreamCandidate, streams: RewriteRootDestination.Streams) {
        scope.launch {
            when (val result = graph.manualResolution.prepare(
                ManualStreamSelection(candidate.id, candidate.directUrl, candidate.infoHash, candidate.fileIndex),
                configuredService = if (debridSettings.enabled) graph.debridRuntime.configuredService() else null,
            )) {
                is ManualResolutionResult.Ready -> {
                    streamMessage = null
                    onLaunchRetainedPlayer(
                        result.source,
                        streams.title,
                        streams.type,
                        streams.id,
                        streams.posterUrl ?: detailsState?.posterUrl.orEmpty(),
                    )
                }
                is ManualResolutionResult.Unavailable -> streamMessage = result.message
                is ManualResolutionResult.Failed -> streamMessage = result.message
            }
        }
    }

    LaunchedEffect(destination, streamGroups) {
        val streams = destination as? RewriteRootDestination.Streams ?: return@LaunchedEffect
        if (!streams.autoPick || autoPickAttemptedFor == streams.id || streamGroups.any { it.state is com.sluggyard.tv.ui.rewrite.streams.RewriteStreamGroupState.Loading }) return@LaunchedEffect
        val candidate = selectRewriteAutoPlayCandidate(streamGroups) ?: return@LaunchedEffect
        autoPickAttemptedFor = streams.id
        resolveStreamCandidate(candidate, streams)
    }

    SlugYardTvTheme {
        CompositionLocalProvider(LocalContentColor provides SlugYardPalette.OnCanvas) {
        val navDestination = when (destination) {
            RewriteRootDestination.Home, is RewriteRootDestination.Details, is RewriteRootDestination.Streams -> RewriteRootDestination.Home
            RewriteRootDestination.Profiles -> RewriteRootDestination.Profiles
            RewriteRootDestination.Settings -> RewriteRootDestination.Settings
            RewriteRootDestination.Search -> RewriteRootDestination.Search
        }
        // Home's hero art and Details' backdrop both bleed to the very top of the screen; the
        // nav bar floats over them on a gradient scrim instead of pushing them down, so there is
        // no hard seam. This must check the actual visible `destination`, not `navDestination`
        // -- the latter folds Details AND Streams into the "Home" tab for nav-highlighting
        // purposes only, which previously made this true even on the plain Streams list screen
        // (since Home's hero data stays loaded in the background regardless of which screen is
        // showing) and skipped RootNavBarHeight's top padding there, letting content -- most
        // visibly the red "no debrid provider" error text -- render underneath the nav bar.
        val hasHeroBackdrop = (destination == RewriteRootDestination.Home && baseHomeState.hero != null) ||
            destination is RewriteRootDestination.Details
        Box(modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { if (hasHeroBackdrop) it else it.padding(top = SlugYardTvMetrics.RootNavBarHeight) },
            ) {
            // Shared by Home's catalog rows/Continue Watching and Search results: posters that
            // carry a catalog addonId go to Details, resume tiles/search hits without one route
            // straight to Streams (see the branch comment below for why).
            val onHomePosterSelected: (com.sluggyard.tv.ui.rewrite.home.RewriteHomePoster) -> Unit = { poster ->
                poster.addonId?.let { addonId ->
                    destination = RewriteRootDestination.Details(
                        addonId = addonId,
                        type = poster.contentType ?: "movie",
                        id = poster.id,
                    )
                } ?: run {
                    // Resume tiles are locally persisted playback state, so they do not
                    // have a catalog-specific addon id. They must still enter the
                    // rewrite stream flow instead of becoming a dead-end focus target.
                    destination = RewriteRootDestination.Streams(
                        type = poster.contentType ?: "movie",
                        id = poster.id,
                        title = poster.title,
                        posterUrl = poster.imageUrl,
                    )
                }
            }
            when (destination) {
                RewriteRootDestination.Home -> RewriteHomeScreen(
                    state = baseHomeState.withHeroEnrichment(heroEnrichment),
                    showPosterLabels = showPosterLabels,
                    smoothFocusMovement = smoothFocusMovement,
                    fastHorizontalNavigation = fastHorizontalNavigation,
                    contentFocusRequester = homeContentFocusRequester,
                    onPlay = { hero ->
                        destination = if (hero.contentType == "series") {
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
                        }
                    },
                    onDetails = { hero ->
                        destination = RewriteRootDestination.Details(
                            addonId = hero.addonId,
                            type = hero.contentType,
                            id = hero.id,
                        )
                    },
                    onPosterSelected = onHomePosterSelected,
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Search -> com.sluggyard.tv.ui.rewrite.search.RewriteSearchScreen(
                    search = { query -> graph.searchDataSource.search(query) },
                    onPosterSelected = onHomePosterSelected,
                    showPosterLabels = showPosterLabels,
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Profiles -> RewriteProfilesScreen(
                    state = profiles,
                    onSelect = { profile ->
                        scope.launch {
                            graph.profiles.select(profile.id)
                            destination = RewriteRootDestination.Home
                        }
                    },
                    onCreate = { name -> scope.launch { graph.profiles.create(name) } },
                    onRename = { profile, name -> scope.launch { graph.profiles.rename(profile.id, name) } },
                    onRemove = { profile -> scope.launch { graph.profiles.remove(profile.id) } },
                    modifier = Modifier.fillMaxSize(),
                )
                RewriteRootDestination.Settings -> RewriteSettingsScreen(
                    facade = settingsFacade,
                    homeSettings = homeSettings,
                    profileState = profiles,
                    onHideUnreleasedChanged = { enabled -> scope.launch { graph.homeSettings.setHideUnreleased(enabled) } },
                    onRememberProfileChanged = { enabled -> scope.launch { graph.profiles.setRememberLastProfile(enabled) } },
                    debridConnection = debridConnection,
                    onConnect = {
                        service, apiKey ->
                        graph.debridRuntime.connect(service, apiKey)
                        graph.provisionCommunitySources(settingsFacade.debridSettings.first().enabled)
                    },
                    onDebridEnabledChanged = { enabled -> graph.provisionCommunitySources(enabled) },
                    onSelect = {
                        service ->
                        graph.debridRuntime.select(service)
                        graph.provisionCommunitySources(settingsFacade.debridSettings.first().enabled)
                    },
                    communityAddonCount = registry.addons.count { addon ->
                        // Manifest "name" is fetched at runtime from a third-party server
                        // (torrentio/mediafusion) — match both names since we don't control
                        // when/whether the remote manifest's own name field gets renamed.
                        addon.manifest.name.contains("PlayFlix", ignoreCase = true) ||
                            addon.manifest.name.contains("SlugYard", ignoreCase = true) ||
                            addon.manifestUrl.contains("torrentio", ignoreCase = true) ||
                            addon.manifestUrl.contains("mediafusion", ignoreCase = true)
                    },
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
                                    destination = RewriteRootDestination.Streams(
                                        type = target.type,
                                        id = target.id,
                                        title = state.title,
                                        autoPick = true,
                                        posterUrl = state.posterUrl,
                                    )
                                }
                            },
                            onChooseStream = {
                                (destination as? RewriteRootDestination.Details)?.let { details ->
                                    destination = RewriteRootDestination.Streams(details.type, details.id, state.title)
                                }
                            },
                            onLibraryChanged = { inLibrary ->
                                scope.launch { graph.libraryWatch.setInLibrary(state.id, inLibrary) }
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
                                    updated.filter { it.watched }.forEach { episode ->
                                        graph.libraryWatch.setWatched(episode.id, true)
                                    }
                                }
                            },
                            onEpisodeSelected = { episode ->
                                destination = RewriteRootDestination.Streams("series", episode.id, state.title)
                            },
                            onEpisodeWatchedChanged = { episode, watched ->
                                scope.launch {
                                    if (watched) {
                                        DetailsWatchPolicy.markEpisodeWatched(
                                            state.toWatchPolicyEpisodes(),
                                            episode.id,
                                        ).filter { it.watched }.forEach { updated ->
                                            graph.libraryWatch.setWatched(updated.id, true)
                                        }
                                    } else {
                                        graph.libraryWatch.setWatched(episode.id, false)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            detailsMessage ?: "Loading title details…",
                            modifier = Modifier.padding(SlugYardTvMetrics.ScreenHorizontalInset),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is RewriteRootDestination.Streams -> {
                    val streams = destination as RewriteRootDestination.Streams
                    // Auto-pick flow (Play button / Continue Watching / hero) never shows the raw
                    // link list: we go straight from "Building player" to the retained player. The
                    // manual chooser (streams.autoPick == false, reached via "Choose a stream") is
                    // the only path that still renders the link list, and only once auto-pick has
                    // failed (streamMessage set) or the user explicitly asked to pick manually.
                    val showBuildingPlayer = streams.autoPick && streamMessage == null &&
                        autoPickAttemptedFor != streams.id
                    if (showBuildingPlayer) {
                        RewriteBuildingPlayerScreen(posterUrl = streams.posterUrl, title = streams.title)
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
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            }
            // Search is a full-bleed, distraction-free surface on Netflix/Nuvio-style TV apps --
            // it does not carry the persistent Home/Profiles/Settings tab strip, since that strip
            // has nothing to do with searching and only steals focus-traversal space above the
            // keyboard/results grid. The physical back button (BackHandler above) still returns
            // to Home from here, so hiding the header does not strand navigation.
            if (destination != RewriteRootDestination.Search) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            if (hasHeroBackdrop) {
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent),
                                )
                            } else {
                                Brush.verticalGradient(listOf(SlugYardPalette.Canvas, SlugYardPalette.Canvas))
                            },
                        ),
                ) {
                    RewriteRootNavigation(
                        destination = navDestination,
                        onDestinationChanged = { destination = it },
                        profileInitial = profiles.profiles.find { it.id == profiles.activeProfileId }
                            ?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "S",
                        downFocusRequester = if (destination == RewriteRootDestination.Home) homeContentFocusRequester else null,
                    )
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
private fun RewriteBuildingPlayerScreen(posterUrl: String?, title: String) {
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
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Text("Building player…", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
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


@Composable
private fun RewriteRootNavigation(
    destination: RewriteRootDestination,
    onDestinationChanged: (RewriteRootDestination) -> Unit,
    profileInitial: String = "S",
    downFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                vertical = 14.dp,
            )
            // Every nav item (avatar, search icon, Home/Profiles/Settings tabs) inherits this as
            // its down-neighbor unless overridden closer to the leaf, which is what lets DPAD_DOWN
            // escape the header into content instead of doing nothing. See the comment on
            // homeContentFocusRequester in RewriteAppContent for the underlying bug.
            .let { base ->
                if (downFocusRequester != null) {
                    base.focusProperties { down = downFocusRequester }
                } else {
                    base
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val avatarInteraction = remember { MutableInteractionSource() }
        val avatarFocused by avatarInteraction.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .size(34.dp)
                .graphicsLayer(
                    scaleX = if (avatarFocused) 1.1f else 1f,
                    scaleY = if (avatarFocused) 1.1f else 1f,
                )
                .clip(RoundedCornerShape(8.dp))
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
        val searchInteraction = remember { MutableInteractionSource() }
        val searchFocused by searchInteraction.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .graphicsLayer(
                    scaleX = if (searchFocused) 1.15f else 1f,
                    scaleY = if (searchFocused) 1.15f else 1f,
                )
                .clip(RoundedCornerShape(50))
                .clickable(
                    interactionSource = searchInteraction,
                    indication = null,
                    onClick = { onDestinationChanged(RewriteRootDestination.Search) },
                )
                .padding(6.dp)
                .semantics { contentDescription = "Search"; role = Role.Button },
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = if (searchFocused || destination == RewriteRootDestination.Search) {
                    SlugYardPalette.OnCanvas
                } else {
                    SlugYardPalette.OnCanvasMuted
                },
                modifier = Modifier.size(22.dp),
            )
        }
        RewriteRootNavigationItem("Home", destination == RewriteRootDestination.Home) { onDestinationChanged(RewriteRootDestination.Home) }
        RewriteRootNavigationItem("Profiles", destination == RewriteRootDestination.Profiles) { onDestinationChanged(RewriteRootDestination.Profiles) }
        RewriteRootNavigationItem("Settings", destination == RewriteRootDestination.Settings) { onDestinationChanged(RewriteRootDestination.Settings) }
        Spacer(Modifier.weight(1f))
        Text(
            text = "SLUGYARD",
            style = MaterialTheme.typography.titleMedium,
            color = SlugYardPalette.OnCanvasMuted,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RewriteRootNavigationItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .graphicsLayer(
                scaleX = if (focused) 1.06f else 1f,
                scaleY = if (focused) 1.06f else 1f,
            )
            .clip(RoundedCornerShape(50))
            .background(if (selected) SlugYardPalette.OnCanvas else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                selected -> Color(0xFF181818)
                focused -> SlugYardPalette.OnCanvas
                else -> SlugYardPalette.OnCanvasMuted
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
    val heroPoster = catalogRows.firstNotNullOfOrNull { it.posters.firstOrNull() }
    return RewriteHomeState(
        hero = heroPoster?.let { poster ->
            val type = poster.contentType ?: "movie"
            val addonId = poster.addonId ?: return@let null
            com.sluggyard.tv.ui.rewrite.home.RewriteHero(
                id = poster.id,
                title = poster.title,
                backdropUrl = poster.imageUrl,
                posterUrl = poster.imageUrl,
                summary = "Browse details and choose a stream.",
                contextTag = type.replaceFirstChar(Char::titlecase),
                addonId = addonId,
                contentType = type,
            )
        },
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
                        )
                    },
                ),
            )
        }
        addAll(catalogRows)
        },
    )
}
