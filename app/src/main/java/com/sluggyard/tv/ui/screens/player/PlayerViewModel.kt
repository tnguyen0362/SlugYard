package com.sluggyard.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.sluggyard.tv.core.debrid.DirectDebridResolver
import com.sluggyard.tv.core.debrid.DirectDebridStreamPreparer
import com.sluggyard.tv.core.player.ExternalPlaybackMetadata
import com.sluggyard.tv.core.player.ExternalPlaybackTracker
import com.sluggyard.tv.core.player.PlaybackLaunchRequest
import com.sluggyard.tv.core.player.RepositoryPlaybackProgressSink
import com.sluggyard.tv.core.player.SubtitleFileCache
import com.sluggyard.tv.core.player.SubtitleInput
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.domain.model.Subtitle
import com.sluggyard.tv.core.player.TrailerPlayerPool
import com.sluggyard.tv.core.streams.StreamBadgePresentation
import com.sluggyard.tv.core.tmdb.TmdbMetadataService
import com.sluggyard.tv.core.tmdb.TmdbService
import com.sluggyard.tv.core.torrent.TorrentService
import com.sluggyard.tv.core.torrent.TorrentSettings
import com.sluggyard.tv.data.local.AudioDelayRouteDataStore
import com.sluggyard.tv.data.local.BingeGroupCacheDataStore
import com.sluggyard.tv.data.local.DeviceLocalPlayerPreferences
import com.sluggyard.tv.data.local.LayoutPreferenceDataStore
import com.sluggyard.tv.data.local.PlayerSettingsDataStore
import com.sluggyard.tv.data.local.StreamBadgeSettingsDataStore
import com.sluggyard.tv.data.local.StreamLinkCacheDataStore
import com.sluggyard.tv.data.local.TmdbSettingsDataStore
import com.sluggyard.tv.data.local.TrackPreferenceDataStore
import com.sluggyard.tv.data.local.WatchedItemsPreferences
import com.sluggyard.tv.data.repository.OpenSubtitlesRepository
import com.sluggyard.tv.data.repository.ParentalGuideRepository
import com.sluggyard.tv.data.repository.PlaybackIssueReportRepository
import com.sluggyard.tv.data.repository.SkipIntroRepository
import com.sluggyard.tv.data.repository.TraktEpisodeMappingService
import com.sluggyard.tv.data.repository.TraktScrobbleService
import com.sluggyard.tv.domain.repository.AddonRepository
import com.sluggyard.tv.domain.repository.MetaRepository
import com.sluggyard.tv.domain.repository.StreamRepository
import com.sluggyard.tv.domain.repository.SubtitleRepository
import com.sluggyard.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val subtitleRepository: SubtitleRepository,
    private val openSubtitlesRepository: OpenSubtitlesRepository,
    private val parentalGuideRepository: ParentalGuideRepository,
    private val traktScrobbleService: TraktScrobbleService,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val trackPreferenceDataStore: TrackPreferenceDataStore,
    private val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    private val torrentService: TorrentService,
    private val torrentSettings: TorrentSettings,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val trailerPlayerPool: TrailerPlayerPool,
    private val directDebridResolver: DirectDebridResolver,
    private val directDebridStreamPreparer: DirectDebridStreamPreparer,
    private val streamBadgePresentation: StreamBadgePresentation,
    private val playbackIssueReportRepository: PlaybackIssueReportRepository,
    private val externalPlaybackTracker: ExternalPlaybackTracker,
    private val subtitleFileCache: SubtitleFileCache,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    init {
        // Yield the shared trailer codec so the full-screen player can grab the
        // hardware decoders without contention (avoids a black-screen start).
        trailerPlayerPool.yield()
    }

    private val launchRequest = PlaybackLaunchRequest.from(savedStateHandle)
    private val playbackProgressSink = RepositoryPlaybackProgressSink(watchProgressRepository)

    private val controller = PlayerRuntimeController(
        context = context,
        playbackProgressSink = playbackProgressSink,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        subtitleRepository = subtitleRepository,
        openSubtitlesRepository = openSubtitlesRepository,
        parentalGuideRepository = parentalGuideRepository,
        traktScrobbleService = traktScrobbleService,
        traktEpisodeMappingService = traktEpisodeMappingService,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        deviceLocalPlayerPreferences = deviceLocalPlayerPreferences,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
        bingeGroupCacheDataStore = bingeGroupCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        watchedItemsPreferences = watchedItemsPreferences,
        trackPreferenceDataStore = trackPreferenceDataStore,
        audioDelayRouteDataStore = audioDelayRouteDataStore,
        torrentService = torrentService,
        torrentSettings = torrentSettings,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        directDebridResolver = directDebridResolver,
        directDebridStreamPreparer = directDebridStreamPreparer,
        streamBadgePresentation = streamBadgePresentation,
        playbackIssueReportRepository = playbackIssueReportRepository,
        launchRequest = launchRequest,
        scope = viewModelScope
    )

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    val playbackTimeline: StateFlow<PlaybackTimelineState>
        get() = controller.playbackTimeline

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun stopAndRelease() {
        controller.stopAndRelease()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun attachMpvView(view: MpvPlayerSurfaceView?) {
        controller.attachMpvView(view)
    }

    fun pauseForLifecycle() {
        controller.pauseForLifecycle()
    }

    fun resumeForLifecycle() {
        controller.resumeForLifecycle()
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    fun enableSubtitleMode() {
        controller.enableSubtitleMode()
    }

    fun configureLaunch(source: ResolvedPlaybackSource) {
        controller.configureLaunch(source)
    }

    fun contentGenres(): String? = controller.effectiveContentGenres()

    fun contentLanguage(): String? = controller.contentLanguage

    fun setAddonSubtitles(subtitles: List<Subtitle>) {
        controller.setAddonSubtitles(subtitles)
    }

    fun consumePendingExitReason() {
        controller.consumePendingExitReason()
    }

    override fun onCleared() {
        controller.onCleared()
        // Let the trailer pool rebuild once we leave the player screen.
        trailerPlayerPool.reclaim()
        super.onCleared()
    }

    /**
     * Persist watch progress reported by an external player after "Open in External Player".
     * Relies on the controller's still-live content metadata (contentId, season, episode, ...).
     */
    fun saveExternalPlayerProgress(positionMs: Long, durationMs: Long?) {
        val effectiveDuration = durationMs ?: controller.playbackTimeline.value.duration
        controller.saveWatchProgressInternal(
            position = positionMs,
            duration = effectiveDuration
        )
    }

    /**
     * Hand the current stream off to an external player via the centralized tracker, which
     * owns progress saving independently of the PlayerScreen lifecycle.
     */
    fun launchInExternalPlayer(activityContext: Context, resumePositionMs: Long) {
        val url = controller.getCurrentStreamUrl()
        val cid = controller.contentId ?: return
        val metadata = ExternalPlaybackMetadata(
            contentId = cid,
            contentType = controller.contentType ?: "movie",
            contentName = controller.contentName ?: controller.title,
            poster = controller.poster,
            backdrop = controller.backdrop,
            logo = controller.logo,
            videoId = controller.currentVideoId ?: cid,
            season = controller.currentSeason,
            episode = controller.currentEpisode,
            episodeTitle = controller.currentEpisodeTitle,
            year = controller.year
        )

        // Forward already-loaded addon subtitles when the user wants them.
        val subtitleInputs = if (controller.uiState.value.subtitleStyle.preferredLanguage.trim().lowercase() != "none") {
            val addonSubtitles = controller.uiState.value.addonSubtitles
            if (addonSubtitles.isNotEmpty()) {
                addonSubtitles.map {
                    SubtitleInput(
                        url = it.url,
                        name = "${it.getDisplayLanguage()} - ${it.addonName}",
                        lang = it.lang
                    )
                }
            } else null
        } else null

        viewModelScope.launch {
            val cachedSubtitles = subtitleInputs?.let { subtitleFileCache.cacheSubtitles(it) }
            externalPlaybackTracker.launchPlayer(
                metadata = metadata,
                url = url,
                title = metadata.buildPlayerTitle(),
                headers = controller.getCurrentHeaders(),
                resumePositionMs = resumePositionMs,
                subtitles = cachedSubtitles,
                context = activityContext
            )
        }
    }
}
