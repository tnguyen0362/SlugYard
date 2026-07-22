package com.sluggyard.tv.ui.rewrite

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    data class Details(val addonId: String, val type: String, val id: String) : RewriteRootDestination
    data class Streams(val type: String, val id: String, val title: String, val autoPick: Boolean = false) : RewriteRootDestination
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
                    onLaunchRetainedPlayer = { source, title, type, id ->
                        navController.navigate(
                            RewritePlayerRoute.create(source.url, title, id, type),
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
    const val pattern = "rewrite_player/{streamUrl}/{title}?contentId={contentId}&contentType={contentType}&contentName={contentName}&videoId={videoId}&returnToHomeOnBack={returnToHomeOnBack}"

    fun create(streamUrl: String, title: String, contentId: String, contentType: String): String =
        "rewrite_player/${Uri.encode(streamUrl)}/${Uri.encode(title)}?contentId=${Uri.encode(contentId)}&contentType=${Uri.encode(contentType)}&contentName=${Uri.encode(title)}&videoId=${Uri.encode(contentId)}&returnToHomeOnBack=true"
}

@Composable
private fun RewriteAppContent(
    graph: RewriteAppGraph,
    settingsFacade: RewriteSettingsFacade,
    onLaunchRetainedPlayer: (ResolvedPlaybackSource, String, String, String) -> Unit,
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
    val baseHomeState = catalogState.toScreenState(playbackCheckpoints)
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
                    onLaunchRetainedPlayer(result.source, streams.title, streams.type, streams.id)
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
        Column(modifier = modifier.fillMaxSize().background(SlugYardPalette.Canvas)) {
            RewriteRootNavigation(
                destination = when (destination) {
                    RewriteRootDestination.Home, is RewriteRootDestination.Details, is RewriteRootDestination.Streams -> RewriteRootDestination.Home
                    RewriteRootDestination.Profiles -> RewriteRootDestination.Profiles
                    RewriteRootDestination.Settings -> RewriteRootDestination.Settings
                },
                onDestinationChanged = { destination = it },
            )
            when (destination) {
                RewriteRootDestination.Home -> RewriteHomeScreen(
                    state = baseHomeState.withHeroEnrichment(heroEnrichment),
                    showPosterLabels = showPosterLabels,
                    smoothFocusMovement = smoothFocusMovement,
                    fastHorizontalNavigation = fastHorizontalNavigation,
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
                    onPosterSelected = { poster ->
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
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
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
                        addon.manifest.name.contains("PlayFlix", ignoreCase = true) ||
                            addon.manifestUrl.contains("torrentio", ignoreCase = true) ||
                            addon.manifestUrl.contains("mediafusion", ignoreCase = true)
                    },
                    modifier = Modifier.weight(1f),
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
                            modifier = Modifier.weight(1f),
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
                    Column(modifier = Modifier.weight(1f)) {
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
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
            vertical = 14.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "PLAYFLIX",
            style = MaterialTheme.typography.titleLarge,
            color = SlugYardPalette.OnCanvas,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.width(28.dp))
        RewriteRootNavigationItem("Home", destination == RewriteRootDestination.Home) { onDestinationChanged(RewriteRootDestination.Home) }
        RewriteRootNavigationItem("Profiles", destination == RewriteRootDestination.Profiles) { onDestinationChanged(RewriteRootDestination.Profiles) }
        RewriteRootNavigationItem("Settings", destination == RewriteRootDestination.Settings) { onDestinationChanged(RewriteRootDestination.Settings) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RewriteRootNavigationItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text = label,
        modifier = Modifier
            .graphicsLayer(
                scaleX = if (focused) 1.06f else 1f,
                scaleY = if (focused) 1.06f else 1f,
                alpha = if (selected || focused) 1f else 0.68f,
            )
            .clip(RoundedCornerShape(3.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = SlugYardPalette.OnCanvas,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
    )
}

private fun RewriteHomeCatalogState.toScreenState(
    playbackCheckpoints: List<com.sluggyard.tv.ui.rewrite.data.RewritePlaybackCheckpoint>,
): RewriteHomeState {
    val catalogRows = rows.mapNotNull { row ->
        val content = row.loadState as? RewriteCatalogLoadState.Content ?: return@mapNotNull null
        RewriteHomeRow(id = "${row.key.addonId}:${row.key.catalogId}", title = row.title, posters = content.posters)
    }
    val heroPoster = catalogRows.firstOrNull()?.posters?.firstOrNull()
    return RewriteHomeState(
        hero = heroPoster?.let { poster ->
            val type = poster.contentType ?: "movie"
            val addonId = poster.addonId ?: return@let null
            com.sluggyard.tv.ui.rewrite.home.RewriteHero(
                id = poster.id,
                title = poster.title,
                backdropUrl = poster.imageUrl,
                summary = "Browse details and choose a stream.",
                metadata = type.replaceFirstChar(Char::titlecase),
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
