@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import com.sluggyard.tv.core.player.BitrateAwareLoadControl
import com.sluggyard.tv.core.player.LastPlaybackDiagnostics
import com.sluggyard.tv.core.debrid.DirectDebridResolver
import com.sluggyard.tv.core.debrid.DirectDebridStreamPreparer
import com.sluggyard.tv.core.torrent.TorrentService
import com.sluggyard.tv.data.local.AutoSkipSegmentType
import com.sluggyard.tv.data.local.InternalPlayerEngine
import com.sluggyard.tv.data.local.MpvHardwareDecodeMode
import com.sluggyard.tv.data.local.NextEpisodeThresholdMode
import com.sluggyard.tv.data.local.AudioDelayRouteDataStore
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.PlayerSettingsDataStore
import com.sluggyard.tv.data.local.DeviceLocalPlayerPreferences
import com.sluggyard.tv.data.local.StreamLinkCacheDataStore
import com.sluggyard.tv.data.local.StreamBadgeSettingsDataStore
import com.sluggyard.tv.data.local.BingeGroupCacheDataStore
import com.sluggyard.tv.data.local.StreamAutoPlayMode
import com.sluggyard.tv.data.repository.ParentalGuideRepository
import com.sluggyard.tv.data.repository.PlaybackIssueErrorInput
import com.sluggyard.tv.data.repository.PlaybackIssueReportRepository
import com.sluggyard.tv.data.repository.SkipIntroRepository
import com.sluggyard.tv.data.repository.SkipInterval
import com.sluggyard.tv.data.repository.EpisodeMappingEntry
import com.sluggyard.tv.data.repository.TraktEpisodeMappingService
import com.sluggyard.tv.data.repository.TraktScrobbleItem
import com.sluggyard.tv.data.repository.TraktScrobbleService
import com.sluggyard.tv.domain.model.Video
import com.sluggyard.tv.domain.model.Subtitle
import com.sluggyard.tv.domain.model.WatchProgress
import com.sluggyard.tv.domain.repository.AddonRepository
import com.sluggyard.tv.domain.repository.MetaRepository
import com.sluggyard.tv.domain.repository.StreamRepository
import com.sluggyard.tv.domain.repository.WatchProgressRepository
import com.sluggyard.tv.data.repository.extractYear
import com.sluggyard.tv.data.repository.parseContentIds
import com.sluggyard.tv.data.repository.toTraktIds
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.sluggyard.tv.core.player.PlaybackLaunchRequest
import com.sluggyard.tv.core.player.PlaybackProgressSink
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class PlayerRuntimeController(
    internal val context: Context,
    internal val playbackProgressSink: PlaybackProgressSink,
    internal val metaRepository: MetaRepository,
    internal val streamRepository: StreamRepository,
    internal val addonRepository: AddonRepository,
    internal val subtitleRepository: com.sluggyard.tv.domain.repository.SubtitleRepository,
    internal val openSubtitlesRepository: com.sluggyard.tv.data.repository.OpenSubtitlesRepository,
    internal val parentalGuideRepository: ParentalGuideRepository,
    internal val traktScrobbleService: TraktScrobbleService,
    internal val traktEpisodeMappingService: TraktEpisodeMappingService,
    internal val skipIntroRepository: SkipIntroRepository,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val deviceLocalPlayerPreferences: DeviceLocalPlayerPreferences,
    internal val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    internal val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    internal val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    internal val layoutPreferenceDataStore: com.sluggyard.tv.data.local.LayoutPreferenceDataStore,
    internal val watchedItemsPreferences: com.sluggyard.tv.data.local.WatchedItemsPreferences,
    internal val trackPreferenceDataStore: com.sluggyard.tv.data.local.TrackPreferenceDataStore,
    internal val releaseTrackMemoryDataStore: com.sluggyard.tv.data.local.ReleaseTrackMemoryDataStore,
    internal val audioDelayRouteDataStore: AudioDelayRouteDataStore,
    internal val torrentService: TorrentService,
    internal val torrentSettings: com.sluggyard.tv.core.torrent.TorrentSettings,
    internal val tmdbService: com.sluggyard.tv.core.tmdb.TmdbService,
    internal val tmdbMetadataService: com.sluggyard.tv.core.tmdb.TmdbMetadataService,
    internal val tmdbSettingsDataStore: com.sluggyard.tv.data.local.TmdbSettingsDataStore,
    internal val directDebridResolver: DirectDebridResolver,
    internal val directDebridStreamPreparer: DirectDebridStreamPreparer,
    internal val streamBadgePresentation: com.sluggyard.tv.core.streams.StreamBadgePresentation,
    internal val playbackIssueReportRepository: PlaybackIssueReportRepository,
    internal val launchRequest: PlaybackLaunchRequest,
    internal val scope: CoroutineScope
) {

    companion object {
        internal const val TAG = "PlayerViewModel"
        internal const val SWITCH_TRACE_TAG = "SwitchTrace"
        internal const val SWITCH_TRACE_ENABLED = false
        internal const val TRACK_FRAME_RATE_GRACE_MS = 1500L
        internal const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        // Stall watchdog: re-seeks past the buffered edge if bufferedPosition stops
        // advancing during STATE_BUFFERING. Fires before OkHttp's readTimeout.
        internal const val STALL_WATCHDOG_THRESHOLD_MS = 15_000L
        internal const val STALL_WATCHDOG_POLL_INTERVAL_MS = 1_000L
        internal const val STALL_WATCHDOG_SKIP_PAST_BUFFERED_MS = 250L
        internal const val MAX_TIMEOUT_RECOVERY_ATTEMPTS = 2
        internal const val ADDON_SUBTITLE_TRACK_ID_PREFIX = "addon-sub:"
        internal const val LONG_PAUSE_THRESHOLD_MS = 300_000L // 5 minutes
    }

    internal data class PendingAudioSelection(
        val language: String?,
        val name: String?,
        val streamUrl: String
    )

    internal data class RememberedTrackSelection(
        val language: String?,
        val name: String?,
        val trackId: String? = null,
        val indexHint: Int? = null,
        val languageIndexHint: Int? = null,
        val isForcedHint: Boolean? = null,
        /**
         * Captured role of the saved subtitle track: `true` when it was a signs-and-songs
         * track, `false` when it was a dialogue/full subtitle, `null` when unknown. Used by
         * engine-switch restore so a saved English dialogue track is preferred over an
         * English signs-and-songs track even when MPV reorders tracks and the raw
         * [indexHint] would otherwise land on the wrong role.
         */
        val isSignsAndSongsHint: Boolean? = null
    )

    internal sealed class RememberedSubtitleSelection {
        data object Disabled : RememberedSubtitleSelection()
        data class Internal(
            val track: RememberedTrackSelection
        ) : RememberedSubtitleSelection()
        data class Addon(
            val id: String,
            val url: String,
            val language: String,
            val addonName: String
        ) : RememberedSubtitleSelection()
    }

    internal data class TrackPreference(
        val audio: RememberedTrackSelection? = null,
        val subtitle: RememberedSubtitleSelection? = null
    )

    internal data class PendingEngineSwitchTrackPreference(
        val streamUrl: String,
        val preference: TrackPreference,
        val sourceEngine: InternalPlayerEngine
    )

    internal data class ExplicitSubtitleSelectionForEngineSwitch(
        val streamUrl: String,
        val selection: RememberedSubtitleSelection
    )

    internal val navigationArgs = launchRequest
    internal var initialStreamUrl: String = navigationArgs.streamUrl
    internal val title: String = navigationArgs.title
    internal val streamName: String? = navigationArgs.streamName
    internal val year: String? = navigationArgs.year
    internal val headersJson: String? = navigationArgs.headersJson
    internal var contentId: String? = navigationArgs.contentId
    internal var contentType: String? = navigationArgs.contentType
    /** Series/catalog id for meta + IntroDB when [contentId] is episode-shaped. */
    internal var parentId: String? = navigationArgs.parentId
    internal var parentType: String? = navigationArgs.parentType
    internal val contentName: String? = navigationArgs.contentName
    internal val poster: String? = navigationArgs.poster
    internal val backdrop: String? = navigationArgs.backdrop
    internal val logo: String? = navigationArgs.logo
    internal val videoId: String? = navigationArgs.videoId
    internal val initialSeason: Int? = navigationArgs.initialSeason
    internal val initialEpisode: Int? = navigationArgs.initialEpisode
    internal val initialEpisodeTitle: String? = navigationArgs.initialEpisodeTitle
    internal val launchStartedAtElapsedMs: Long? = navigationArgs.launchStartedAtMs
    internal val rememberedAudioLanguage: String? = navigationArgs.rememberedAudioLanguage
    internal val rememberedAudioName: String? = navigationArgs.rememberedAudioName
    internal val mediaSourceFactory = PlayerMediaSourceFactory(context.applicationContext)

    internal var currentVideoHash: String? = navigationArgs.videoHash
    internal var currentVideoSize: Long? = navigationArgs.videoSize
    internal var currentFilename: String? = navigationArgs.filename
        ?: initialStreamUrl.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    internal var currentAddonName: String? = navigationArgs.addonName
    internal var currentAddonLogo: String? = navigationArgs.addonLogo
    internal var contentGenres: String? = navigationArgs.contentGenres
    internal var currentStreamDescription: String? = navigationArgs.streamDescription
    internal var contentLanguage: String? = navigationArgs.contentLanguage
    internal var currentVideoCodec: String? = null
    internal var currentVideoWidth: Int? = null
    internal var currentVideoHeight: Int? = null
    internal var currentVideoBitrate: Int? = null
    internal var currentStreamUrl: String
    internal var currentStreamResponseHeaders: Map<String, String> = emptyMap()
    internal var currentStreamMimeType: String?
    internal var currentHeaders: Map<String, String>

    init {
        // Normalize the launch URL + headers once, up front: parse the JSON
        // header blob, sanitize it, then fold any userInfo auth into the URL.
        val parsedHeaders = PlayerMediaSourceFactory.parseHeaders(headersJson)
        val sanitizedHeaders = PlayerMediaSourceFactory.sanitizeHeaders(parsedHeaders)
        val (cleanUrl, mergedHeaders) = PlayerMediaSourceFactory.extractUserInfoAuth(initialStreamUrl, sanitizedHeaders)
        currentStreamUrl = cleanUrl
        currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
            url = cleanUrl,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders
        )
        currentHeaders = mergedHeaders
    }

    fun getCurrentStreamUrl(): String = currentStreamUrl
    fun getCurrentHeaders(): Map<String, String> = currentHeaders

    fun stopAndRelease() {
        releasePlayer()
    }

    internal var currentVideoId: String? = videoId
    internal var currentSeason: Int? = initialSeason
    internal var currentEpisode: Int? = initialEpisode
    @Volatile internal var isTraktCwActive: Boolean = false
    internal var currentEpisodeTitle: String? = initialEpisodeTitle

    internal val _uiState = MutableStateFlow(
        PlayerUiState(
            title = title,
            contentName = contentName,
            currentStreamName = streamName,
            currentStreamUrl = currentStreamUrl,
            currentStreamInfoHash = navigationArgs.infoHash,
            currentStreamFileIdx = navigationArgs.fileIdx,
            currentStreamAddonName = navigationArgs.addonName,
            releaseYear = year,
            contentType = contentType,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            showLoadingOverlay = true,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            currentEpisodeTitle = currentEpisodeTitle
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        // Track play/pause transitions for downstream observers. The body is
        // intentionally empty — the distinctUntilChanged flow is the signal.
        scope.launch {
            _uiState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { isPlaying ->
                }
        }
    }

    internal fun consumePendingExitReason() {
        _uiState.update { it.copy(pendingExitReason = null) }
    }

    internal val _playbackTimeline = MutableStateFlow(PlaybackTimelineState())
    val playbackTimeline: StateFlow<PlaybackTimelineState> = _playbackTimeline.asStateFlow()

    internal fun updatePlaybackTimeline(
        currentPosition: Long = _playbackTimeline.value.currentPosition,
        duration: Long = _playbackTimeline.value.duration,
        bufferedPosition: Long = _playbackTimeline.value.bufferedPosition
    ) {
        // Clamp all three components to non-negative before publishing, so a
        // transient negative from a seek or reset never reaches the UI.
        _playbackTimeline.update { snapshot ->
            snapshot.copy(
                currentPosition = currentPosition.coerceAtLeast(0L),
                duration = duration.coerceAtLeast(0L),
                bufferedPosition = bufferedPosition.coerceAtLeast(0L)
            )
        }
    }

    internal fun resetPlaybackTimeline() {
        _playbackTimeline.value = PlaybackTimelineState()
    }

    internal var _exoPlayer: ExoPlayer? = null
    val exoPlayer: ExoPlayer?
        get() = _exoPlayer
    internal var _loadControl: DefaultLoadControl? = null
    internal var playbackSpeedAwareAudioSink: PlaybackSpeedAwareAudioSink? = null

    internal var progressJob: Job? = null
    internal var vodTelemetryJob: Job? = null
    internal var firstFrameWatchdogJob: Job? = null
    internal var stallWatchdogJob: Job? = null
    internal var hideControlsJob: Job? = null
    internal var hideSeekOverlayJob: Job? = null
    internal var watchProgressSaveJob: Job? = null
    internal var seekProgressSyncJob: Job? = null
    internal var frameRateProbeJob: Job? = null
    internal var frameRateProbeToken: Long = 0L
    internal var hideAspectRatioIndicatorJob: Job? = null
    internal var hideStreamSourceIndicatorJob: Job? = null
    internal var hidePlayerEngineSwitchInfoJob: Job? = null
    internal var hideSubtitleDelayOverlayJob: Job? = null
    internal var subtitleAutoSyncLoadJob: Job? = null
    internal var nextEpisodeAutoPlayJob: Job? = null
    internal var debridResolveJob: Job? = null
    internal var stillWatchingPromptJob: Job? = null
    internal var startupLoadingReportJob: Job? = null
    internal var sourceStreamsJob: Job? = null
    internal var sourceBadgeJob: Job? = null
    internal var sourceBadgedAddonNames: Set<String> = emptySet()
    internal var sourceStreamsScope: kotlinx.coroutines.CoroutineScope? = null
    internal var episodeStreamsScope: kotlinx.coroutines.CoroutineScope? = null
    internal var episodeBadgeJob: Job? = null
    internal var sourceChipErrorDismissJob: Job? = null
    internal var sourceStreamsCacheRequestKey: String? = null
    internal var sourceStreamsFetchCompleted: Boolean = false
    internal var hostActivityRef: WeakReference<Activity>? = null
    internal var initialPlaybackStarted: Boolean = false
    internal var lastPlaybackDiagnosticsForReport: LastPlaybackDiagnostics =
        LastPlaybackDiagnostics.EMPTY
    internal var lastPlaybackIssueError: PlaybackIssueErrorInput? = null
    internal val playbackIssueReportRequestVersion = AtomicLong(0L)
    internal val playbackAnalyticsDiagnostics = PlayerPlaybackAnalyticsDiagnostics()
    internal val loadingDiagnosticEvents: ArrayDeque<PlayerLoadingDiagnosticEvent> = ArrayDeque()
    internal val loadingDiagnosticRawEventLines: ArrayDeque<String> = ArrayDeque()
    internal val pendingPlaybackRawEventLines: ArrayDeque<String> = ArrayDeque()
    internal var loadingDiagnosticsStartedAtMs: Long = 0L
    internal var currentLoadingPhase: String = "idle"
    internal var currentLoadingPhaseStartedAtMs: Long = 0L
    internal var currentLoadingMessageForReport: String? = null
    internal var currentLoadingProgressForReport: Float? = null
    internal var lastLoadingDiagnosticSignature: String = ""
    internal var startupPhaseSequence: Int = 0

    internal var lastSavedPosition: Long = 0L
    internal val saveThresholdMs = 5000L
    internal var hasMarkedCurrentEpisodeCompleted: Boolean = false
    internal var lastKnownDuration: Long = 0L

    internal var playbackStartedForParentalGuide = false
    internal var hasRenderedFirstFrame = false
    internal var shouldEnforceAutoplayOnFirstReady = true

    internal var rebufferCount: Int = 0
    internal var rebufferTotalMs: Long = 0L
    internal var rebufferStartedAtMs: Long = 0L
    /** Back buffer (ms) currently in force, after the first-frame DV7/low-RAM resolution. */
    internal var effectiveBackBufferDurationMs: Int = 0
    /** Custom LoadControl for this playback (null when using stock); used to resolve the back buffer at first frame. */
    internal var currentBitrateAwareLoadControl: BitrateAwareLoadControl? = null
    /** Back buffer (ms) the user configured, captured at build to restore once DV7 status is known. */
    internal var configuredBackBufferMs: Int = 0
    internal var metaVideos: List<Video> = emptyList()
    internal var metaGenres: List<String> = emptyList()
    internal var metaCountry: String? = null
    internal var nextEpisodeVideo: Video? = null
    internal var userPausedManually = false
    internal var pauseStartTimeMs: Long = 0L

    internal var isInBackground: Boolean = false
    internal var pendingBackgroundCrashRecovery: Boolean = false
    internal var backgroundCrashSavedPositionMs: Long = 0L

    internal var skipIntervals: List<SkipInterval> = emptyList()
    internal var skipIntroEnabled: Boolean = true
    internal var parentalGuideEnabled: Boolean = false
    internal var autoSkipSegmentTypes: Set<AutoSkipSegmentType> = emptySet()
    internal var playerSettingsInitialized: Boolean = false
    internal var skipIntroFetchedKey: String? = null
    internal var skipIntroInFlightKey: String? = null
    internal val autoSkippedIntervalKeys: MutableSet<String> = mutableSetOf()
    internal var lastActiveSkipType: String? = null
    internal var autoSubtitleSelected: Boolean = false
    internal var subtitleMode: Boolean = false
    internal var addonSubtitles: List<Subtitle> = emptyList()
    internal var lastSubtitlePreferredLanguage: String? = null
    internal var lastSubtitleSecondaryLanguage: String? = null
    internal var lastUseForcedSubtitles: Boolean? = null
    internal var pendingAddonSubtitleLanguage: String? = null
    internal var pendingAddonSubtitleTrackId: String? = null
    internal var pendingAudioSelectionAfterSubtitleRefresh: PendingAudioSelection? = null
    internal var rememberedTrackPreference: TrackPreference? = null
    internal var persistedTrackPreference: TrackPreference? = null
    internal var pendingEngineSwitchTrackPreference: PendingEngineSwitchTrackPreference? = null
    internal var explicitSubtitleSelectionForEngineSwitch: ExplicitSubtitleSelectionForEngineSwitch? = null
    internal var effectiveSubtitleSelectionForEngineSwitch: ExplicitSubtitleSelectionForEngineSwitch? = null
    internal var switchTraceSessionId: Long = 0L
    internal var switchTraceSequence: Long = 0L
    internal var subtitleDisabledByPersistedPreference: Boolean = false
    internal var subtitleAddonRestoredByPersistedPreference: Boolean = false
    internal var pendingRestoredAddonSubtitle: com.sluggyard.tv.domain.model.Subtitle? = null
    internal var attachedAddonSubtitleKeys: Set<String> = emptySet()
    internal var hasScannedTextTracksOnce: Boolean = false
    internal var streamReuseLastLinkEnabled: Boolean = false
    internal var autoSwitchInternalPlayerOnErrorEnabled: Boolean = false
    internal var startupEngineFailoverTriggered: Boolean = false
    internal var postFirstFrameEngineFailoverTriggered: Boolean = false
    internal var runtimeInternalPlayerEngineOverride: InternalPlayerEngine? = null
    internal var resolvedAutoPlayerEngine: InternalPlayerEngine? = null
    internal var currentInternalPlayerEngine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER
    internal var streamAutoPlayModeSetting: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL
    internal var streamAutoPlayNextEpisodeEnabledSetting: Boolean = false
    internal var streamAutoPlayPreferBingeGroupForNextEpisodeSetting: Boolean = false
    internal var nextEpisodeThresholdModeSetting: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    internal var nextEpisodeThresholdPercentSetting: Float = PlayerNextEpisodeRules.MIN_THRESHOLD_PERCENT
    internal var nextEpisodeThresholdMinutesBeforeEndSetting: Float = 2f
    internal var stillWatchingEnabledSetting: Boolean = false
    internal var stillWatchingEpisodeThresholdSetting: Int =
        PlayerSettings.DEFAULT_STILL_WATCHING_EPISODE_THRESHOLD
    internal var mpvHardwareDecodeModeSetting: MpvHardwareDecodeMode = MpvHardwareDecodeMode.AUTO_SAFE
    internal var mpvPreferredAudioLanguages: List<String> = emptyList()
    internal var currentStreamBingeGroup: String? = navigationArgs.bingeGroup
    internal var hasAppliedRememberedAudioSelection: Boolean = false
    internal var hasInitializedAudioAmplificationForSession: Boolean = false
    internal var hasInitializedCenterMixForSession: Boolean = false
    internal var rememberAudioDelayPerDeviceEnabled: Boolean = false
    internal var currentAudioOutputRoute: AudioOutputRoute? = null
    internal var audioOutputRouteCallback: AudioDeviceCallback? = null

    internal var lastBufferLogTimeMs: Long = 0L
    internal var pendingSeekFlush: Boolean = false
    internal var suppressBufferingUiForSeek: Boolean = false
    internal var isScrubbingModeActive: Boolean = false
    internal var seekBufferingUiJob: Job? = null
    internal var seekBufferingUiDeferred: Boolean = false
    internal val seekBufferingUiDelayMs = 1000L

    internal var lastVodTelemetryRefreshTimeMs: Long = 0L
    internal var cachedVodCacheLogState: String = "vod=warming"
    internal var bufferLogsEnabled: Boolean = false
    internal var lastProgressUiUpdateUptimeMs: Long = 0L
    internal var lastSkipIntervalEvaluationUptimeMs: Long = 0L
    internal var lastNextEpisodeEvaluationUptimeMs: Long = 0L
    internal var bufferLogJob: Job? = null
    internal val gainAudioProcessor = GainAudioProcessor()
    internal var loudnessEnhancer: LoudnessEnhancer? = null
    internal var trackSelector: DefaultTrackSelector? = null
    internal var currentMediaSession: MediaSession? = null
    internal var ffmpegAudioRenderer: FfmpegAudioRenderer? = null
    internal var mpvView: MpvPlayerSurfaceView? = null
    internal var mpvInitializationInProgress: Boolean = false
    internal var mpvTrackRefreshJob: Job? = null
    internal var mpvTrackRefreshInProgress: Boolean = false
    /** MPV exposes tracks asynchronously; poll briefly during startup, never for the whole film. */
    internal var mpvTrackDiscoveryDeadlineElapsedMs: Long = 0L
    /** When the current MPV discovery window started (elapsedRealtime). */
    internal var mpvTrackDiscoveryStartedElapsedMs: Long = 0L
    internal var pendingMpvHardRestartOnNextAttach: Boolean = false
    internal var delayMpvResumeSeekUntilVideoTrack: Boolean = false
    internal var mpvDelayStartAfterAfrSwitch: Boolean = false
    internal var pauseOverlayJob: Job? = null
    internal val pauseOverlayDelayMs = 5000L
    internal val seekProgressSyncDebounceMs = 700L
    internal val audioDelayUs = AtomicLong(0L)
    internal val subtitleDelayUs = AtomicLong(0L)
    internal var pendingPreviewSeekPosition: Long? = null
    internal var pendingResumeProgress: WatchProgress? = null
    internal var hasRetriedCurrentStreamAfter416: Boolean = false
    @Volatile internal var isReleasingPlayer: Boolean = false
    internal var cachedDecoderPriority: Int = 1
    internal var hasTriedAudioPcmFallback: Boolean = false
    internal var pendingAudioPcmFallbackRebuild: Boolean = false
    internal var hasTriedDv7HevcFallback: Boolean = false
    internal var forceDv7ToHevc: Boolean = false
    internal var startupRetryCount: Int = 0
    internal var hasRetriedCurrentStreamAfterUnexpectedNpe: Boolean = false
    internal var hasRetriedCurrentStreamAfterMediaPeriodHolderCrash: Boolean = false
    /** Prevent a decoder-reclaim retry loop while allowing a different stream to recover. */
    internal var decoderResourcesReclaimedRecoveryUrl: String? = null
    /** Invalidates deferred recovery work when the user changes stream or engine. */
    internal var playbackRecoveryGeneration: Long = 0L
    internal var deferredPlayerRecoveryJob: Job? = null
    internal var timeoutRecoveryAttempts: Int = 0
    internal var errorRetryCount: Int = 0
    internal var consecutiveAutoPlayCount: Int = 0
    internal var errorRetryJob: Job? = null
    internal var stableProgressResetJob: Job? = null
    @Volatile internal var currentPlayerSettingsForReport: PlayerSettings = PlayerSettings()

    internal val dv7ToHevcForcedStreamUrls: MutableSet<String> = mutableSetOf()
    // Streams where manual Convert-to-DV8.1 mode 2 failed to play, so the next
    // attempt is forced to libdovi mode 1 before falling back to HDR10 base layer.
    internal val dv7Mode1ForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1SoftwarePreferredStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1TrackSelectionBypassStreamUrls: MutableSet<String> = mutableSetOf()
    internal val safeAudioForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val audioDisabledForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal var isMapDv7ToHevcActiveForCurrentPlayback: Boolean = false
    internal var isManualDv81Mode2ActiveForCurrentPlayback: Boolean = false
    internal var isExperimentalDv7ToDv81ActiveForCurrentPlayback: Boolean = false
    internal var isVc1SoftwareFallbackActiveForCurrentPlayback: Boolean = false
    internal var isVc1TrackSelectionBypassActiveForCurrentPlayback: Boolean = false
    internal var isSafeAudioModeActiveForCurrentPlayback: Boolean = false
    internal var isAudioDisabledForCurrentPlayback: Boolean = false
    internal var hasAttemptedDv7ToDv81ForCurrentPlayback: Boolean = false
    internal var dv7ToDv81BridgeVersionForCurrentPlayback: String? = null
    internal var dv7ToDv81LastProbeReasonForCurrentPlayback: String? = null

    internal var playerInitializationStartedAtMs: Long = 0L
    internal var pendingSeekTelemetryRequestedAtMs: Long = 0L
    internal var pendingSeekTelemetryTargetMs: Long = -1L
    internal var pendingSeekTelemetryReadyAtMs: Long = 0L
    internal var pendingSeekTelemetryReadyLatencyMs: Long = -1L
    internal var pendingSeekTelemetryAwaitingFirstFrame: Boolean = false
    internal var pendingSeekTelemetryReadyAssumed: Boolean = false

    internal var currentScrobbleItem: TraktScrobbleItem? = null
    internal var currentTraktEpisodeMapping: EpisodeMappingEntry? = null
    internal var currentTraktEpisodeMappingKey: String? = null
    internal var hasSentScrobbleStartForCurrentItem: Boolean = false
    internal var hasRequestedScrobbleStartForCurrentItem: Boolean = false
    internal var scrobbleStartRequestGeneration: Long = 0L
    internal var playbackPreparationJob: Job? = null
    internal var traktMappingJob: Job? = null
    internal var hasSentCompletionScrobbleForCurrentItem: Boolean = false

    internal var requestedUseLibassByUser: Boolean = false
    internal var libassPipelineOverrideForCurrentStream: Boolean? = null
    internal var activePlayerUsesLibass: Boolean = false
    internal var libassPipelineSwitchInFlight: Boolean = false
    internal var hasDetectedAssSsaTrackForCurrentStream: Boolean = false
    internal var libassPipelineDecisionStreamUrl: String? = null
    internal var torrentStreamJob: Job? = null
    internal var torrentStateObserverJob: Job? = null
    internal var isTorrentStream: Boolean = navigationArgs.infoHash != null && !initialStreamUrl.startsWith("http")
    internal var currentInfoHash: String? = navigationArgs.infoHash
    internal var currentFileIdx: Int? = navigationArgs.fileIdx
    /** Debounce TRACK_MEMORY DataStore writes across MPV/Exo track scan churn. */
    internal var lastRememberedReleaseTracksFingerprint: String? = null
    internal var currentTorrentSources: List<String>? =
        navigationArgs.sourcesJson?.let { raw ->
            runCatching {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { s -> s.isNotEmpty() }
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    internal var currentStreamHasVideoTrack: Boolean = false
    internal var currentVideoTrackIsLikelyVc1: Boolean = false
    internal var currentVideoTrackMimeType: String? = null
    internal var currentVideoTrackCodecs: String? = null
    internal var currentVideoTrackWidth: Int = 0
    internal var currentVideoTrackHeight: Int = 0
    internal var currentVideoTrackBitrate: Int = -1
    internal var currentVideoTrackColorTransfer: Int? = null
    internal var currentVideoTrackSelected: Boolean = false
    internal var currentVideoTrackBestSupport: Int = C.FORMAT_UNSUPPORTED_TYPE
    internal var lastLoggedVideoTrackSignature: String? = null

    internal var episodeStreamsJob: Job? = null
    internal var episodeStreamsCacheRequestKey: String? = null
    internal val streamCacheKey: String?
        get() {
            val type = contentType?.lowercase()
            val vid = currentVideoId
            return if (type.isNullOrBlank() || vid.isNullOrBlank()) null else "$type|$vid"
        }

    init {
        // IMPORTANT: saved watch progress is NOT loaded here. It is loaded inside
        // preparePlaybackBeforeStart() via loadSavedProgressSuspend(). Loading it
        // in this init block used a fire-and-forget coroutine that raced against
        // initializePlayer(); when ExoPlayer's STATE_READY fired before the DB
        // read finished, the resume seek was silently dropped.
        observeSubtitleSettings()
        fetchMetaDetailsForCurrentContent()
        observeBlurUnwatchedEpisodes()
        observeEpisodeWatchProgress()
        observeTorrentSettings()
        observeStreamBadgeSettings()
        observeDeviceLocalAspectMode()
        scope.launch { isTraktCwActive = playbackProgressSink.isTraktProgressActive() }
    }

    private fun observeTorrentSettings() {
        scope.launch {
            torrentSettings.settings.collect { settings ->
                _uiState.update { it.copy(hideTorrentStats = settings.hideTorrentStats) }
            }
        }
    }

    private fun observeStreamBadgeSettings() {
        scope.launch {
            streamBadgeSettingsDataStore.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        showFileSizeBadges = settings.showFileSizeBadges,
                        showAddonLogo = settings.showAddonLogo,
                        streamBadgePlacement = settings.badgePlacement
                    )
                }
            }
        }
    }

    fun onCleared() {
        releasePlayer()
        stopTorrentStream()
        startupLoadingReportJob?.cancel()
        vodTelemetryJob?.cancel()
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
        sourceStreamsScope?.cancel()
        sourceStreamsScope = null
        episodeStreamsScope?.cancel()
        episodeStreamsScope = null
    }

    // --- HELPER METHODS MOVED INSIDE THE CLASS ---



    internal fun refreshScrobbleItem() {
        val rawContentId = contentId ?: return
        val parsedIds = parseContentIds(rawContentId)
        val ids = toTraktIds(parsedIds)
        val parsedYear = extractYear(year)
        val normalizedType = contentType?.lowercase()

        val isEpisode = normalizedType in listOf("series", "tv") &&
                currentSeason != null && currentEpisode != null

        currentScrobbleItem = if (isEpisode) {
            TraktScrobbleItem.Episode(
                showTitle = contentName ?: title,
                showYear = parsedYear,
                showIds = ids,
                season = currentSeason ?: return,
                number = currentEpisode ?: return,
                episodeTitle = currentEpisodeTitle
            )
        } else {
            TraktScrobbleItem.Movie(
                title = contentName ?: title,
                year = parsedYear,
                ids = ids
            )
        }
        hasSentScrobbleStartForCurrentItem = false
        hasSentCompletionScrobbleForCurrentItem = false
    }
}

internal fun PlayerRuntimeController.beginSwitchTraceSession(
    reason: String,
    targetEngine: InternalPlayerEngine?
) {
    switchTraceSessionId = System.currentTimeMillis()
    switchTraceSequence = 0L
    logSwitchTrace(
        stage = "session-begin",
        message = "reason=$reason sourceEngine=$currentInternalPlayerEngine targetEngine=$targetEngine"
    )
}

internal fun PlayerRuntimeController.logSwitchTrace(
    stage: String,
    message: String
) {
    if (!PlayerRuntimeController.SWITCH_TRACE_ENABLED) return
    // Lazily start a session if a trace fires before beginSwitchTraceSession().
    if (switchTraceSessionId == 0L) {
        switchTraceSessionId = System.currentTimeMillis()
        switchTraceSequence = 0L
    }
    val sequence = ++switchTraceSequence
    val streamToken = currentStreamUrl.hashCode().toUInt().toString(16)
    Log.w(
        PlayerRuntimeController.SWITCH_TRACE_TAG,
        "sid=$switchTraceSessionId seq=$sequence stage=$stage engine=$currentInternalPlayerEngine streamToken=$streamToken $message"
    )
}
