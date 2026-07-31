@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import com.sluggyard.tv.R
import com.sluggyard.tv.core.player.BitrateAwareLoadControl
import com.sluggyard.tv.core.player.DolbyVisionBaseLayerPolicy
import com.sluggyard.tv.core.player.DolbyVisionCodecFallback
import com.sluggyard.tv.core.player.DolbyVisionConversionConfig
import com.sluggyard.tv.core.player.DolbyVisionConversionStats
import com.sluggyard.tv.core.player.DolbyVisionExtractorsFactory
import com.sluggyard.tv.core.player.DoviBridge
import com.sluggyard.tv.core.player.LastPlaybackDiagnostics
import com.sluggyard.tv.core.player.StreamScoringEngine
import com.sluggyard.tv.data.local.AddonSubtitleStartupMode
import com.sluggyard.tv.data.local.AudioLanguageOption
import com.sluggyard.tv.data.local.AudioOutputChannels
import com.sluggyard.tv.data.local.Dv7HandlingMode
import com.sluggyard.tv.data.local.InternalPlayerEngine
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.sluggyard.tv.data.local.MemoryBudget
import com.sluggyard.tv.data.repository.PlaybackIssueErrorInput
import com.sluggyard.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import kotlin.math.min

// Subtitle providers are best-effort enrichment. Do not make video start wait
// for a slow addon or remote service; an unfinished request resumes after start.
private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 1_500L
private const val MPV_AFR_SETTLE_DELAY_MS = 2_000L
private const val PLAYER_RELEASE_TIMEOUT_MS = 3000L
private const val PLAYER_REBUILD_SETTLE_DELAY_MS = 120L
private const val ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS = 2_000
private const val ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS = 25_000_000L

/**
 * Snapshot of the subtitle state prepared before the player is built.
 * `fetchedSubtitles` is the full set visible to the UI; `attachedSubtitles`
 * is the subset wired into the ExoPlayer MediaItem as sidecar tracks.
 * `fetchCompleted` is false when the prefetch timed out or was skipped.
 */
internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

/**
 * Resolves and caches the MIME type for the current stream. Filename and URL
 * inference is only a fallback for progressive HTTP sources because a provider
 * can return a different representation after redirects.
 */
private suspend fun PlayerRuntimeController.resolveCurrentStreamMimeType(
    url: String,
    headers: Map<String, String>
) {
    val inferredMimeType = PlayerMediaSourceFactory.probeMimeType(
        url = url,
        headers = headers,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    currentStreamMimeType = inferredMimeType ?: currentStreamMimeType
    if (isProgressiveFileMime(currentStreamMimeType)) {
        val validation = validateProgressivePayloadBeforeExoStartupOrThrow(url = url, headers = headers)
        if (validation?.verdict == PayloadVerdict.Valid) {
            Log.i(
                PlayerRuntimeController.TAG,
                "Using validated progressive payload " +
                    "resolvedHost=${validation.resolvedUrl?.safeHost() ?: "unknown"} " +
                    "mime=${currentStreamMimeType ?: "unknown"}"
            )
            return
        }
    }
    val responseProbe = if (shouldProbeProgressivePayload(url, inferredMimeType)) {
        withTimeoutOrNull(StreamPayloadValidator.PROBE_TIMEOUT_MS) {
            PlayerPlaybackNetworking.probePlaybackResponse(url = url, headers = headers)
        }
    } else {
        null
    }
    val responseMimeType = responseProbe?.let { probe ->
        PlayerMediaSourceFactory.normalizeMimeType(probe.contentType)
            ?: PlayerMediaSourceFactory.sniffManifestMimeType(
                String(probe.initialBytes, Charsets.US_ASCII)
            )
    }
    currentStreamMimeType = responseMimeType ?: inferredMimeType ?: currentStreamMimeType
    responseProbe?.let { probe ->
        Log.d(
            PlayerRuntimeController.TAG,
            "Playback response mime probe host=${url.safeHost()} status=${probe.status} " +
                "contentType=${probe.contentType ?: "none"} finalHost=${probe.finalHost} " +
                "sniffedMime=${responseMimeType ?: "unknown"} bytes=${probe.initialBytes.size}"
        )
    }
    Log.d(
        PlayerRuntimeController.TAG,
        "Resolved stream mimeType=${currentStreamMimeType ?: "unknown"} host=${url.safeHost()}"
    )
}

/**
 * Phase 1 gate: probes a progressive HTTP(S) stream before ExoPlayer extractor startup and
 * throws [InvalidStreamPayloadException] when the response is clearly not a playable media
 * payload (the diagnosed Comet invalid-payload case). The throw is caught by
 * [handleInitializePlayerException] and routed into the existing Exo -> MPV dual-player
 * failover, so MPV and the fallback path are untouched.
 *
 * Adaptive manifests (HLS/DASH/SS) and non-HTTP schemes (torrent/local) are skipped — they
 * have their own load/sniff paths and must not pay the probe cost. Any probe failure returns
 * [PayloadVerdict.Indeterminate] and playback proceeds normally.
 */
private suspend fun PlayerRuntimeController.validateProgressivePayloadBeforeExoStartupOrThrow(
    url: String,
    headers: Map<String, String>
): ProgressivePayloadValidation? {
    mediaSourceFactory.progressiveRangeOverride = null
    mediaSourceFactory.progressiveUrlOverride = null
    val mime = currentStreamMimeType
    if (!shouldProbeProgressivePayload(url, mime)) return null
    val validation = StreamPayloadValidator.validateProgressiveHttpPayloadWithDetails(
        url = url,
        headers = headers,
        expectedMimeType = mime
    )
    when (val verdict = validation.verdict) {
        is PayloadVerdict.Invalid -> {
            Log.w(
                PlayerRuntimeController.TAG,
                "Stream payload rejected before Exo startup: reason=${verdict.reason} " +
                    "host=${url.safeHost()} mime=${mime ?: "unknown"}"
            )
            throw InvalidStreamPayloadException(verdict.reason)
        }
        is PayloadVerdict.Indeterminate -> {
            Log.d(
                PlayerRuntimeController.TAG,
                "Stream payload probe indeterminate: reason=${verdict.reason} host=${url.safeHost()}"
            )
        }
        PayloadVerdict.Valid -> {
            // The resolved endpoint is the original file. Do not carry the bounded probe's
            // fixed full-file Range into every later DataSpec; ExoPlayer must own seek ranges.
            mediaSourceFactory.progressiveRangeOverride = null
            mediaSourceFactory.progressiveUrlOverride = validation.resolvedUrl
            Log.d(
                PlayerRuntimeController.TAG,
                "Stream payload probe accepted host=${url.safeHost()} " +
                    "resolvedFile=${validation.resolvedUrl != null} " +
                    "resolvedHost=${validation.resolvedUrl?.safeHost() ?: "unknown"}"
            )
        }
    }
    return validation
}

private fun isProgressiveFileMime(mime: String?): Boolean = mime?.lowercase() in setOf(
    MimeTypes.VIDEO_MATROSKA,
    MimeTypes.VIDEO_WEBM,
    "audio/x-matroska",
    "video/mkv",
    "audio/mkv",
    "audio/webm"
)

private fun shouldProbeProgressivePayload(url: String, mime: String?): Boolean {
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return false
    // Skip adaptive manifests by URL path — HLS (.m3u8), DASH (.mpd), and Smooth
    // Streaming (/Manifest) have their own manifest sniffing / load path and must not be
    // rejected by a single byte-range probe. Matched case-insensitively on the path with
    // any query string ignored, in addition to the existing MIME gates below.
    if (isAdaptiveManifestUrlPath(url)) return false
    if (mime == null) return true
    val m = mime.lowercase()
    // Skip adaptive manifests — they have their own manifest sniffing / load path and must
    // not be rejected by a single byte-range probe.
    if (m == "application/vnd.apple.mpegurl" || m == "application/x-mpegurl" ||
        m == "application/m3u8" || m.contains("mpegurl") || m.contains("m3u8") ||
        m == "application/dash+xml" || m.contains("mpd") ||
        m == "application/vnd.ms-sstr+xml"
    ) return false
    return true
}

/**
 * Returns true when the URL's path (query ignored, case-insensitive) ends with a known
 * adaptive-manifest extension/marker: `.m3u8` (HLS), `.mpd` (DASH), or `/Manifest`
 * (Smooth Streaming). Used to skip the progressive payload probe for manifest URLs even
 * when the inferred MIME type is missing or generic.
 */
private fun isAdaptiveManifestUrlPath(url: String): Boolean {
    val path = runCatching { Uri.parse(url).path?.lowercase() }.getOrNull() ?: return false
    if (path.isEmpty()) return false
    return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith("/manifest")
}

/**
 * Tears down the existing ExoPlayer and associated resources before a rebuild.
 * Releases the media session, loudness enhancer, and the player itself, in that
 * order. Every step is wrapped in `runCatching` so a partially-released state
 * never blocks the rebuild.
 */
internal fun PlayerRuntimeController.disposeExoPlayerBeforeRebuild() {
    notifyAudioSessionUpdate(false)
    runCatching { currentMediaSession?.release() }
    currentMediaSession = null
    runCatching { loudnessEnhancer?.release() }
    loudnessEnhancer = null

    _exoPlayer?.let { player ->
        runCatching { player.playWhenReady = false }
        runCatching { player.pause() }
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.release() }
    }
    _exoPlayer = null
    playbackSpeedAwareAudioSink = null
}

/**
 * Builds and starts the player for the given stream URL.
 *
 * The engine is resolved from the override, the persisted setting, or AUTO
 * detection. ExoPlayer builds go through the full DV7 / libass / buffer / track
 * pipeline; MPV builds delegate to [initializeMpvPlayer]. All error paths
 * route through the retry/failover chain before surfacing a fatal error.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(
    url: String,
    headers: Map<String, String>,
    overrideInternalPlayerEngine: InternalPlayerEngine? = null,
    allowEngineFailover: Boolean = true,
    startPaused: Boolean = false
) {
    if (url.isEmpty()) {
        _uiState.update {
            it.copy(error = context.getString(R.string.player_error_no_stream_url), showLoadingOverlay = false)
        }
        return
    }

    // A direct stream/engine change wins over any queued error recovery.
    playbackRecoveryGeneration += 1L

    scope.launch {
        try {
            initializePlayerInternal(url, headers, overrideInternalPlayerEngine, allowEngineFailover, startPaused)
        } catch (e: Exception) {
            handleInitializePlayerException(e, allowEngineFailover)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun PlayerRuntimeController.initializePlayerInternal(
    url: String,
    headers: Map<String, String>,
    overrideInternalPlayerEngine: InternalPlayerEngine?,
    allowEngineFailover: Boolean,
    startPaused: Boolean
) {
    if (allowEngineFailover) {
        startupEngineFailoverTriggered = false
        postFirstFrameEngineFailoverTriggered = false
    }
    autoSubtitleSelected = false
    hasScannedTextTracksOnce = false
    lastPlaybackDiagnosticsForReport = LastPlaybackDiagnostics.EMPTY
    lastPlaybackIssueError = null
    playbackIssueReportRequestVersion.incrementAndGet()
    playbackAnalyticsDiagnostics.reset()
    _uiState.update {
        it.copy(
            playbackIssueReportStatus = PlaybackIssueReportStatus.Idle,
            playbackIssueReportId = null,
            playbackIssueReportError = null
        )
    }
    resetLoadingOverlayForNewStream()
    if (startPaused) {
        userPausedManually = true
        shouldEnforceAutoplayOnFirstReady = false
    }

    val applyPcmFallbackOnStartup = pendingAudioPcmFallbackRebuild
    val applyDv7FallbackOnStartup = forceDv7ToHevc
    if (!applyPcmFallbackOnStartup) hasTriedAudioPcmFallback = false
    hasTriedDv7HevcFallback = false
    forceDv7ToHevc = false
    mpvDelayStartAfterAfrSwitch = false
    playerInitializationStartedAtMs = System.currentTimeMillis()
    // Reset per playback; only the ExoPlayer custom-buffer path sets a real value.
    effectiveBackBufferDurationMs = 0
    currentBitrateAwareLoadControl = null
    configuredBackBufferMs = 0

    val playerSettings = playerSettingsDataStore.playerSettings.first()
    currentPlayerSettingsForReport = playerSettings
    rememberAudioDelayPerDeviceEnabled = playerSettings.rememberAudioDelayPerDevice
    if (rememberAudioDelayPerDeviceEnabled) {
        registerAudioDelayRouteCallback()
        applyStoredAudioDelayForCurrentRouteIfEnabled()
    }
    cachedDecoderPriority = playerSettings.decoderPriority

    val preferredAudioLanguages = resolvePreferredAudioLanguages(
        preferredAudioLanguage = playerSettings.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
        deviceLanguages = resolveDeviceAudioLanguages(),
        contentOriginalLanguage = contentLanguage
    )
    mpvPreferredAudioLanguages = preferredAudioLanguages
    mpvHardwareDecodeModeSetting = playerSettings.mpvHardwareDecodeMode

    val effectiveEngine = resolveEffectiveEngine(playerSettings, overrideInternalPlayerEngine)
    runtimeInternalPlayerEngineOverride = overrideInternalPlayerEngine
    if (overrideInternalPlayerEngine == null && playerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO) {
        resolvedAutoPlayerEngine = effectiveEngine
    } else if (overrideInternalPlayerEngine != null) {
        resolvedAutoPlayerEngine = null
    }
    currentInternalPlayerEngine = effectiveEngine

    playbackAnalyticsDiagnostics.setTraceContext(host = url.safeHost(), engine = effectiveEngine.name)
    playbackAnalyticsDiagnostics.setStartupContext(
        launchStartedAtElapsedMs = launchStartedAtElapsedMs,
        initializationStartedAtWallTimeMs = playerInitializationStartedAtMs,
        startPositionMs = null
    )
    flushPendingPlaybackRawEventLines()

    val deviceAspectMode = deviceLocalPlayerPreferences.aspectMode.first()
    _uiState.update {
        it.copy(
            internalPlayerEngine = effectiveEngine,
            frameRateMatchingMode = playerSettings.frameRateMatchingMode,
            resizeMode = playerSettings.resizeMode,
            aspectMode = deviceAspectMode,
            playbackIssueReportsEnabled = playerSettings.playbackIssueReportsEnabled,
            tunnelingEnabled = playerSettings.tunnelingEnabled &&
                effectiveEngine != InternalPlayerEngine.MVP_PLAYER
        )
    }
    setLoadingStatus(phase = "detecting_format", message = context.getString(R.string.player_loading_detecting_format))

    val afrJob = scope.async {
        runAfrPreflightIfEnabled(
            url = url,
            headers = headers,
            frameRateMatchingMode = playerSettings.frameRateMatchingMode,
            resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
        )
    }

    // MPV branch: AFR settle, then MPV init, then addon subtitles.
    if (effectiveEngine == InternalPlayerEngine.MVP_PLAYER) {
        mpvInitializationInProgress = true
        try {
            afrJob.await()
            if (mpvDelayStartAfterAfrSwitch) {
                Log.d(PlayerRuntimeController.TAG, "AFR display mode switched; delaying MPV start by ${MPV_AFR_SETTLE_DELAY_MS}ms")
                delay(MPV_AFR_SETTLE_DELAY_MS)
            }
            setLoadingStatus(phase = "mpv_buffering", message = context.getString(R.string.player_loading_buffering))
            initializeMpvPlayer(url = url, headers = headers, allowEngineFailover = allowEngineFailover)
            fetchAddonSubtitles()
        } finally {
            mpvInitializationInProgress = false
        }
        return
    }

    resolveCurrentStreamMimeType(url = url, headers = headers)
    mpvInitializationInProgress = false

    // ExoPlayer branch: full DV7 / libass / buffer / track pipeline.
    val dv7Context = resolveDv7Context(playerSettings, url)
    var currentDiagnostics = buildInitialDiagnostics(url, headers, playerSettings, dv7Context)

    val bufferSetup = configureBufferAndNetwork(playerSettings, url)
    currentDiagnostics = currentDiagnostics.copy(
        bufferEngineEnabled = bufferSetup.bufferEngineEnabled,
        parallelNetworkEnabled = bufferSetup.parallelNetworkEnabled
    )

    val streamFlags = resolveStreamFlags(url)
    val startupSubtitlePreparation = prepareStreamStartSubtitles(playerSettings)
    afrJob.await()

    val libassContext = resolveLibassContext(playerSettings)
    val trackSelector = buildTrackSelector(
        playerSettings = playerSettings,
        preferredAudioLanguages = preferredAudioLanguages,
        safeAudioModeEnabled = streamFlags.safeAudioModeEnabled,
        audioDisabledForStream = streamFlags.audioDisabledForStream,
        vc1TrackSelectionBypassActive = streamFlags.vc1TrackSelectionBypassActive
    )
    this.trackSelector = trackSelector

    val extractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

    val dv7ExtractorContext = resolveDv7ExtractorContext(playerSettings, dv7Context, url)
    audioDelayUs.set(_uiState.value.audioDelayMs.toLong() * 1000L)
    subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)

    val codecFallbackContext = resolveCodecFallbackContext(playerSettings, dv7Context, url)
    com.sluggyard.tv.core.player.dvmkv.DolbyVisionCompatibility
        .setHdr10BaseLayerModeActive(codecFallbackContext.isHdr10BaseLayerModeActive)
    isMapDv7ToHevcActiveForCurrentPlayback = codecFallbackContext.mapDv7ToHevcEnabled

    val renderersFactory = buildRenderersFactory(playerSettings, codecFallbackContext)
    val effectiveExtractorsFactory = buildEffectiveExtractorsFactory(extractorsFactory, playerSettings, dv7Context, dv7ExtractorContext)
    mediaSourceFactory.configureSubtitleParsing(
        extractorsFactory = effectiveExtractorsFactory,
        subtitleParserFactory = DefaultSubtitleParserFactory()
    )

    setLoadingStatus(phase = "building_player", message = context.getString(R.string.player_loading_building))

    disposeExoPlayerBeforeRebuild()
    delay(PLAYER_REBUILD_SETTLE_DELAY_MS)

    val bandwidthMeter = buildBandwidthMeter(playerSettings, context)
    _exoPlayer = buildExoPlayer(
        url = url,
        headers = headers,
        playerSettings = playerSettings,
        effectiveEngine = effectiveEngine,
        trackSelector = trackSelector,
        renderersFactory = renderersFactory,
        effectiveExtractorsFactory = effectiveExtractorsFactory,
        loadControl = bufferSetup.loadControl,
        bandwidthMeter = bandwidthMeter,
        useLibass = libassContext.useLibass,
        libassRenderType = libassContext.libassRenderType
    )
    activePlayerUsesLibass = libassContext.useLibass
    libassPipelineSwitchInFlight = false

    _exoPlayer?.let { exo ->
        wireExoPlayer(
            exoPlayer = exo,
            url = url,
            headers = headers,
            playerSettings = playerSettings,
            effectiveEngine = effectiveEngine,
            applyPcmFallbackOnStartup = applyPcmFallbackOnStartup,
            startPaused = startPaused,
            startupSubtitlePreparation = startupSubtitlePreparation,
            currentDiagnostics = currentDiagnostics,
            isTunneledPlayback = playerSettings.tunnelingEnabled
        )
    }
    if (!startupSubtitlePreparation.fetchCompleted) {
        fetchAddonSubtitles()
    }
}

private fun PlayerRuntimeController.resolveEffectiveEngine(
    playerSettings: PlayerSettings,
    overrideInternalPlayerEngine: InternalPlayerEngine?
): InternalPlayerEngine {
    val base = overrideInternalPlayerEngine ?: playerSettings.internalPlayerEngine
    return if (base == InternalPlayerEngine.AUTO) resolveAutoInternalPlayerEngine() else base
}

private fun PlayerRuntimeController.handleInitializePlayerException(
    e: Exception,
    allowEngineFailover: Boolean
) {
    if (maybeAutoSwitchInternalPlayerOnStartupError(
            detailedError = e.message ?: context.getString(R.string.player_error_initialize_failed),
            allowEngineFailover = allowEngineFailover
        )
    ) {
        return
    }
    val displayError = e.toDisplayMessage(context, context.getString(R.string.player_error_initialize_failed))
    val diagnostics = LastPlaybackDiagnostics(
        timestampMs = System.currentTimeMillis(),
        host = currentStreamUrl.safeHost(),
        result = "Error: $displayError"
    )
    lastPlaybackDiagnosticsForReport = diagnostics
    lastPlaybackIssueError = PlaybackIssueErrorInput(
        displayMessage = displayError,
        errorCode = null,
        errorCodeName = null,
        exceptionClass = e.javaClass.name,
        causeClass = e.cause?.javaClass?.name,
        causeMessage = e.cause?.message ?: e.message,
        httpStatus = null
    )
    scope.launch { runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(diagnostics) } }
    _uiState.update {
        it.copy(
            error = displayError,
            showLoadingOverlay = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DV7 mode resolution
// ─────────────────────────────────────────────────────────────────────────────

private data class Dv7Context(
    val effectiveMode: Dv7HandlingMode,
    val autoResult: DolbyVisionBaseLayerPolicy.Result?,
    val probe: DoviBridge.RealtimeConversionProbe,
    val libdoviModeOverrideActive: Boolean,
    val libdoviModeOverride: Int,
    val manualDv81Selected: Boolean,
    val dv7Mode1Forced: Boolean
)

private fun PlayerRuntimeController.resolveDv7Context(
    playerSettings: PlayerSettings,
    url: String
): Dv7Context {
    DoviBridge.resetRuntimeCounters()
    DolbyVisionConversionStats.reset()
    rebufferCount = 0
    rebufferTotalMs = 0L
    rebufferStartedAtMs = 0L

    // Resolve effective DV7 mode — AUTO consults the display-capability policy.
    val (autoResult, baseMode) = when (playerSettings.dv7HandlingMode) {
        Dv7HandlingMode.AUTO -> {
            val result = DolbyVisionBaseLayerPolicy.resolve(
                context = context,
                bridgeReady = DoviBridge.isLibraryLoaded
            )
            val mode = when (result.decision) {
                DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7 -> Dv7HandlingMode.OFF
                DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81 -> Dv7HandlingMode.DV81_LIBDOVI
                else -> Dv7HandlingMode.HDR10_BASE_LAYER
            }
            Log.i(
                PlayerRuntimeController.TAG,
                "DV7_AUTO: decision=${result.decision} effectiveMode=$mode " +
                    "hdrCapsKnown=${result.hdrCapsKnown} displayDv=${result.displayDv} " +
                    "displayHdr10=${result.displayHdr10} displayHdr10Plus=${result.displayHdr10Plus} " +
                    "codecDvheDtb=${result.codecSupportsDvheDtb} bridgeReady=${result.bridgeReady} " +
                    "api=${result.apiLevel} host=${url.safeHost()}"
            )
            result to mode
        }
        else -> null to playerSettings.dv7HandlingMode
    }

    var effectiveMode = baseMode

    // Experimental: explicit libdovi conversion-mode override. Only applies
    // when DV7 handling is Convert to DV8.1 (the modes are libdovi conversion
    // modes, so they're only meaningful while conversion is active).
    val libdoviModeOverride = playerSettings.dv7LibdoviModeOverride
    val libdoviModeOverrideActive = libdoviModeOverride in 0..4 &&
        playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI &&
        effectiveMode == Dv7HandlingMode.DV81_LIBDOVI
    if (libdoviModeOverrideActive) {
        Log.i(PlayerRuntimeController.TAG, "DV7_LIBDOVI_OVERRIDE: forcing conversion mode=$libdoviModeOverride")
    }

    // DV7 to DV8.1 libdovi probe — only runs when the effective mode requests it.
    val dv7ToDv81SettingActive = effectiveMode == Dv7HandlingMode.DV81_LIBDOVI
    val probe = if (dv7ToDv81SettingActive) {
        DoviBridge.probeRealtimeConversionSupport(url)
    } else {
        val reason = when (effectiveMode) {
            Dv7HandlingMode.HDR10_BASE_LAYER -> "hdr10-base-layer-mode"
            Dv7HandlingMode.STRIP_DV -> "strip-dv-mode"
            Dv7HandlingMode.OFF -> "dv7-mode-off"
            Dv7HandlingMode.AUTO -> "auto-mode-no-dv81"  // unreachable; AUTO is collapsed above
            Dv7HandlingMode.DV81_LIBDOVI -> "setting-disabled"  // unreachable
        }
        DoviBridge.RealtimeConversionProbe(
            supported = false,
            reason = reason,
            bridgeVersion = DoviBridge.getBridgeVersionOrNull(),
            extractorHookReady = DoviBridge.isExtractorHookReadyInBuild,
            selfTest = DoviBridge.SelfTestResult(false, "not-run", 0, 0)
        )
    }
    isExperimentalDv7ToDv81ActiveForCurrentPlayback = dv7ToDv81SettingActive && probe.supported

    // AUTO fallback: if AUTO chose DV81 but the probe failed for this stream,
    // downgrade to HDR10_BASE_LAYER so the user still gets a picture.
    if (playerSettings.dv7HandlingMode == Dv7HandlingMode.AUTO &&
        effectiveMode == Dv7HandlingMode.DV81_LIBDOVI &&
        !probe.supported
    ) {
        effectiveMode = Dv7HandlingMode.HDR10_BASE_LAYER
        Log.i(
            PlayerRuntimeController.TAG,
            "DV7_AUTO_FALLBACK: dv81-probe-failed reason=${probe.reason} " +
                "fallback=HDR10_BASE_LAYER host=${url.safeHost()}"
        )
    }
    hasAttemptedDv7ToDv81ForCurrentPlayback = false
    dv7ToDv81BridgeVersionForCurrentPlayback = probe.bridgeVersion
    dv7ToDv81LastProbeReasonForCurrentPlayback = probe.reason
    Log.i(
        PlayerRuntimeController.TAG,
        "DV7_DOVI: mode=${playerSettings.dv7HandlingMode} effectiveMode=$effectiveMode " +
            "dv81Active=$dv7ToDv81SettingActive dv5Compat=${playerSettings.dv5ToDv81Enabled} " +
            "preserveMapping=${playerSettings.dv7ToDv81PreserveMappingEnabled} " +
            "buildNative=${DoviBridge.isNativeEnabledInBuild} " +
            "libraryLoaded=${DoviBridge.isLibraryLoaded} " +
            "extractorHookReady=${probe.extractorHookReady} " +
            "active=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} reason=${probe.reason} " +
            "selfTest=${probe.selfTest.reason} bridge=${probe.bridgeVersion ?: "n/a"} host=${url.safeHost()}"
    )

    val dv7Mode1Forced = dv7Mode1ForcedStreamUrls.contains(url)
    val manualDv81Selected = playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
    isManualDv81Mode2ActiveForCurrentPlayback =
        manualDv81Selected &&
            effectiveMode == Dv7HandlingMode.DV81_LIBDOVI &&
            !libdoviModeOverrideActive &&
            !dv7Mode1Forced

    return Dv7Context(
        effectiveMode = effectiveMode,
        autoResult = autoResult,
        probe = probe,
        libdoviModeOverrideActive = libdoviModeOverrideActive,
        libdoviModeOverride = libdoviModeOverride,
        manualDv81Selected = manualDv81Selected,
        dv7Mode1Forced = dv7Mode1Forced
    )
}

private fun PlayerRuntimeController.buildInitialDiagnostics(
    url: String,
    headers: Map<String, String>,
    playerSettings: PlayerSettings,
    dv7Context: Dv7Context
): LastPlaybackDiagnostics {
    val dv7AutoResult = dv7Context.autoResult
    val probe = dv7Context.probe
    return LastPlaybackDiagnostics(
        timestampMs = System.currentTimeMillis(),
        host = url.safeHost(),
        streamUrl = url,
        headersJson = org.json.JSONObject(headers).toString(),
        hdrCapsKnown = dv7AutoResult?.hdrCapsKnown ?: false,
        displayDv = dv7AutoResult?.displayDv ?: false,
        displayHdr10 = dv7AutoResult?.displayHdr10 ?: false,
        displayHdr10Plus = dv7AutoResult?.displayHdr10Plus ?: false,
        codecDv7Supported = dv7AutoResult?.codecSupportsDvheDtb ?: false,
        dv81DecoderName = null,
        bridgeReady = DoviBridge.isLibraryLoaded,
        bridgeVersion = probe.bridgeVersion,
        bridgeReason = probe.reason,
        dv7ModeRequested = playerSettings.dv7HandlingMode.name,
        dv7ModeEffective = dv7Context.effectiveMode.name,
        dv7AutoDecision = dv7AutoResult?.decision?.name,
        dvSourceProfile = null,
        dv7DoviCalls = 0,
        dv7DoviSuccess = 0,
        dv7DoviSignalRewrites = 0,
        bufferEngineEnabled = false,
        parallelNetworkEnabled = false,
        firstFrameMs = -1L,
        result = "Pending"
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Buffer & network configuration
// ─────────────────────────────────────────────────────────────────────────────

private data class BufferSetup(
    val loadControl: androidx.media3.exoplayer.LoadControl,
    val bufferEngineEnabled: Boolean,
    val parallelNetworkEnabled: Boolean
)

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.configureBufferAndNetwork(
    playerSettings: PlayerSettings,
    url: String
): BufferSetup {
    val libdoviConversionActive = playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
    ExoPlayerPerformanceHelper.updateSettings(playerSettings, context)
    ExoPlayerPerformanceHelper.enabled = playerSettings.exoPerformanceModeEnabled

    val streamMime = currentStreamMimeType
    val isHls = streamMime != null && (
        streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
            streamMime.lowercase().contains("mpegurl") ||
            streamMime.lowercase().contains("m3u8")
    )
    val rawBandwidthMeter = if (ExoPlayerPerformanceHelper.enabled) {
        ExoPlayerPerformanceHelper.buildBandwidthMeter(context)
    } else {
        DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS)
            .build()
    }
    val bandwidthMeter = SafeBandwidthMeter(rawBandwidthMeter, isHls)

    val loadControl = buildLoadControl(playerSettings, url, libdoviConversionActive)
    _loadControl = loadControl as? DefaultLoadControl

    configureMediaSourceFactoryBuffers(playerSettings)

    Log.i(
        PlayerRuntimeController.TAG,
        "BUFFER_NETWORK: bufferEngine=${playerSettings.bufferEngineEnabled} " +
            "parallelNetwork=${playerSettings.parallelNetworkEnabled} " +
            "useParallel=${mediaSourceFactory.useParallelConnections} " +
            "vodCache=${mediaSourceFactory.vodCacheEnabled} host=${url.safeHost()}"
    )

    return BufferSetup(
        loadControl = loadControl,
        bufferEngineEnabled = playerSettings.bufferEngineEnabled,
        parallelNetworkEnabled = playerSettings.parallelNetworkEnabled
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.buildLoadControl(
    playerSettings: PlayerSettings,
    url: String,
    libdoviConversionActive: Boolean
): androidx.media3.exoplayer.LoadControl {
    return if (playerSettings.exoPerformanceModeEnabled) {
        effectiveBackBufferDurationMs = ExoPlayerPerformanceHelper.backBufferMs
        currentBitrateAwareLoadControl = null
        Log.i(
            PlayerRuntimeController.TAG,
            "BUFFER_GATE: engine=exo-native-perf master=on; ExoPlayerPerformanceHelper.buildLoadControl host=${url.safeHost()}"
        )
        ExoPlayerPerformanceHelper.buildLoadControl(context)
    } else if (playerSettings.bufferEngineEnabled) {
        buildBitrateAwareLoadControl(playerSettings, url, libdoviConversionActive)
    } else {
        // Stock LoadControl configured with a 1.5s back buffer so a small rewind (e.g. the
        // scrubber's D-pad seek) replays already-decoded data instead of forcing a full
        // rebuffer stall. DefaultLoadControl's back buffer is 0 by default.
        effectiveBackBufferDurationMs = 1_500
        currentBitrateAwareLoadControl = null
        Log.i(
            PlayerRuntimeController.TAG,
            "BUFFER_GATE: engine=exo-stock master=off; DefaultLoadControl " +
                "(1.5s back buffer, no VOD cache) host=${url.safeHost()}"
        )
        DefaultLoadControl.Builder()
            .setBackBuffer(effectiveBackBufferDurationMs, true)
            .build()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.buildBitrateAwareLoadControl(
    playerSettings: PlayerSettings,
    url: String,
    @Suppress("UNUSED_PARAMETER") libdoviConversionActive: Boolean
): androidx.media3.exoplayer.LoadControl {
    val bufferSettings = playerSettings.bufferSettings
    // Managed (default) caps the buffer at the device budget; off uses Target Buffer Size.
    val budgetManaged = playerSettings.bufferBudgetManaged
    val budgetMbEffective = if (budgetManaged) {
        MemoryBudget.budgetMb
    } else {
        MemoryBudget.effectiveBufferMb(bufferSettings.targetBufferSizeMb)
            .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
    }
    val budgetBytes = budgetMbEffective.toLong() * 1024L * 1024L
    // Build with the user's back buffer so seek-back works immediately (it can't
    // depend on the player re-polling the LoadControl). First frame only lowers
    // it to 0 for confirmed DV7 on low-RAM; everything else keeps it.
    configuredBackBufferMs = bufferSettings.backBufferDurationMs
    val backBufferMsAtBuild = configuredBackBufferMs
    Log.i(
        PlayerRuntimeController.TAG,
        "BUFFER_GATE: engine=exo-custom master=on lowRam=${MemoryBudget.isLowRamTier} " +
            "allowLarge=${playerSettings.allowLargeTargetBuffer} dv7conv=$libdoviConversionActive " +
            "managed=$budgetManaged backBufferMsAtBuild=$backBufferMsAtBuild " +
            "(set=$configuredBackBufferMs, lowered to 0 only for real DV7) " +
            "budgetMb=$budgetMbEffective host=${url.safeHost()}"
    )
    effectiveBackBufferDurationMs = backBufferMsAtBuild
    val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(
        true,
        C.DEFAULT_BUFFER_SEGMENT_SIZE,
        64,
        playerSettings.exoPerformanceModeEnabled
    )
    return BitrateAwareLoadControl(
        minBufferMs = bufferSettings.minBufferMs,
        maxBufferMs = bufferSettings.maxBufferMs,
        bufferForPlaybackMs = bufferSettings.bufferForPlaybackMs,
        bufferForPlaybackAfterRebufferMs = bufferSettings.bufferForPlaybackAfterRebufferMs,
        // Allow buffering past the byte budget until the minimum time threshold is
        // met. Without this, high-bitrate remux files (e.g. 100+ Mbps UHD MKV with
        // multiple audio tracks) exhaust the 500MB byte cap in <5s of content
        // before minBufferMs is satisfied, leaving ExoPlayer stuck in STATE_BUFFERING.
        prioritizeTimeOverSizeThresholds = true,
        backBufferDurationMs = backBufferMsAtBuild,
        retainBackBufferFromKeyframe = true,
        budgetBytes = budgetBytes,
        allocator = allocator
    ).also { currentBitrateAwareLoadControl = it }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.configureMediaSourceFactoryBuffers(playerSettings: PlayerSettings) {
    val bufferEngineEffective = playerSettings.bufferEngineEnabled
    if (bufferEngineEffective) {
        mediaSourceFactory.vodCacheEnabled = playerSettings.vodCacheEnabled
        mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
        mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
    } else {
        mediaSourceFactory.vodCacheEnabled = false
    }

    if (playerSettings.parallelNetworkEnabled) {
        mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
        mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
        mediaSourceFactory.parallelChunkSizeKb = playerSettings.parallelChunkSizeKb
        mediaSourceFactory.exoPerformanceModeEnabled = playerSettings.exoPerformanceModeEnabled
    } else {
        // Reset each playback so the factory doesn't keep last stream's state.
        mediaSourceFactory.useParallelConnections = false
        mediaSourceFactory.exoPerformanceModeEnabled = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stream flags
// ─────────────────────────────────────────────────────────────────────────────

private data class StreamFlags(
    val safeAudioModeEnabled: Boolean,
    val audioDisabledForStream: Boolean,
    val vc1TrackSelectionBypassActive: Boolean
)

private fun PlayerRuntimeController.resolveStreamFlags(url: String): StreamFlags {
    val safeAudioModeEnabled = safeAudioForcedStreamUrls.contains(url)
    val audioDisabledForStream = audioDisabledForcedStreamUrls.contains(url)
    val vc1TrackSelectionBypassActive = vc1TrackSelectionBypassStreamUrls.contains(url)
    isSafeAudioModeActiveForCurrentPlayback = safeAudioModeEnabled
    isAudioDisabledForCurrentPlayback = audioDisabledForStream
    isVc1TrackSelectionBypassActiveForCurrentPlayback = vc1TrackSelectionBypassActive
    return StreamFlags(safeAudioModeEnabled, audioDisabledForStream, vc1TrackSelectionBypassActive)
}

// ─────────────────────────────────────────────────────────────────────────────
// Libass context
// ─────────────────────────────────────────────────────────────────────────────

private data class LibassContext(
    val useLibass: Boolean,
    val libassRenderType: AssRenderType
)

private fun PlayerRuntimeController.resolveLibassContext(playerSettings: PlayerSettings): LibassContext {
    requestedUseLibassByUser = playerSettings.useLibass
    val useLibass = when {
        !requestedUseLibassByUser -> false
        libassPipelineOverrideForCurrentStream != null -> libassPipelineOverrideForCurrentStream == true
        else -> true
    }
    val libassRenderType = playerSettings.libassRenderType.toAssRenderType()
    _uiState.update {
        it.copy(useLibass = useLibass, libassRenderType = playerSettings.libassRenderType)
    }
    return LibassContext(useLibass, libassRenderType)
}

// ─────────────────────────────────────────────────────────────────────────────
// Track selector
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.buildTrackSelector(
    playerSettings: PlayerSettings,
    preferredAudioLanguages: List<String>,
    safeAudioModeEnabled: Boolean,
    audioDisabledForStream: Boolean,
    vc1TrackSelectionBypassActive: Boolean
): DefaultTrackSelector {
    val adaptiveFactory = AdaptiveTrackSelection.Factory(
        ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS,
        AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
    )
    val capturedStreamMime = currentStreamMimeType
    return object : DefaultTrackSelector(context, adaptiveFactory) {
        override fun selectAllTracks(
            definitions: Array<ExoTrackSelection.Definition?>,
            mappedTrackInfo: MappedTrackInfo,
            rendererFormatSupports: Array<out Array<out IntArray>>,
            rendererMixedMimeTypeAdaptationSupports: IntArray,
            params: Parameters
        ) {
            upgradeHlsExceedsCapabilitiesTracks(mappedTrackInfo, rendererFormatSupports, capturedStreamMime)
            super.selectAllTracks(
                definitions,
                mappedTrackInfo,
                rendererFormatSupports,
                rendererMixedMimeTypeAdaptationSupports,
                params
            )
        }
    }.apply {
        setParameters(buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
        if (playerSettings.tunnelingEnabled && !safeAudioModeEnabled) {
            setParameters(buildUponParameters().setTunnelingEnabled(true))
        } else if (safeAudioModeEnabled) {
            setParameters(buildUponParameters().setTunnelingEnabled(false).setConstrainAudioChannelCountToDeviceCapabilities(true))
        }
        if (audioDisabledForStream) {
            setParameters(buildUponParameters().setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO)))
        }
        if (vc1TrackSelectionBypassActive) {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setForceHighestSupportedBitrate(true)
            )
        }
        if (preferredAudioLanguages.isNotEmpty()) {
            setParameters(buildUponParameters().setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray()))
        }
        applyCaptioningManagerParameters()
        applyForcedSubtitleParameters(playerSettings)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun DefaultTrackSelector.upgradeHlsExceedsCapabilitiesTracks(
    mappedTrackInfo: MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    streamMime: String?
) {
    val isHls = streamMime != null && (
        streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
            streamMime.lowercase().contains("mpegurl") ||
            streamMime.lowercase().contains("m3u8")
    )
    Log.d("PlayerTrackSelector", "selectAllTracks run: streamMime=$streamMime, isHls=$isHls")
    if (!isHls) return
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_VIDEO) continue
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                val formatSupport = RendererCapabilities.getFormatSupport(support)
                Log.d(
                    "PlayerTrackSelector",
                    "Evaluating track: id=${format.id}, res=${format.width}x${format.height}, " +
                        "mime=${format.sampleMimeType}, codecs=${format.codecs}, support=$formatSupport"
                )
                if (formatSupport != C.FORMAT_EXCEEDS_CAPABILITIES) continue
                if (shouldUpgradeHlsTrackToHandled(format)) {
                    Log.i("PlayerTrackSelector", "Upgraded track support to FORMAT_HANDLED for id=${format.id}")
                    rendererFormatSupports[rendererIndex][groupIndex][trackIndex] = RendererCapabilities.create(
                        C.FORMAT_HANDLED,
                        RendererCapabilities.ADAPTIVE_SEAMLESS,
                        RendererCapabilities.getTunnelingSupport(support),
                        RendererCapabilities.getHardwareAccelerationSupport(support),
                        RendererCapabilities.getDecoderSupport(support)
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun DefaultTrackSelector.shouldUpgradeHlsTrackToHandled(format: Format): Boolean {
    val mime = format.sampleMimeType
    val isAvcOrHevc = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
    val isAtMost1080p = format.width <= 1920 && format.height <= 1080
    val codecs = format.codecs?.lowercase() ?: ""
    val is10Bit = codecs.contains("main10") || codecs.contains("hevc.2") || codecs.contains("hev2")
    val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084
    val isStandard8Bit = !is10Bit && !isHdr
    Log.d(
        "PlayerTrackSelector",
        "Conditions for id=${format.id}: isAvcOrHevc=$isAvcOrHevc, " +
            "isAtMost1080p=$isAtMost1080p, isStandard8Bit=$isStandard8Bit"
    )
    return isAvcOrHevc && isAtMost1080p && isStandard8Bit
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun DefaultTrackSelector.applyCaptioningManagerParameters() {
    val captioningManager = context?.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager ?: return
    if (!captioningManager.isEnabled) {
        setParameters(buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT))
    }
    captioningManager.locale?.let { locale ->
        setParameters(buildUponParameters().setPreferredTextLanguage(locale.isO3Language))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun DefaultTrackSelector.applyForcedSubtitleParameters(playerSettings: PlayerSettings) {
    // Forced mode: disable ExoPlayer auto text selection; our logic handles it.
    if (playerSettings.subtitleStyle.useForcedSubtitles) {
        setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
    }
    // When forced subtitles are disabled, tell ExoPlayer to ignore
    // SELECTION_FLAG_FORCED so it won't auto-select forced tracks.
    if (!playerSettings.subtitleStyle.useForcedSubtitles) {
        val currentFlags = parameters.ignoredTextSelectionFlags
        setParameters(
            buildUponParameters().setIgnoredTextSelectionFlags(currentFlags or C.SELECTION_FLAG_FORCED)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DV7 extractor context
// ─────────────────────────────────────────────────────────────────────────────

private data class Dv7ExtractorContext(
    val stripDvRpuEnabled: Boolean,
    val stripHdr10PlusSei: Boolean
)

private fun PlayerRuntimeController.resolveDv7ExtractorContext(
    playerSettings: PlayerSettings,
    dv7Context: Dv7Context,
    @Suppress("UNUSED_PARAMETER") url: String
): Dv7ExtractorContext {
    // DV7 conversion is handled app-side by DolbyVisionExtractorsFactory and the
    // vendored Matroska extractor (wired into effectiveExtractorsFactory below).
    if (isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
        dv7ToDv81LastProbeReasonForCurrentPlayback != "ready"
    ) {
        dv7ToDv81LastProbeReasonForCurrentPlayback = "app-extractor-factory"
    }
    val stripDvRpuEnabled = playerSettings.dv7HandlingMode == Dv7HandlingMode.STRIP_DV ||
        dv7Context.effectiveMode == Dv7HandlingMode.HDR10_BASE_LAYER
    if (stripDvRpuEnabled) {
        Log.i(PlayerRuntimeController.TAG, "DV_RPU_STRIP: enabled — will remove DV RPU NALs")
    }
    val stripHdr10PlusSei = playerSettings.stripHdr10PlusSei
    if (stripHdr10PlusSei) {
        Log.i(PlayerRuntimeController.TAG, "HDR10PLUS_STRIP: enabled — will remove HDR10+ SEI NALs")
    }
    return Dv7ExtractorContext(stripDvRpuEnabled, stripHdr10PlusSei)
}

// ─────────────────────────────────────────────────────────────────────────────
// Codec fallback context
// ─────────────────────────────────────────────────────────────────────────────

private data class CodecFallbackContext(
    val mapDv7ToHevcEnabled: Boolean,
    val isHdr10BaseLayerModeActive: Boolean,
    val convertToDv81Active: Boolean,
    val codecSelector: MediaCodecSelector,
    val vc1SoftwareFallbackActive: Boolean,
    val effectiveDecoderPriority: Int
)

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.resolveCodecFallbackContext(
    playerSettings: PlayerSettings,
    dv7Context: Dv7Context,
    url: String
): CodecFallbackContext {
    // mapDv7ToHevc is driven by effective mode (HDR10_BASE_LAYER strips DV7),
    // OR the error handler's per-stream override (preserved for retry-after-failure).
    val mapDv7ToHevcEnabled = dv7Context.effectiveMode == Dv7HandlingMode.HDR10_BASE_LAYER ||
        dv7ToHevcForcedStreamUrls.contains(url)
    val isHdr10BaseLayerModeActive = when (playerSettings.dv7HandlingMode) {
        Dv7HandlingMode.AUTO -> dv7Context.autoResult?.displayDv != true
        else -> dv7Context.effectiveMode == Dv7HandlingMode.HDR10_BASE_LAYER ||
            dv7Context.effectiveMode == Dv7HandlingMode.STRIP_DV
    }
    val convertToDv81Active = !mapDv7ToHevcEnabled &&
        dv7Context.autoResult?.decision == DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81
    val codecSelector = createDolbyVisionFallbackCodecSelector(convertToDv81Active = convertToDv81Active)
    val vc1SoftwareFallbackActive = vc1SoftwarePreferredStreamUrls.contains(url)
    isVc1SoftwareFallbackActiveForCurrentPlayback = vc1SoftwareFallbackActive
    val isForcePassthroughActive = playerSettings.forceOpticalPassthrough && playerSettings.decoderPriority != 0
    val effectiveDecoderPriority = if (vc1SoftwareFallbackActive || hasTriedAudioPcmFallback || isForcePassthroughActive) {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    } else {
        playerSettings.decoderPriority
    }
    return CodecFallbackContext(
        mapDv7ToHevcEnabled = mapDv7ToHevcEnabled,
        isHdr10BaseLayerModeActive = isHdr10BaseLayerModeActive,
        convertToDv81Active = convertToDv81Active,
        codecSelector = codecSelector,
        vc1SoftwareFallbackActive = vc1SoftwareFallbackActive,
        effectiveDecoderPriority = effectiveDecoderPriority
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Renderers factory
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.buildRenderersFactory(
    playerSettings: PlayerSettings,
    codecFallbackContext: CodecFallbackContext
): DefaultRenderersFactory {
    val isForcePassthroughActive = playerSettings.forceOpticalPassthrough && playerSettings.decoderPriority != 0
    return SubtitleOffsetRenderersFactory(
        context = context,
        subtitleDelayUsProvider = subtitleDelayUs::get,
        audioDelayUsProvider = audioDelayUs::get,
        shouldNormalizeCuePositionProvider = {
            val selectedAddonSubtitle = _uiState.value.selectedAddonSubtitle
            selectedAddonSubtitle != null &&
                PlayerSubtitleUtils.mimeTypeFromUrl(selectedAddonSubtitle.url) == MimeTypes.TEXT_VTT
        },
        gainAudioProcessor = gainAudioProcessor,
        downmixEnabled = playerSettings.downmixEnabled,
        audioOutputChannels = playerSettings.audioOutputChannels,
        downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
        forceOpticalPassthrough = isForcePassthroughActive,
        playbackSpeedProvider = { _uiState.value.playbackSpeed },
        initialForcePcm = hasTriedAudioPcmFallback,
        onPlaybackSpeedAwareAudioSinkCreated = { playbackSpeedAwareAudioSink = it },
        onFfmpegAudioRendererChanged = { renderer ->
            ffmpegAudioRenderer = renderer
            renderer?.applyDownmixSettings(
                downmixEnabled = playerSettings.downmixEnabled,
                audioOutputChannels = playerSettings.audioOutputChannels,
                downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
                forceOpticalPassthrough = isForcePassthroughActive
            )
            applyCenterMixLevel(_uiState.value.centerMixLevelDb)
            updateAudioControlAvailability()
        }
    ).setExtensionRendererMode(codecFallbackContext.effectiveDecoderPriority)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(codecFallbackContext.codecSelector)
        .applyMapDv7ToHevcIfSupported(codecFallbackContext.mapDv7ToHevcEnabled)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.buildEffectiveExtractorsFactory(
    delegate: ExtractorsFactory,
    playerSettings: PlayerSettings,
    dv7Context: Dv7Context,
    dv7ExtractorContext: Dv7ExtractorContext
): ExtractorsFactory {
    val needsDvFactory = isExperimentalDv7ToDv81ActiveForCurrentPlayback ||
        dv7ExtractorContext.stripDvRpuEnabled ||
        dv7ExtractorContext.stripHdr10PlusSei
    if (!needsDvFactory) return delegate
    return DolbyVisionExtractorsFactory(
        delegate = delegate,
        config = DolbyVisionConversionConfig(
            active = isExperimentalDv7ToDv81ActiveForCurrentPlayback,
            forcedMode = when {
                dv7Context.libdoviModeOverrideActive -> dv7Context.libdoviModeOverride
                dv7Context.dv7Mode1Forced -> 1
                else -> -1
            },
            preserveMapping = playerSettings.dv7ToDv81PreserveMappingEnabled && dv7Context.manualDv81Selected,
            dv5Enabled = playerSettings.dv5ToDv81Enabled,
            manualDv81 = dv7Context.manualDv81Selected && !dv7Context.dv7Mode1Forced
        ),
        stripDvRpu = dv7ExtractorContext.stripDvRpuEnabled,
        stripHdr10PlusSei = dv7ExtractorContext.stripHdr10PlusSei
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Bandwidth meter & ExoPlayer build
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.buildBandwidthMeter(
    playerSettings: PlayerSettings,
    context: Context
): BandwidthMeter {
    val streamMime = currentStreamMimeType
    val isHls = streamMime != null && (
        streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
            streamMime.lowercase().contains("mpegurl") ||
            streamMime.lowercase().contains("m3u8")
    )
    val raw = if (ExoPlayerPerformanceHelper.enabled) {
        ExoPlayerPerformanceHelper.buildBandwidthMeter(context)
    } else {
        DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS)
            .build()
    }
    return SafeBandwidthMeter(raw, isHls)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.buildExoPlayer(
    url: String,
    headers: Map<String, String>,
    playerSettings: PlayerSettings,
    effectiveEngine: InternalPlayerEngine,
    trackSelector: DefaultTrackSelector,
    renderersFactory: DefaultRenderersFactory,
    effectiveExtractorsFactory: ExtractorsFactory,
    loadControl: androidx.media3.exoplayer.LoadControl,
    bandwidthMeter: BandwidthMeter,
    useLibass: Boolean,
    libassRenderType: AssRenderType
): ExoPlayer {
    val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
    val baseBuilder = {
        ExoPlayer.Builder(context)
            .setBandwidthMeter(bandwidthMeter)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, effectiveExtractorsFactory))
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()
    }
    return if (useLibass) {
        ExoPlayer.Builder(context)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, effectiveExtractorsFactory))
            .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .buildWithAssSupportCompat(
                context = context,
                renderType = libassRenderType,
                playerMediaSourceFactory = mediaSourceFactory,
                dataSourceFactory = dataSourceFactory,
                extractorsFactory = effectiveExtractorsFactory,
                renderersFactory = renderersFactory
            )
    } else {
        baseBuilder()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ExoPlayer wiring (listeners, analytics, media source, prepare)
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.wireExoPlayer(
    exoPlayer: ExoPlayer,
    url: String,
    headers: Map<String, String>,
    playerSettings: PlayerSettings,
    effectiveEngine: InternalPlayerEngine,
    applyPcmFallbackOnStartup: Boolean,
    startPaused: Boolean,
    startupSubtitlePreparation: StartupSubtitlePreparation,
    currentDiagnostics: LastPlaybackDiagnostics,
    isTunneledPlayback: Boolean
) {
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    exoPlayer.setAudioAttributes(audioAttributes, true)
    exoPlayer.setPlaybackSpeed(_uiState.value.playbackSpeed)
    if (applyPcmFallbackOnStartup) {
        pendingAudioPcmFallbackRebuild = false
        hasTriedAudioPcmFallback = true
    }
    if (playerSettings.skipSilence) exoPlayer.skipSilenceEnabled = true
    exoPlayer.setHandleAudioBecomingNoisy(true)

    runCatching {
        currentMediaSession?.release()
        if (exoPlayer.canAdvertiseSession()) {
            currentMediaSession = MediaSession.Builder(context, exoPlayer).build()
        }
        updateMediaSessionMetadata()
    }

    applyAudioAmplification(_uiState.value.audioAmplificationDb)
    applyCenterMixLevel(_uiState.value.centerMixLevelDb)
    notifyAudioSessionUpdate(true)

    val preferred = playerSettings.subtitleStyle.preferredLanguage
    val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
    applySubtitlePreferences(preferred, secondary)
    applyStartupSubtitlePreparation(startupSubtitlePreparation)
    val startupSubtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation)
    val initialResumePosition = resolvePendingInitialResumePosition()
    playbackAnalyticsDiagnostics.setStartupStartPosition(initialResumePosition)
    playbackAnalyticsDiagnostics.recordRawEventLine(
        "PLAYER_INIT: engine=EXOPLAYER host=${url.safeHost()} " +
            "playbackSpeed=${_uiState.value.playbackSpeed} " +
            "resumePositionMs=$initialResumePosition mime=${currentStreamMimeType ?: "unknown"} " +
            "bufferEngine=${playerSettings.bufferEngineEnabled} parallel=${mediaSourceFactory.useParallelConnections} " +
            "vodCache=${mediaSourceFactory.vodCacheEnabled} tunneling=${playerSettings.tunnelingEnabled}"
    )
    val initialMediaSource = mediaSourceFactory.createMediaSource(
        context = context,
        url = url,
        headers = headers,
        subtitleConfigurations = startupSubtitleConfigurations,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders,
        mimeTypeOverride = currentStreamMimeType,
        audioDelayUsProvider = audioDelayUs::get,
        mediaMetadata = buildMediaSessionMetadata()
    )
    if (initialResumePosition > 0L) {
        exoPlayer.setMediaSource(initialMediaSource, initialResumePosition)
        clearPendingInitialResumePosition()
        updatePlaybackTimeline(currentPosition = initialResumePosition)
    } else {
        exoPlayer.setMediaSource(initialMediaSource)
    }

    setLoadingStatus(phase = "starting_stream", message = context.getString(R.string.player_loading_starting))
    // Always start paused — playback begins in onRenderedFirstFrame() so audio
    // and video start in perfect sync. Without this, the audio renderer races
    // ahead by 1-2s while the video decoder is still decoding the first I-frame.
    //
    // Exception: tunneled playback bypasses the normal video rendering pipeline
    // so onRenderedFirstFrame() never fires. In that case we fall back to
    // starting on STATE_READY.
    exoPlayer.playWhenReady = !startPaused && !userPausedManually

    attachPlayerListener(
        exoPlayer = exoPlayer,
        playerSettings = playerSettings,
        startPaused = startPaused,
        isTunneledPlayback = isTunneledPlayback,
        currentDiagnostics = currentDiagnostics
    )
    // Register listeners before preparation. Fast/local sources can transition
    // to BUFFERING or READY quickly enough to miss post-prepare registration.
    exoPlayer.prepare()
    attachAnalyticsListener(exoPlayer, currentDiagnostics)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.attachPlayerListener(
    exoPlayer: ExoPlayer,
    playerSettings: PlayerSettings,
    startPaused: Boolean,
    isTunneledPlayback: Boolean,
    currentDiagnostics: LastPlaybackDiagnostics
) {
    var liveDiagnostics = currentDiagnostics
    exoPlayer.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isReleasingPlayer) return
            if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                mediaSourceFactory.unlockStartupPrefetch()
            }
            val playerDuration = exoPlayer.duration
            if (playerDuration > lastKnownDuration) lastKnownDuration = playerDuration
            val isBuffering = playbackState == Player.STATE_BUFFERING
            updatePlaybackTimeline(duration = playerDuration.coerceAtLeast(0L))
            _uiState.update {
                it.copy(
                    isBuffering = if (ExoPlayerPerformanceHelper.shouldSuppressBufferingUi(
                            suppressBufferingUiForSeek, seekBufferingUiDeferred, isBuffering
                        )
                    ) false else isBuffering,
                    playbackEnded = playbackState == Player.STATE_ENDED
                )
            }
            updateAudioControlAvailability()

            handleRebufferTelemetry(playbackState, liveDiagnostics)

            if (isScrubbingModeActive) {
                isScrubbingModeActive = false
                _exoPlayer?.setScrubbingModeParameters(ScrubbingModeParameters.Builder().build())
            }

            if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                _uiState.update { state ->
                    if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
                        recordLoadingDiagnosticEvent(
                            phase = "buffering_before_first_frame",
                            message = context.getString(R.string.player_loading_buffering),
                            detail = "overlay_reopened"
                        )
                        state.copy(
                            showLoadingOverlay = true,
                            showControls = false,
                            loadingMessage = context.getString(R.string.player_loading_buffering)
                        )
                    } else {
                        recordLoadingDiagnosticEvent(
                            phase = "buffering_before_first_frame",
                            message = context.getString(R.string.player_loading_buffering)
                        )
                        state.copy(loadingMessage = context.getString(R.string.player_loading_buffering))
                    }
                }
            }

            // Arm stall watchdog while buffering.
            if (playbackState == Player.STATE_BUFFERING) {
                maybeScheduleStallWatchdog()
            } else {
                cancelStallWatchdog()
            }

            if (playbackState == Player.STATE_BUFFERING &&
                pendingSeekTelemetryAwaitingFirstFrame &&
                pendingSeekTelemetryReadyAssumed
            ) {
                pendingSeekTelemetryReadyAtMs = 0L
                pendingSeekTelemetryReadyLatencyMs = -1L
                pendingSeekTelemetryReadyAssumed = false
            }

            if (playbackState == Player.STATE_READY) {
                liveDiagnostics = handleStateReady(exoPlayer, startPaused, isTunneledPlayback, playerSettings, liveDiagnostics)
            } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                cancelFirstFrameWatchdog()
            }

            if (playbackState == Player.STATE_ENDED) {
                handleStateEnded(liveDiagnostics).also { liveDiagnostics = it }
            }

            refreshStableProgressResetGate()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                userPausedManually = false
                cancelPauseOverlay()
                startProgressUpdates()
                startWatchProgressSaving()
                scheduleHideControls()
                tryShowParentalGuide()
                emitScrobbleStart()
            } else {
                if (userPausedManually) schedulePauseOverlay() else cancelPauseOverlay()
                if (exoPlayer.playbackState == Player.STATE_ENDED || exoPlayer.playbackState == Player.STATE_IDLE) {
                    stopProgressUpdates()
                }
                stopWatchProgressSaving()
                if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
                    saveWatchProgressIfNeeded()
                } else {
                    emitStopScrobbleForCurrentProgress()
                    saveWatchProgress()
                }
            }
            refreshStableProgressResetGate()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateAvailableTracks(tracks)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                currentVideoWidth = videoSize.width
                currentVideoHeight = videoSize.height
                Log.d(
                    PlayerRuntimeController.TAG,
                    "onVideoSizeChanged: updated resolution to ${videoSize.width}x${videoSize.height}"
                )
            }
        }

        override fun onRenderedFirstFrame() {
            val isFirstFrame = !hasRenderedFirstFrame  // capture BEFORE flipping
            hasRenderedFirstFrame = true
            mediaSourceFactory.unlockStartupPrefetch()
            if (isFirstFrame && _uiState.value.postPlayDismissedForCurrentEpisode) {
                _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
            }
            updateAudioControlAvailability()
            // Start playback now that the first video frame is visible: audio
            // and video begin in sync.
            if (!startPaused && !userPausedManually) {
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            }
            refreshStableProgressResetGate()
            cancelFirstFrameWatchdog()
            _uiState.update {
                it.copy(
                    showLoadingOverlay = false,
                    loadingMessage = null,
                    loadingProgress = if (it.loadingProgress != null) 1f else null,
                    loadingIssueReportVisible = false,
                    loadingIssueElapsedMs = 0L,
                    showPlayerEngineSwitchInfo = false
                )
            }
            finishLoadingDiagnostics("first_frame_rendered")
            if (isFirstFrame) {
                liveDiagnostics = recordFirstFrameDiagnostics(exoPlayer, liveDiagnostics, playerSettings)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isReleasingPlayer && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) return
            cancelFirstFrameWatchdog()
            val detailedError = error.toDisplayMessage(context)
            cancelStableProgressReset()
            val crashPosition = exoPlayer.currentPosition

            if (handleBackgroundDecoderCrash(error, crashPosition)) return
            if (handleDolbyVisionDecoderFailure(error, crashPosition)) return
            if (handleDecodingFailedOrRuntimeCheck(error, crashPosition)) return
            if (handleAudioTrackFailure(error, crashPosition)) return
            if (handleStuckPlayingNoProgress(error, crashPosition)) return
            if (handleTimeoutRecovery(error, crashPosition)) return
            if (handleUnexpectedLoaderNpe(error, crashPosition)) return
            if (handleMediaPeriodHolderCrash(error, crashPosition)) return
            if (handleDecoderResourcesReclaimed(error, crashPosition)) return
            if (handle416ResponseCode(error)) return

            // ── Main Engine Failover ──
            if (maybeAutoSwitchInternalPlayerOnStartupError(
                    detailedError = detailedError,
                    allowEngineFailover = allowEngineFailoverForError(error),
                    allowAfterFirstFrame = error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
                )
            ) {
                return
            }
            if (attemptAutoRetry(error, detailedError)) return

            if (rebufferStartedAtMs != 0L) {
                val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
                rebufferTotalMs += lastRebufferMs
                rebufferStartedAtMs = 0L
                playbackAnalyticsDiagnostics.onRebufferEnded(exoPlayer, rebufferTotalMs, lastRebufferMs)
            }

            val errorDiagnostics = liveDiagnostics.copy(
                rebufferCount = rebufferCount,
                rebufferTotalMs = rebufferTotalMs,
                result = "Error: $detailedError"
            )
            lastPlaybackDiagnosticsForReport = errorDiagnostics
            lastPlaybackIssueError = PlaybackIssueErrorInput(
                displayMessage = detailedError,
                errorCode = error.errorCode,
                errorCodeName = error.errorCodeName,
                exceptionClass = error.javaClass.name,
                causeClass = error.cause?.javaClass?.name,
                causeMessage = error.cause?.message,
                httpStatus = error.findInvalidResponseCodeException()?.responseCode
            )
            scope.launch { runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(errorDiagnostics) } }

            _uiState.update {
                it.copy(
                    error = detailedError,
                    showLoadingOverlay = false,
                    showPauseOverlay = false,
                    loadingIssueReportVisible = false,
                    loadingIssueElapsedMs = 0L
                )
            }
        }
    })
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleRebufferTelemetry(
    playbackState: Int,
    currentDiagnostics: LastPlaybackDiagnostics
) {
    val player = _exoPlayer ?: return
    if (playbackState == Player.STATE_BUFFERING) {
        if (hasRenderedFirstFrame && rebufferStartedAtMs == 0L) {
            rebufferCount += 1
            rebufferStartedAtMs = SystemClock.elapsedRealtime()
            playbackAnalyticsDiagnostics.onRebufferStarted(player, rebufferCount)
            Log.i(
                PlayerRuntimeController.TAG,
                "REBUFFER: count=$rebufferCount totalRebufferMs=$rebufferTotalMs " +
                    "bufferEngine=${currentDiagnostics.bufferEngineEnabled} " +
                    "dv7dovi=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                    "host=${currentStreamUrl.safeHost()}"
            )
        }
    } else if (rebufferStartedAtMs != 0L) {
        val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
        rebufferTotalMs += lastRebufferMs
        rebufferStartedAtMs = 0L
        playbackAnalyticsDiagnostics.onRebufferEnded(player, rebufferTotalMs, lastRebufferMs)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleStateReady(
    exoPlayer: ExoPlayer,
    startPaused: Boolean,
    isTunneledPlayback: Boolean,
    playerSettings: PlayerSettings,
    currentDiagnostics: LastPlaybackDiagnostics
): LastPlaybackDiagnostics {
    if (pendingSeekTelemetryRequestedAtMs > 0L && pendingSeekTelemetryReadyAtMs <= 0L) {
        val latencyMs = (System.currentTimeMillis() - pendingSeekTelemetryRequestedAtMs).coerceAtLeast(0L)
        pendingSeekTelemetryReadyAtMs = System.currentTimeMillis()
        pendingSeekTelemetryReadyLatencyMs = latencyMs
    }
    // Don't auto-play on the initial STATE_READY — wait for
    // onRenderedFirstFrame() to ensure A/V sync.
    // Exception: tunneled playback never fires onRenderedFirstFrame(), so we
    // must start here.
    if (shouldEnforceAutoplayOnFirstReady) {
        shouldEnforceAutoplayOnFirstReady = false
        if (isTunneledPlayback) {
            // Tunneled mode — onRenderedFirstFrame() won't fire; treat
            // STATE_READY as the sync point.
            hasRenderedFirstFrame = true
            mediaSourceFactory.unlockStartupPrefetch()
            playbackAnalyticsDiagnostics.onSyntheticFirstFrame(exoPlayer)
            if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
            }
            if (!startPaused && !userPausedManually) {
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            }
            finishLoadingDiagnostics("first_frame_ready")
            recordFirstFrameDiagnostics(exoPlayer, currentDiagnostics, playerSettings)
            _uiState.update {
                it.copy(
                    showLoadingOverlay = false,
                    loadingMessage = null,
                    loadingProgress = if (it.loadingProgress != null) 1f else null,
                    showPlayerEngineSwitchInfo = false
                )
            }
        }
        // Non-tunneled: playback will start in onRenderedFirstFrame().
    } else if (!userPausedManually && hasRenderedFirstFrame) {
        exoPlayer.play()
    }
    tryApplyPendingResumeProgress(exoPlayer)
    _uiState.value.pendingSeekPosition?.let { position ->
        exoPlayer.seekTo(position)
        if (ExoPlayerPerformanceHelper.enabled) {
            seekBufferingUiDeferred = true
            seekBufferingUiJob?.cancel()
            seekBufferingUiJob = scope.launch {
                delay(seekBufferingUiDelayMs)
                seekBufferingUiDeferred = false
                if (pendingSeekFlush) {
                    _uiState.update { it.copy(isBuffering = true) }
                }
            }
        }
        _uiState.update { it.copy(pendingSeekPosition = null) }
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    if (!ExoPlayerPerformanceHelper.shouldGuardTrackRebuild() || !hasRenderedFirstFrame) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().build()
    }
    maybeScheduleFirstFrameWatchdog()
    return currentDiagnostics
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleStateReadyDiagnostics(
    currentDiagnostics: LastPlaybackDiagnostics,
    playerSettings: PlayerSettings
): LastPlaybackDiagnostics {
    // The first-frame diagnostics snapshot is recorded in onRenderedFirstFrame
    // for the non-tunneled path; the tunneled path records it inline in
    // handleStateReady. This helper is a no-op placeholder kept for symmetry.
    return currentDiagnostics
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleStateEnded(
    currentDiagnostics: LastPlaybackDiagnostics
): LastPlaybackDiagnostics {
    emitCompletionScrobbleStop(progressPercent = 99.5f)
    // Re-persist diagnostics with the final rebuffer totals (the first-frame
    // snapshot captured 0, since rebuffers accrue after).
    Log.i(
        PlayerRuntimeController.TAG,
        "BUFFER_SUMMARY: rebuffers=$rebufferCount rebufferTotalMs=$rebufferTotalMs " +
            "bufferEngine=${currentDiagnostics.bufferEngineEnabled} host=${currentStreamUrl.safeHost()}"
    )
    var updated = currentDiagnostics
    if (currentDiagnostics.result == "Played") {
        updated = currentDiagnostics.copy(
            rebufferCount = rebufferCount,
            rebufferTotalMs = rebufferTotalMs
        )
        lastPlaybackDiagnosticsForReport = updated
        scope.launch { runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(updated) } }
    }
    saveWatchProgress()
    resetPostPlayStateAfterPlaybackEnded()
    return updated
}

// ─────────────────────────────────────────────────────────────────────────────
// Player error handlers
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleBackgroundDecoderCrash(error: PlaybackException, currentPosition: Long): Boolean {
    // If the codec crashed while the app is in the background (e.g. another app
    // reclaimed the hardware decoder), don't run the retry chain. Each retry
    // just re-acquires a decoder the foreground app immediately reclaims again,
    // burning the retry budget. Save the position, free the decoder, and
    // rebuild paused on resume instead.
    if (!isInBackground || !isRetryablePlaybackError(error)) return false
    backgroundCrashSavedPositionMs = currentPosition.takeIf { it > 0L } ?: 0L
    pendingBackgroundCrashRecovery = true
    _uiState.update { it.copy(isPlaying = false, isBuffering = true, showLoadingOverlay = true) }
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch { releasePlayer(flushPlaybackState = false) }
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleDolbyVisionDecoderFailure(error: PlaybackException, currentPosition: Long): Boolean {
    if (!error.isDolbyVisionDecoderFailure() || isMapDv7ToHevcActiveForCurrentPlayback) return false
    // Manual Convert-to-DV8.1 mode 2 failed to decode: try libdovi mode 1 once
    // before falling back to HDR10.
    if (isManualDv81Mode2ActiveForCurrentPlayback &&
        !dv7Mode1ForcedStreamUrls.contains(currentStreamUrl)
    ) {
        dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
        Log.i(
            PlayerRuntimeController.TAG,
            "DV7_MODE2_PLAYBACK_FALLBACK: mode 2 decode failed; " +
                "retrying stream at mode 1 host=${currentStreamUrl.safeHost()}"
        )
        retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
        return true
    }
    if (isExperimentalDv7ToDv81ActiveForCurrentPlayback && !hasAttemptedDv7ToDv81ForCurrentPlayback) {
        hasAttemptedDv7ToDv81ForCurrentPlayback = true
        val probe = DoviBridge.probeRealtimeConversionSupport(currentStreamUrl)
        dv7ToDv81LastProbeReasonForCurrentPlayback = probe.reason
        dv7ToDv81BridgeVersionForCurrentPlayback = probe.bridgeVersion
    }
    dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
    retryCurrentStreamWithDolbyVisionFallback(currentPosition)
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleDecodingFailedOrRuntimeCheck(error: PlaybackException, currentPosition: Long): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
        error.errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
    ) return false
    if (autoSwitchInternalPlayerOnErrorEnabled) return false
    if (!isSafeAudioModeActiveForCurrentPlayback) {
        safeAudioForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithSafeAudioFallback(currentPosition)
        return true
    }
    if (!isAudioDisabledForCurrentPlayback) {
        audioDisabledForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithAudioDisabled(currentPosition)
        return true
    }
    return false
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleAudioTrackFailure(error: PlaybackException, currentPosition: Long): Boolean {
    if (!error.isAudioTrackFailure()) return false
    // AudioTrack init (5001) or write (5002, e.g. ERROR_DEAD_OBJECT on an
    // E-AC-3/AC-3 passthrough or offload track) failure: re-select audio with
    // passthrough/tunneling off and the channel count constrained to the
    // device's capabilities, then fall back to disabling audio so video keeps
    // playing — instead of surfacing the fatal error screen.
    if (!isSafeAudioModeActiveForCurrentPlayback) {
        safeAudioForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithSafeAudioFallback(currentPosition)
        return true
    }
    if (!isAudioDisabledForCurrentPlayback) {
        audioDisabledForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithAudioDisabled(currentPosition)
        return true
    }
    return false
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleStuckPlayingNoProgress(error: PlaybackException, currentPosition: Long): Boolean {
    if (!error.isStuckPlayingNoProgress()) return false
    if (!isSafeAudioModeActiveForCurrentPlayback) {
        safeAudioForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithSafeAudioFallback(currentPosition)
        return true
    }
    if (!isAudioDisabledForCurrentPlayback) {
        audioDisabledForcedStreamUrls.add(currentStreamUrl)
        retryCurrentStreamWithAudioDisabled(currentPosition)
        return true
    }
    return false
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleTimeoutRecovery(error: PlaybackException, currentPosition: Long): Boolean {
    val timeoutError = error.findCause<SocketTimeoutException>() ?: return false
    if (timeoutRecoveryAttempts >= PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS) return false
    retryCurrentStreamAfterTimeout(currentPosition)
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleUnexpectedLoaderNpe(error: PlaybackException, currentPosition: Long): Boolean {
    if (!error.isUnexpectedLoaderNullPointer()) return false
    if (hasRetriedCurrentStreamAfterUnexpectedNpe) return false
    hasRetriedCurrentStreamAfterUnexpectedNpe = true
    retryCurrentStreamAfterUnexpectedNpe(currentPosition)
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleMediaPeriodHolderCrash(error: PlaybackException, currentPosition: Long): Boolean {
    if (!error.isMediaPeriodHolderStateCrash()) return false
    if (hasRetriedCurrentStreamAfterMediaPeriodHolderCrash) return false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = true
    retryCurrentStreamAfterMediaPeriodHolderCrash(currentPosition)
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handleDecoderResourcesReclaimed(error: PlaybackException, currentPosition: Long): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) return false
    if (decoderResourcesReclaimedRecoveryUrl == currentStreamUrl) return false
    retryCurrentStreamAfterDecoderResourcesReclaimed(currentPosition)
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.handle416ResponseCode(error: PlaybackException): Boolean {
    val responseCode = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
    if (responseCode != 416 || hasRetriedCurrentStreamAfter416) return false
    retryCurrentStreamFromStartAfter416()
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun allowEngineFailoverForError(@Suppress("UNUSED_PARAMETER") error: PlaybackException): Boolean = true

// ─────────────────────────────────────────────────────────────────────────────
// Analytics listener
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.attachAnalyticsListener(
    player: ExoPlayer,
    currentDiagnostics: LastPlaybackDiagnostics
) {
    var liveDiagnostics = currentDiagnostics
    player.addAnalyticsListener(object : AnalyticsListener {
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            playbackAnalyticsDiagnostics.onPlaybackStateChanged(eventTime, state)
        }

        override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
            playbackAnalyticsDiagnostics.onPlayWhenReadyChanged(eventTime, playWhenReady, reason)
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            playbackAnalyticsDiagnostics.onIsPlayingChanged(eventTime, isPlaying)
        }

        override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
            playbackAnalyticsDiagnostics.onIsLoadingChanged(eventTime, isLoading)
        }

        override fun onPlaybackParametersChanged(eventTime: AnalyticsListener.EventTime, playbackParameters: PlaybackParameters) {
            playbackAnalyticsDiagnostics.onPlaybackParametersChanged(eventTime, playbackParameters)
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            playbackAnalyticsDiagnostics.onRenderedFirstFrame(eventTime)
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            playbackAnalyticsDiagnostics.onPlayerError(eventTime, error)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            liveDiagnostics = liveDiagnostics.copy(dv81DecoderName = decoderName)
            playbackAnalyticsDiagnostics.onVideoDecoderInitialized(
                eventTime = eventTime,
                decoderName = decoderName,
                initializationDurationMs = initializationDurationMs
            )
            Log.i(
                PlayerRuntimeController.TAG,
                "VIDEO_DECODER: name=$decoderName initMs=$initializationDurationMs host=${currentStreamUrl.safeHost()}"
            )
        }

        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            playbackAnalyticsDiagnostics.onVideoDecoderReleased(eventTime, decoderName)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            playbackAnalyticsDiagnostics.onVideoInputFormatChanged(
                eventTime = eventTime,
                format = format,
                reuseEvaluation = decoderReuseEvaluation
            )
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: androidx.media3.common.VideoSize) {
            playbackAnalyticsDiagnostics.onVideoSizeChanged(eventTime, videoSize)
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            playbackAnalyticsDiagnostics.onDroppedVideoFrames(eventTime, droppedFrames, elapsedMs)
        }

        override fun onVideoFrameProcessingOffset(eventTime: AnalyticsListener.EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
            playbackAnalyticsDiagnostics.onVideoFrameProcessingOffset(
                eventTime = eventTime,
                totalProcessingOffsetUs = totalProcessingOffsetUs,
                frameCount = frameCount
            )
        }

        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            playbackAnalyticsDiagnostics.onVideoDisabled(eventTime, decoderCounters)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            playbackAnalyticsDiagnostics.onAudioDecoderInitialized(
                eventTime = eventTime,
                decoderName = decoderName,
                initializationDurationMs = initializationDurationMs
            )
        }

        override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            playbackAnalyticsDiagnostics.onAudioDecoderReleased(eventTime, decoderName)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            playbackAnalyticsDiagnostics.onAudioInputFormatChanged(
                eventTime = eventTime,
                format = format,
                reuseEvaluation = decoderReuseEvaluation
            )
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long
        ) {
            playbackAnalyticsDiagnostics.onAudioUnderrun(
                eventTime = eventTime,
                bufferSize = bufferSize,
                bufferSizeMs = bufferSizeMs,
                elapsedSinceLastFeedMs = elapsedSinceLastFeedMs
            )
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long
        ) {
            playbackAnalyticsDiagnostics.onBandwidthEstimate(
                eventTime = eventTime,
                totalLoadTimeMs = totalLoadTimeMs,
                totalBytesLoaded = totalBytesLoaded,
                bitrateEstimate = bitrateEstimate
            )
        }

        override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            playbackAnalyticsDiagnostics.onLoadStarted(eventTime, loadEventInfo, mediaLoadData)
        }

        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            playbackAnalyticsDiagnostics.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData)
        }

        override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            playbackAnalyticsDiagnostics.onLoadCanceled(eventTime, loadEventInfo, mediaLoadData)
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean
        ) {
            playbackAnalyticsDiagnostics.onLoadError(
                eventTime = eventTime,
                loadEventInfo = loadEventInfo,
                mediaLoadData = mediaLoadData,
                error = error,
                wasCanceled = wasCanceled
            )
        }
    })
}

// ─────────────────────────────────────────────────────────────────────────────
// AUTO engine resolver
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Resolves the AUTO engine selection for the current stream.
 *
 * - Dolby Vision → ExoPlayer (forked extractor / DoviBridge DV7→DV8.1).
 * - Anime (genre, catalog id, Animation+Japan, or series+Animation hint) → MPV
 *   for native libass softsubs — even when the stream title never says ASS.
 * - Explicit ASS/SSA in stream metadata → MPV.
 * - Everything else (mainstream remuxes, non-anime) → ExoPlayer (HW HEVC path).
 *
 * AUTO does **not** guarantee lag-free playback: anime always forces MPV today,
 * and Hi10/AVC10 encodes may still software-decode on leanback SoCs.
 */
internal fun PlayerRuntimeController.resolveAutoInternalPlayerEngine(): InternalPlayerEngine {
    val streamMetadataText = buildString {
        currentFilename?.let { appendLine(it) }
        streamName?.let { appendLine(it) }
        currentStreamDescription?.let { appendLine(it) }
        append(title)
    }
    val isDolbyVision = Regex(
        """(?i)(\bDV[\.\-_ ]|\bDoVi\b|\bDolby[\.\-_ ]?Vision\b|\bProfile[\.\-_ ]?7\b|\bP7\b)"""
    ).containsMatchIn(streamMetadataText)

    // Match proven origin/main AUTO: anime genre, Animation+Japan, or anime catalog IDs
    // (kitsu/mal/anilist) — then fall back to the series+Animation rewrite hint.
    val hasAnimeGenre = metaGenres.any { it.equals("anime", ignoreCase = true) } ||
        effectiveContentGenres().orEmpty().split(',').any { it.trim().equals("anime", ignoreCase = true) }
    val isAnimationFromJapan = (
        metaGenres.any { it.equals("animation", ignoreCase = true) } ||
            effectiveContentGenres().orEmpty().contains("animation", ignoreCase = true)
        ) && metaCountry?.contains("Japan", ignoreCase = true) == true
    val videoId = currentVideoId ?: contentId
    val hasAnimeId = videoId?.startsWith("kitsu:", ignoreCase = true) == true ||
        videoId?.startsWith("mal:", ignoreCase = true) == true ||
        videoId?.startsWith("anilist:", ignoreCase = true) == true
    val isAnime = hasAnimeGenre || hasAnimeId || isAnimationFromJapan || isAnimePlaybackHint(
        contentType = contentType,
        contentGenres = effectiveContentGenres(),
        contentLanguage = contentLanguage,
        title = title,
    )
    val needsAssRenderer = Regex("""(?i)\b(ass|ssa)\b""").containsMatchIn(streamMetadataText)
    return if (!isDolbyVision && (isAnime || needsAssRenderer)) {
        Log.i(
            "SlugYardAutoPick",
            "engine=MPV isAnime=$isAnime assHint=$needsAssRenderer " +
                "genres=${effectiveContentGenres()} lang=$contentLanguage " +
                "type=$contentType stream='${streamName?.take(120)}' file='${currentFilename?.take(120)}'",
        )
        InternalPlayerEngine.MVP_PLAYER
    } else {
        Log.i(
            "SlugYardAutoPick",
            "engine=EXO isAnime=$isAnime dv=$isDolbyVision " +
                "genres=${effectiveContentGenres()} lang=$contentLanguage " +
                "type=$contentType stream='${streamName?.take(120)}' file='${currentFilename?.take(120)}'",
        )
        InternalPlayerEngine.EXOPLAYER
    }
}

/** Launch genres, else meta genres once [applyMetaDetails] has run. */
internal fun PlayerRuntimeController.effectiveContentGenres(): String? {
    contentGenres?.takeIf { it.isNotBlank() }?.let { return it }
    return metaGenres.takeIf { it.isNotEmpty() }?.joinToString(",")
}

/**
 * Heuristic for whether the current item is anime. Cinemeta frequently
 * identifies anime only as an episodic Animation title and omits
 * original-language metadata. For playback, that is enough of a signal to
 * prefer MPV's native libass path; movies and non-animated series remain on
 * ExoPlayer by default.
 */
internal fun isAnimePlaybackHint(
    contentType: String?,
    contentGenres: String?,
    contentLanguage: String?,
    title: String?
): Boolean {
    if (contentType.equals("anime", ignoreCase = true)) return true
    if (StreamScoringEngine.classifyContent(
            StreamScoringEngine.ContentContext(
                contentType = contentType,
                genres = contentGenres,
                contentLanguage = contentLanguage,
                title = title,
                season = null,
                episode = null
            )
        ) == StreamScoringEngine.ContentType.ANIME
    ) {
        return true
    }
    val normalizedGenres = contentGenres.orEmpty().lowercase()
    val hasAnimationGenre = normalizedGenres.contains("animation") || normalizedGenres.contains("anime")
    // Series + Animation is enough for MPV/libass. Do not require Asian language —
    // Cinemeta often tags JJK etc. as Animation without ja original-language metadata
    // at the moment AUTO first runs.
    return contentType.equals("series", ignoreCase = true) && hasAnimationGenre
}

internal fun PlayerRuntimeController.shouldPreferAssSubtitles(): Boolean {
    if (!isUsingMpvEngine()) return false
    return isAnimePlaybackHint(
        contentType = contentType,
        contentGenres = effectiveContentGenres(),
        contentLanguage = contentLanguage,
        title = title,
    ) || Regex("""(?i)\b(ass|ssa)\b""").containsMatchIn(
        buildString {
            currentFilename?.let { appendLine(it) }
            streamName?.let { appendLine(it) }
            currentStreamDescription?.let { appendLine(it) }
        },
    )
}

/**
 * Resolves the ordered list of preferred audio languages for track selection,
 * expanding the special "default"/"device"/"original" tokens into concrete
 * language codes.
 */
internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String? = null
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = language?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            SUBTITLE_LANGUAGE_FORCED -> null
            AudioLanguageOption.ORIGINAL -> contentOriginalLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            else -> normalized
        }
    }

    return when (preferredAudioLanguage.trim().lowercase()) {
        AudioLanguageOption.DEFAULT -> listOfNotNull(normalize(secondaryPreferredAudioLanguage)).distinct()
        AudioLanguageOption.DEVICE -> (
            deviceLanguages.mapNotNull(::normalize) +
                listOfNotNull(normalize(secondaryPreferredAudioLanguage))
            ).distinct()
        AudioLanguageOption.ORIGINAL -> {
            val originalLang = normalize(contentOriginalLanguage)
            if (originalLang != null) {
                listOfNotNull(originalLang, normalize(secondaryPreferredAudioLanguage)).distinct()
            } else {
                // Fallback to device languages when original language is unknown.
                (deviceLanguages.mapNotNull(::normalize) +
                    listOfNotNull(normalize(secondaryPreferredAudioLanguage))).distinct()
            }
        }
        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
    }
}

/**
 * Returns the device's configured audio languages as ISO 639-2/B codes.
 */
internal fun resolveDeviceAudioLanguages(): List<String> {
    return if (Build.VERSION.SDK_INT >= 24) {
        val localeList = Resources.getSystem().configuration.locales
        List(localeList.size()) { localeList[it].isO3Language }
    } else {
        listOf(Resources.getSystem().configuration.locale.isO3Language)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Startup subtitle preparation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fetches addon subtitles for the current item, gated by the startup mode.
 * Fast-startup mode skips fetching entirely; preferred-only mode filters to
 * the user's preferred languages. The fetch is bounded by
 * [STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS] so video start is never blocked.
 */
internal suspend fun PlayerRuntimeController.prepareStartupSubtitles(
    mode: AddonSubtitleStartupMode,
    preferredLanguage: String,
    secondaryLanguage: String?,
    showOnlyPreferredLanguages: Boolean = false
): StartupSubtitlePreparation {
    val effectiveMode = if (showOnlyPreferredLanguages && mode == AddonSubtitleStartupMode.ALL_SUBTITLES) {
        AddonSubtitleStartupMode.PREFERRED_ONLY
    } else {
        mode
    }

    if (effectiveMode == AddonSubtitleStartupMode.FAST_STARTUP) {
        return StartupSubtitlePreparation(emptyList(), emptyList(), false)
    }
    if (buildSubtitleFetchRequest() == null) {
        return StartupSubtitlePreparation(emptyList(), emptyList(), false)
    }

    val preferredTargets = when (PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)) {
        "none" -> listOfNotNull(secondaryLanguage?.takeIf { it.isNotBlank() })
        else -> listOfNotNull(preferredLanguage, secondaryLanguage?.takeIf { it.isNotBlank() })
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }.distinct()

    if (effectiveMode == AddonSubtitleStartupMode.PREFERRED_ONLY && preferredTargets.isEmpty()) {
        return StartupSubtitlePreparation(emptyList(), emptyList(), false)
    }

    val loadingSubtitlesMessage = context.getString(R.string.player_loading_subtitles)
    _uiState.update {
        it.copy(
            isLoadingAddonSubtitles = true,
            addonSubtitlesError = null,
            loadingMessage = loadingSubtitlesMessage
        )
    }
    recordLoadingDiagnosticEvent(phase = "fetching_subtitles", message = loadingSubtitlesMessage)

    val fetchedSubtitles = withTimeoutOrNull(STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS) {
        fetchAddonSubtitlesNow(
            onProgress = { completed, total, addonName ->
                val msg = if (completed == 0) {
                    context.getString(R.string.player_loading_subtitles_from, total)
                } else if (addonName != null) {
                    context.getString(R.string.player_loading_subtitles_addon, addonName, completed, total)
                } else {
                    context.getString(R.string.player_loading_subtitles_progress, completed, total)
                }
                _uiState.update { it.copy(loadingMessage = msg) }
                recordLoadingDiagnosticEvent(
                    phase = "fetching_subtitles",
                    message = msg,
                    progress = if (total > 0) completed.toFloat() / total.toFloat() else null,
                    detail = addonName
                )
            }
        )
    } ?: run {
        recordLoadingDiagnosticEvent(
            phase = "fetching_subtitles_timeout",
            message = context.getString(R.string.player_loading_subtitles)
        )
        return StartupSubtitlePreparation(emptyList(), emptyList(), false)
    }

    val attachedSubtitles = when (effectiveMode) {
        AddonSubtitleStartupMode.ALL_SUBTITLES -> fetchedSubtitles
        AddonSubtitleStartupMode.PREFERRED_ONLY -> fetchedSubtitles.filter { subtitle ->
            preferredTargets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target) }
        }
        AddonSubtitleStartupMode.FAST_STARTUP -> emptyList()
    }
    val visibleSubtitles = if (showOnlyPreferredLanguages) attachedSubtitles else fetchedSubtitles

    return StartupSubtitlePreparation(
        fetchedSubtitles = visibleSubtitles,
        attachedSubtitles = attachedSubtitles,
        fetchCompleted = true
    ).also {
        recordLoadingDiagnosticEvent(
            phase = "fetching_subtitles_done",
            message = context.getString(R.string.player_loading_subtitles),
            detail = visibleSubtitles.size.toString()
        )
    }
}

/**
 * Clears all addon-subtitle selection state for a new stream.
 */
internal fun PlayerRuntimeController.resetAddonSubtitleStateForNewStream() {
    autoSubtitleSelected = subtitleDisabledByPersistedPreference || subtitleAddonRestoredByPersistedPreference
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    explicitSubtitleSelectionForEngineSwitch = null
    effectiveSubtitleSelectionForEngineSwitch = null
    attachedAddonSubtitleKeys = emptySet()
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
}

/**
 * Prepares the startup subtitle state for the current stream, resetting the
 * libass pipeline decision cache for a new URL and delegating to
 * [prepareStartupSubtitles].
 */
internal suspend fun PlayerRuntimeController.prepareStreamStartSubtitles(
    playerSettings: PlayerSettings
): StartupSubtitlePreparation {
    requestedUseLibassByUser = playerSettings.useLibass
    if (libassPipelineDecisionStreamUrl != currentStreamUrl) {
        libassPipelineDecisionStreamUrl = currentStreamUrl
        libassPipelineOverrideForCurrentStream = null
        libassPipelineSwitchInFlight = false
        hasDetectedAssSsaTrackForCurrentStream = false
    }
    resetAddonSubtitleStateForNewStream()
    return prepareStartupSubtitles(
        mode = playerSettings.addonSubtitleStartupMode,
        preferredLanguage = playerSettings.subtitleStyle.preferredLanguage,
        secondaryLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage,
        showOnlyPreferredLanguages = playerSettings.subtitleStyle.showOnlyPreferredLanguages
    )
}

/**
 * Records the attached addon subtitle keys and pushes the fetched subset into
 * UI state when the prefetch completed.
 */
internal fun PlayerRuntimeController.applyStartupSubtitlePreparation(startupSubtitlePreparation: StartupSubtitlePreparation) {
    attachedAddonSubtitleKeys = startupSubtitlePreparation.attachedSubtitles
        .distinctBy { addonSubtitleKey(it) }
        .map(::addonSubtitleKey)
        .toSet()
    if (!startupSubtitlePreparation.fetchCompleted) return
    _uiState.update {
        it.copy(
            addonSubtitles = startupSubtitlePreparation.fetchedSubtitles,
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
}

/**
 * Builds the ExoPlayer [SubtitleConfiguration] list for the attached addon
 * subtitles, deduplicated by id+url.
 */
internal fun PlayerRuntimeController.buildStartupSubtitleConfigurations(
    startupSubtitlePreparation: StartupSubtitlePreparation
): List<androidx.media3.common.MediaItem.SubtitleConfiguration> {
    return startupSubtitlePreparation.attachedSubtitles
        .distinctBy { "${it.id}|${it.url}" }
        .map(::toSubtitleConfiguration)
}

/**
 * Resets the loading overlay and per-stream telemetry/diagnostic state for a
 * new stream. Called at the top of [initializePlayer].
 */
internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    val preparingMessage = context.getString(R.string.player_loading_preparing)
    resetLoadingDiagnostics(phase = "preparing", message = preparingMessage, progress = null)
    hasRenderedFirstFrame = false
    hasMarkedCurrentEpisodeCompleted = false
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    timeoutRecoveryAttempts = 0
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    hasRetriedCurrentStreamAfter416 = false
    hasAttemptedDv7ToDv81ForCurrentPlayback = false
    isExperimentalDv7ToDv81ActiveForCurrentPlayback = false
    isVc1SoftwareFallbackActiveForCurrentPlayback = false
    isVc1TrackSelectionBypassActiveForCurrentPlayback = false
    isSafeAudioModeActiveForCurrentPlayback = false
    isAudioDisabledForCurrentPlayback = false
    dv7ToDv81BridgeVersionForCurrentPlayback = null
    dv7ToDv81LastProbeReasonForCurrentPlayback = null
    playerInitializationStartedAtMs = 0L
    pendingSeekTelemetryRequestedAtMs = 0L
    pendingSeekTelemetryTargetMs = -1L
    pendingSeekTelemetryReadyAtMs = 0L
    pendingSeekTelemetryReadyLatencyMs = -1L
    pendingSeekTelemetryAwaitingFirstFrame = false
    pendingSeekTelemetryReadyAssumed = false
    lastKnownDuration = 0L
    currentStreamHasVideoTrack = false
    currentVideoTrackIsLikelyVc1 = false
    currentVideoTrackMimeType = null
    currentVideoTrackCodecs = null
    currentVideoTrackWidth = 0
    currentVideoTrackHeight = 0
    currentVideoTrackBitrate = -1
    currentVideoTrackColorTransfer = null
    currentVideoTrackSelected = false
    currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
    lastLoggedVideoTrackSignature = null
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false,
            loadingMessage = preparingMessage,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            loadingProgress = null
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom renderers for audio/subtitles
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renderers factory that injects subtitle-offset and audio-delay adjustments
 * into the text and audio renderer pipelines, plus gain/downmix/passthrough
 * configuration for the audio sink.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val gainAudioProcessor: GainAudioProcessor,
    private val downmixEnabled: Boolean,
    private val audioOutputChannels: AudioOutputChannels,
    private val downmixNormalizationEnabled: Boolean,
    private val forceOpticalPassthrough: Boolean,
    private val playbackSpeedProvider: () -> Float,
    private val initialForcePcm: Boolean = false,
    private val onPlaybackSpeedAwareAudioSinkCreated: (PlaybackSpeedAwareAudioSink) -> Unit,
    private val onFfmpegAudioRendererChanged: (FfmpegAudioRenderer?) -> Unit
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gainAudioProcessor))
        if (forceOpticalPassthrough) {
            builder.setAudioCapabilities(buildStableAudioCapabilities(context, true))
        }
        val baseSink = builder.build()
        val playbackSpeedAwareSink = PlaybackSpeedAwareAudioSink(baseSink, initialForcePcm)
        playbackSpeedAwareSink.setInitialPlaybackSpeed(playbackSpeedProvider())
        onPlaybackSpeedAwareAudioSinkCreated(playbackSpeedAwareSink)
        return playbackSpeedAwareSink
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val playbackAwareSink = audioSink as? PlaybackSpeedAwareAudioSink
        val startIndex = out.size
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        if (playbackAwareSink != null && out.size > startIndex) {
            val mediaCodecIndex = (startIndex until out.size)
                .firstOrNull { index -> out[index] is MediaCodecAudioRenderer }
                ?: startIndex
            out[mediaCodecIndex] = PlaybackSpeedAwareAudioRenderer(
                rendererContext = context,
                codecAdapterFactory = getCodecAdapterFactory(),
                mediaCodecSelector = mediaCodecSelector,
                enableDecoderFallback = enableDecoderFallback,
                eventHandler = eventHandler,
                eventListener = eventListener,
                playbackSpeedAwareAudioSink = playbackAwareSink
            )
        }
        applyFfmpegRendererSettings(out)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = output,
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
                audioDelayUsProvider = audioDelayUsProvider
            )
        }
    }

    private fun applyFfmpegRendererSettings(out: ArrayList<Renderer>) {
        val ffmpegRenderers = out.filterIsInstance<FfmpegAudioRenderer>()
        ffmpegRenderers.forEach { renderer ->
            renderer.applyDownmixSettings(
                downmixEnabled = downmixEnabled,
                audioOutputChannels = audioOutputChannels,
                downmixNormalizationEnabled = downmixNormalizationEnabled,
                forceOpticalPassthrough = forceOpticalPassthrough
            )
        }
        onFfmpegAudioRendererChanged(ffmpegRenderers.firstOrNull())
    }
}

private fun FfmpegAudioRenderer.applyDownmixSettings(
    downmixEnabled: Boolean,
    audioOutputChannels: AudioOutputChannels,
    downmixNormalizationEnabled: Boolean,
    forceOpticalPassthrough: Boolean
) {
    setForceOpticalPassthrough(forceOpticalPassthrough)
    if (downmixEnabled) {
        setAudioOutputChannels(audioOutputChannels.ffmpegLayoutName, audioOutputChannels.channelCount)
        setDownmixNormalizationEnabled(downmixNormalizationEnabled)
    } else {
        setAudioOutputChannels(null, 0)
        setDownmixNormalizationEnabled(false)
    }
}

/**
 * Text output wrapper that normalizes cue positions (for VTT sidecar
 * subtitles) and fixes RTL punctuation/bidi wrapping for Arabic and Hebrew.
 */
private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        val processed = cueGroup.cues.map { transformCue(it) }
        delegate.onCues(CueGroup(processed, cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        val processed = cues.map { transformCue(it) }
        delegate.onCues(processed)
    }

    private fun transformCue(cue: Cue): Cue {
        var c = fixRtlCueText(cue)
        if (shouldNormalizeCuePositionProvider()) c = normalizeCuePosition(c)
        return c
    }

    private fun normalizeCuePosition(cue: Cue): Cue {
        if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
            return cue
        }
        return cue.buildUpon()
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .build()
    }

    private fun fixRtlCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue

        // Arabic: wrap each physical line with RLE (\u202B) ... PDF (\u202C).
        // This renders boundary punctuation and auto-wrapped lines as RTL in
        // an LTR container.
        if (containsArabic(text)) {
            val builder = android.text.SpannableStringBuilder()
            val lines = text.splitByNewlines()
            for (i in lines.indices) {
                if (i > 0) builder.append("\n")
                // Clear existing directional markers -> prevents double wrapping
                // upon re-execution (idempotent).
                val line = lines[i].stripDirectionalWrap()
                if (line.isEmpty()) {
                    builder.append(line)
                    continue
                }
                // Keep the trailing CR (paragraph separator) OUTSIDE of the
                // embedding; otherwise it terminates the RLE run and leaves
                // the PDF orphan.
                val hasCr = line[line.length - 1] == '\r'
                val core = if (hasCr) line.subSequence(0, line.length - 1) else line
                if (core.isEmpty()) {
                    builder.append(line)
                    continue
                }
                builder.append("\u202B").append(core).append("\u202C")
                if (hasCr) builder.append("\r")
            }
            if (builder.contentEquals(text)) return cue
            return cue.buildUpon().setText(builder).build()
        }

        // Hebrew / other RTL: punctuation boundary-swap method (span preserving).
        if (containsRtlChars(text)) {
            val builder = android.text.SpannableStringBuilder()
            val lines = text.splitByNewlines()
            var changed = false
            for (i in lines.indices) {
                if (i > 0) builder.append("\n")
                val line = lines[i]
                val fixed = fixRtlPunctuationForLtr(line)
                if (fixed !== line) changed = true
                builder.append(fixed)
            }
            if (!changed) return cue
            return cue.buildUpon().setText(builder).build()
        }
        return cue
    }

    private fun containsArabic(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint in 0x0600..0x06FF || // Arabic block
                codePoint in 0x0750..0x077F || // Arabic Supplement
                codePoint in 0x0870..0x08FF || // Arabic Extended
                codePoint in 0xFB50..0xFDFF || // Arabic Presentation Forms-A
                codePoint in 0xFE70..0xFEFF || // Arabic Presentation Forms-B
                Character.getDirectionality(codePoint) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    // Take CharSequence instead of String -> preserve spans.
    private fun fixRtlPunctuationForLtr(line: CharSequence): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var start = 0
        while (start < end0 && isRtlPunctuation(line[start])) start++
        var end = end0
        while (end > start && isRtlPunctuation(line[end - 1])) end--
        if (start == 0 && end == end0) return line

        val out = android.text.SpannableStringBuilder()
        out.append(line.subSequence(end, end0))   // trailing punct -> front
            .append(line.subSequence(start, end)) // middle
            .append(line.subSequence(0, start))    // leading punct -> end
        if (hasCr) out.append("\r")
        return out
    }

    // Clears existing directional control characters (idempotency + legacy
    // RLM/LRE remnants).
    private fun CharSequence.stripDirectionalWrap(): CharSequence {
        val hasMarker = (0 until length).any { isDirectionalMark(this[it]) }
        if (!hasMarker) return this
        val sb = android.text.SpannableStringBuilder(this)
        var k = 0
        while (k < sb.length) {
            if (isDirectionalMark(sb[k])) sb.delete(k, k + 1) else k++
        }
        return sb
    }

    private fun isDirectionalMark(c: Char): Boolean =
        c == '\u202A' || c == '\u202B' || c == '\u202C' || // LRE / RLE / PDF
            c == '\u200E' || c == '\u200F'                  // LRM / RLM

    private fun CharSequence.splitByNewlines(): List<CharSequence> {
        val result = mutableListOf<CharSequence>()
        var start = 0
        var i = 0
        while (i < this.length) {
            if (this[i] == '\n') {
                result.add(this.subSequence(start, i))
                start = i + 1
            }
            i++
        }
        result.add(this.subSequence(start, this.length))
        return result
    }

    private fun isRtlPunctuation(ch: Char): Boolean = ch in RTL_PUNCTUATION || ch.isWhitespace()

    private fun containsRtlChars(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            // Direct Unicode range checks for Hebrew and Arabic scripts.
            if (codePoint in 0x0590..0x05FF || // Hebrew block (letters, points, punctuation)
                codePoint in 0xFB1D..0xFB4F || // Hebrew Presentation Forms
                codePoint in 0x0600..0x06FF || // Arabic block
                codePoint in 0x0750..0x077F || // Arabic Supplement
                codePoint in 0x0870..0x08FF || // Arabic Extended
                codePoint in 0xFB50..0xFDFF || // Arabic Presentation Forms-A
                codePoint in 0xFE70..0xFEFF    // Arabic Presentation Forms-B
            ) {
                return true
            }
            val d = Character.getDirectionality(codePoint)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                d == Character.DIRECTIONALITY_ARABIC_NUMBER
            ) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    companion object {
        private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '\'', '"')
    }
}

/**
 * Forwards renderer that shifts subtitle render timestamps by the user's
 * subtitle/audio delay offsets.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val subtitleOffsetUs = subtitleDelayUsProvider()
        val audioOffsetUs = audioDelayUsProvider()
        val adjustedPositionUs = (positionUs + audioOffsetUs - subtitleOffsetUs).coerceAtLeast(0L)
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PlaybackException classification helpers
// ─────────────────────────────────────────────────────────────────────────────

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun PlaybackException.isDolbyVisionDecoderFailure(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("dolby-vision", ignoreCase = true) &&
        details.contains("decoder failed", ignoreCase = true)
}

private fun PlaybackException.isUnexpectedLoaderNullPointer(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("unexpected nullpointerexception", ignoreCase = true) ||
        (details.contains("nullpointerexception", ignoreCase = true) &&
            details.contains("matroskaextractor", ignoreCase = true))
}

private fun PlaybackException.isAudioTrackFailure(): Boolean {
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return isAudioTrackFailure(errorCode, details)
}

private fun PlaybackException.isStuckPlayingNoProgress(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_TIMEOUT) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("stuck playing with no progress", ignoreCase = true)
}

private fun PlaybackException.isMediaPeriodHolderStateCrash(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("mediaperiodholder", ignoreCase = true) &&
        details.contains(".info", ignoreCase = true) &&
        details.contains("null", ignoreCase = true)
}

private fun String.safeHost(): String =
    runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")

/**
 * Parses the DV profile number from a codec string, e.g. "dvhe.07.06" gives 7.
 * Used as a fallback when libdovi bridge hasn't loaded (e.g. HDR10_BASE_LAYER
 * mode strips DV before the bridge runs, so its source-profile detector never
 * sees the stream).
 */
private fun parseDvProfileFromCodecString(codecs: String?): Int? {
    if (codecs.isNullOrBlank()) return null
    val match = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.").find(codecs.trim().lowercase()) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/** Human-friendly codec name for the diagnostics card. */
private fun friendlyVideoCodecName(mimeType: String?, codecs: String?): String? {
    val mime = mimeType?.lowercase()
    return when {
        mime == null -> null
        mime == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        mime == MimeTypes.VIDEO_H265 -> "HEVC"
        mime == MimeTypes.VIDEO_H264 -> "H.264"
        mime == MimeTypes.VIDEO_AV1 -> "AV1"
        mime == MimeTypes.VIDEO_VP9 -> "VP9"
        mime.startsWith("video/") -> mime.removePrefix("video/").uppercase()
        else -> codecs ?: mime
    }
}

/**
 * Human-friendly HDR/output type for the diagnostics card — reflects what is
 * actually output, not just the source track mime. When DV7 is stripped to
 * the HDR10 base layer the output is HDR10/SDR even though the track mime is
 * DV.
 */
private fun friendlyVideoHdrType(
    mimeType: String?,
    colorTransfer: Int?,
    effectiveModeName: String?,
    dvConversionOccurred: Boolean
): String? {
    val isDolbyVisionMime = mimeType?.lowercase() == MimeTypes.VIDEO_DOLBY_VISION
    fun fromTransfer(): String? = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
    return when {
        // Ignore DV data: output is HDR10/SDR, never Dolby Vision.
        effectiveModeName == "HDR10_BASE_LAYER" -> fromTransfer() ?: "HDR10"
        // DV RPU stripped: output is HDR10 base layer, never Dolby Vision.
        effectiveModeName == "STRIP_DV" -> fromTransfer() ?: "HDR10"
        // DV8.1 conversion, but only label it DV if a conversion actually ran.
        // AUTO arms this mode for every file on a DV display, so plain
        // SDR/HDR10 lands here too.
        effectiveModeName == "DV81_LIBDOVI" && dvConversionOccurred -> "Dolby Vision"
        effectiveModeName == "DV81_LIBDOVI" -> fromTransfer()
        // Native DV passthrough.
        isDolbyVisionMime -> "Dolby Vision"
        else -> fromTransfer()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun createDolbyVisionFallbackCodecSelector(
    convertToDv81Active: Boolean = false
): MediaCodecSelector {
    // Stripping DV7 to its HEVC base layer is handled by the renderer
    // (setMapDV7ToHevc), which only touches profile 7. We must NOT force
    // video/dolby-vision to the HEVC decoder here: that also catches DV5,
    // which has no HDR10 base layer and ends up decoded without its
    // reshaping (wrong colors). DV5 keeps the DV decoder.
    if (!convertToDv81Active) return MediaCodecSelector.DEFAULT
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType, requiresSecureDecoder, requiresTunnelingDecoder
        )
        if (mimeType != MimeTypes.VIDEO_DOLBY_VISION || defaults.isNotEmpty()) {
            return@MediaCodecSelector defaults
        }
        DolbyVisionCodecFallback.findDvDecodersIgnoringProfile()
    }
}

private fun describeExtensionRendererMode(mode: Int): String = when (mode) {
    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> "off"
    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> "on"
    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> "prefer"
    else -> mode.toString()
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun DefaultRenderersFactory.applyMapDv7ToHevcIfSupported(enabled: Boolean): DefaultRenderersFactory {
    return runCatching {
        val method = javaClass.getMethod("setMapDV7ToHevc", Boolean::class.javaPrimitiveType)
        method.invoke(this, enabled)
        this
    }.getOrElse { this }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun buildStableAudioCapabilities(context: Context, forceOpticalPassthrough: Boolean = false): AudioCapabilities {
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    val supportedEncodings = mutableListOf<Int>()
    val knownEncodings = intArrayOf(
        C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_AC4, C.ENCODING_DTS,
        C.ENCODING_E_AC3_JOC, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD
    )
    for (encoding in knownEncodings) {
        if (detected.supportsEncoding(encoding)) {
            supportedEncodings += encoding
        }
    }
    if ((detected.supportsEncoding(C.ENCODING_DTS_HD) || detected.supportsEncoding(C.ENCODING_DTS_UHD_P2)) &&
        C.ENCODING_DTS !in supportedEncodings
    ) {
        supportedEncodings += C.ENCODING_DTS
    }
    if (forceOpticalPassthrough) {
        val forced = intArrayOf(
            C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS, C.ENCODING_DTS_HD
        )
        for (encoding in forced) {
            if (encoding !in supportedEncodings) supportedEncodings += encoding
        }
    }
    val maxChannelCount = if (forceOpticalPassthrough) {
        maxOf(detected.maxChannelCount, 8)
    } else {
        detected.maxChannelCount
    }
    return AudioCapabilities(supportedEncodings.toIntArray(), maxChannelCount)
}

/**
 * Bandwidth meter wrapper that floors HLS estimates at a high bitrate so the
 * adaptive track selector doesn't downshift on streams where the manifest
 * advertises low bitrates but the segments are much larger.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SafeBandwidthMeter(
    private val delegate: BandwidthMeter,
    private val isHls: Boolean
) : BandwidthMeter {
    override fun getBitrateEstimate(): Long {
        val raw = delegate.bitrateEstimate
        return if (isHls) maxOf(raw, 25_000_000L) else raw
    }

    override fun getTimeToFirstByteEstimateUs(): Long = delegate.timeToFirstByteEstimateUs

    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = delegate.transferListener

    override fun addEventListener(eventHandler: android.os.Handler, eventListener: BandwidthMeter.EventListener) {
        delegate.addEventListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        delegate.removeEventListener(eventListener)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// First-frame diagnostics
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Records the first-frame diagnostics snapshot: startup latency, DV conversion
 * counts, video codec/HDR type, bitrate, rebuffer totals, and the back-buffer
 * resolution for confirmed DV7 on low-RAM devices. Persists the snapshot to
 * DataStore and returns the updated diagnostics.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.recordFirstFrameDiagnostics(
    player: ExoPlayer,
    currentDiagnostics: LastPlaybackDiagnostics,
    playerSettings: PlayerSettings
): LastPlaybackDiagnostics {
    val startupMs = (System.currentTimeMillis() - playerInitializationStartedAtMs).coerceAtLeast(0L)
    val conversionCalls = DoviBridge.getConversionCallCount()
    val conversionSucceeded = DoviBridge.getConversionSuccessCount()
    val signalingRewrites = DolbyVisionConversionStats.getCodecStringRewriteCount()
    val sourceProfile = DolbyVisionConversionStats.getLastSourceProfile()
        ?: parseDvProfileFromCodecString(currentVideoTrackCodecs)
    val conversionAttempted = hasAttemptedDv7ToDv81ForCurrentPlayback || conversionCalls > 0 || signalingRewrites > 0
    if (pendingSeekTelemetryAwaitingFirstFrame && pendingSeekTelemetryRequestedAtMs > 0L) {
        pendingSeekTelemetryRequestedAtMs = 0L
        pendingSeekTelemetryTargetMs = -1L
        pendingSeekTelemetryReadyAtMs = 0L
        pendingSeekTelemetryReadyLatencyMs = -1L
        pendingSeekTelemetryAwaitingFirstFrame = false
    }

    val clickToFirstFrameMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    val playbackSnapshot = playbackAnalyticsDiagnostics.snapshot(
        player = player,
        hasRenderedFirstFrame = true,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        rebufferStartedAtMs = rebufferStartedAtMs
    )
    playbackAnalyticsDiagnostics.recordRawEventLine(
        "PLAYBACK_STARTUP: clickToFirstFrameMs=$clickToFirstFrameMs " +
            "initToFirstFrameMs=$startupMs playbackSpeed=${player.playbackParameters.speed} " +
            "pitch=${player.playbackParameters.pitch} " +
            "startPositionMs=${player.currentPosition.coerceAtLeast(0L)} " +
            "currentPositionMs=${player.currentPosition.coerceAtLeast(0L)} " +
            "bufferedMs=${player.bufferedPosition.coerceAtLeast(0L)} " +
            "durationMs=${player.duration.takeIf { it > 0L } ?: -1L} " +
            "video=${playbackSnapshot.videoFormat?.sampleMimeType ?: currentVideoTrackMimeType ?: "n/a"} " +
            "codecs=${playbackSnapshot.videoFormat?.codecs ?: currentVideoTrackCodecs ?: "n/a"} " +
            "size=${playbackSnapshot.videoFormat?.width ?: currentVideoTrackWidth}x${playbackSnapshot.videoFormat?.height ?: currentVideoTrackHeight} " +
            "frameRate=${playbackSnapshot.videoFormat?.frameRate ?: -1f} " +
            "bitrate=${playbackSnapshot.videoFormat?.bitrate ?: -1} " +
            "bandwidthBps=${playbackSnapshot.bandwidthEstimateBps ?: -1L} " +
            "loads=${playbackSnapshot.loadCompletedCount}/${playbackSnapshot.loadStartedCount} " +
            "bytesLoaded=${playbackSnapshot.totalBytesLoaded} droppedFrames=${playbackSnapshot.droppedFrames} " +
            "audioUnderruns=${playbackSnapshot.audioUnderrunCount} rebufferCount=$rebufferCount " +
            "host=${currentStreamUrl.safeHost()} engine=$currentInternalPlayerEngine"
    )

    val dvConversionOccurred = conversionSucceeded > 0 ||
        signalingRewrites > 0 ||
        sourceProfile != null

    resolveBackBufferAfterFirstFrame(playerSettings, conversionSucceeded, dvConversionOccurred)

    val finalDiagnostics = currentDiagnostics.copy(
        firstFrameMs = startupMs,
        dv7DoviCalls = conversionCalls.toInt(),
        dv7DoviSuccess = conversionSucceeded.toInt(),
        dv7DoviSignalRewrites = signalingRewrites.toInt(),
        dvSourceProfile = sourceProfile?.toString(),
        videoResolution = if (currentVideoTrackWidth > 0 && currentVideoTrackHeight > 0)
            "${currentVideoTrackWidth}x${currentVideoTrackHeight}" else null,
        videoCodec = friendlyVideoCodecName(currentVideoTrackMimeType, currentVideoTrackCodecs),
        videoHdrType = friendlyVideoHdrType(
            currentVideoTrackMimeType,
            currentVideoTrackColorTransfer,
            currentDiagnostics.dv7ModeEffective,
            dvConversionOccurred
        ),
        videoBitrate = computeVideoBitrate(player),
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        result = "Played"
    )
    lastPlaybackDiagnosticsForReport = finalDiagnostics
    scope.launch { runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(finalDiagnostics) } }
    return finalDiagnostics
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.resolveBackBufferAfterFirstFrame(
    playerSettings: PlayerSettings,
    conversionSucceeded: Long,
    dvConversionOccurred: Boolean
) {
    val lc = currentBitrateAwareLoadControl ?: return
    val budgetManaged = playerSettings.bufferBudgetManaged
    val keepZeroForDv7 = budgetManaged && conversionSucceeded > 0L && MemoryBudget.isLowRamTier
    val resolvedBackBufferMs = if (keepZeroForDv7) 0 else configuredBackBufferMs
    if (resolvedBackBufferMs != effectiveBackBufferDurationMs) {
        lc.setBackBufferDurationOverrideMs(resolvedBackBufferMs)
        effectiveBackBufferDurationMs = resolvedBackBufferMs
    }
    if (keepZeroForDv7) {
        lc.setBudgetBytesOverride(MemoryBudget.conversionBudgetMb.toLong() * 1024L * 1024L)
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "BACK_BUFFER_RESOLVED: dvConversion=$dvConversionOccurred " +
            "lowRam=${MemoryBudget.isLowRamTier} " +
            "resolvedBackBufferMs=$resolvedBackBufferMs " +
            "managed=$budgetManaged " +
            "budgetMb=${when {
                keepZeroForDv7 -> MemoryBudget.conversionBudgetMb
                budgetManaged -> MemoryBudget.budgetMb
                else -> MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
            }} host=${currentStreamUrl.safeHost()}"
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.computeVideoBitrate(player: ExoPlayer): Int {
    val durationMsVal = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    val sizeBytes = currentVideoSize
    return if (sizeBytes != null && sizeBytes > 0L && durationMsVal > 0L) {
        val durationSecs = durationMsVal / 1000.0
        ((sizeBytes * 8.0) / durationSecs).toInt()
    } else {
        currentVideoTrackBitrate
    }
}
