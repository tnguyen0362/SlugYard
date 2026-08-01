package com.sluggyard.tv.data.repository

import android.net.Uri
import android.os.Build
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.core.player.LastPlaybackDiagnostics
import com.sluggyard.tv.data.remote.api.PlaybackIssueReportApi
import com.sluggyard.tv.data.remote.dto.PlaybackIssueAppDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueContentDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueDeviceDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueDiagnosticsDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueErrorDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueLoadingDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueLoadingEventDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlayerDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlaybackAnalyticsDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlaybackEventDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlaybackFormatDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlaybackHealthSnapshotDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlaybackLoadDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlaybackLoadErrorDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssuePlaybackSettingsDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueReportRequestDto
import com.sluggyard.tv.data.remote.dto.PlaybackIssueStreamDto
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inputs collected at the point a user files a playback-issue report. All
 * fields are plain values so the structure can be assembled off the player
 * thread and serialized into the report payload by [PlaybackIssueReportRepository].
 */
data class PlaybackIssueReportInput(
    val diagnostics: LastPlaybackDiagnostics,
    val error: PlaybackIssueErrorInput,
    val title: String?,
    val contentName: String?,
    val contentId: String?,
    val contentType: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val releaseYear: String?,
    val streamUrl: String,
    val streamMimeType: String?,
    val streamName: String?,
    val addonName: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val playerEngine: String,
    val loading: PlaybackIssueLoadingInput,
    val positionMs: Long?,
    val durationMs: Long?,
    val bufferedPositionMs: Long?,
    val selectedAudioTrack: String?,
    val selectedSubtitleTrack: String?,
    val isTorrentStream: Boolean,
    val playbackSettings: PlaybackIssuePlaybackSettingsInput?,
    val playbackAnalytics: PlaybackIssuePlaybackAnalyticsInput?
)

data class PlaybackIssueLoadingInput(
    val phase: String,
    val message: String?,
    val progress: Float?,
    val elapsedMs: Long,
    val phaseElapsedMs: Long,
    val reportReason: String,
    val loadingOverlayVisible: Boolean,
    val loadingStatusVisible: Boolean,
    val hasRenderedFirstFrame: Boolean,
    val exoPlayerCreated: Boolean,
    val exoPlaybackState: Int?,
    val exoPlaybackStateName: String?,
    val exoIsLoading: Boolean?,
    val exoPlayWhenReady: Boolean?,
    val mpvAttached: Boolean,
    val startupRetryCount: Int,
    val errorRetryCount: Int,
    val timeoutRecoveryAttempts: Int,
    val isLoadingAddonSubtitles: Boolean,
    val addonSubtitlesCount: Int,
    val isLoadingSourceStreams: Boolean,
    val isLoadingEpisodeStreams: Boolean,
    val torrentDownloadSpeed: Long,
    val torrentPeers: Int,
    val torrentSeeds: Int,
    val rawEventLines: List<String>,
    val events: List<PlaybackIssueLoadingEventInput>
)

data class PlaybackIssueLoadingEventInput(
    val timeMs: Long,
    val elapsedMs: Long,
    val phase: String,
    val message: String?,
    val progress: Float?,
    val detail: String?
)

data class PlaybackIssueErrorInput(
    val displayMessage: String?,
    val errorCode: Int?,
    val errorCodeName: String?,
    val exceptionClass: String?,
    val causeClass: String?,
    val causeMessage: String?,
    val httpStatus: Int?
)

data class PlaybackIssuePlaybackSettingsInput(
    val playerPreference: String,
    val internalPlayerEngine: String,
    val resolvedInternalPlayerEngine: String,
    val autoSwitchInternalPlayerOnError: Boolean,
    val decoderPriority: Int,
    val decoderPriorityName: String,
    val effectiveDecoderPriority: Int,
    val effectiveDecoderPriorityName: String,
    val downmixEnabled: Boolean,
    val audioOutputChannels: String,
    val maintainOriginalAudioOnDownmix: Boolean,
    val tunnelingEnabled: Boolean,
    val tunnelingEffective: Boolean,
    val forceOpticalPassthrough: Boolean,
    val skipSilence: Boolean,
    val audioAmplificationDb: Int,
    val centerMixLevelDb: Int,
    val persistAudioAmplification: Boolean,
    val rememberAudioDelayPerDevice: Boolean,
    val preferredAudioLanguage: String,
    val secondaryPreferredAudioLanguage: String?,
    val preferredSubtitleLanguage: String,
    val secondaryPreferredSubtitleLanguage: String?,
    val useForcedSubtitles: Boolean,
    val showOnlyPreferredSubtitleLanguages: Boolean,
    val useLibass: Boolean,
    val activePlayerUsesLibass: Boolean,
    val libassRenderType: String,
    val addonSubtitleStartupMode: String,
    val externalPlayerForwardSubtitles: Boolean,
    val subtitleOrganizationMode: String,
    val loadingOverlayEnabled: Boolean,
    val showPlayerLoadingStatus: Boolean,
    val playbackIssueReportsEnabled: Boolean,
    val dv5ToDv81Enabled: Boolean,
    val dv7ToDv81PreserveMappingEnabled: Boolean,
    val dv7HandlingMode: String,
    val dv7LibdoviModeOverride: Int,
    val stripHdr10PlusSei: Boolean,
    val mpvHardwareDecodeMode: String,
    val frameRateMatchingMode: String,
    val resolutionMatchingEnabled: Boolean,
    val resizeMode: Int,
    val aspectMode: String,
    val bufferEngineEnabled: Boolean,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferSizeMb: Int,
    val backBufferDurationMs: Int,
    val effectiveBackBufferDurationMs: Int,
    val retainBackBufferFromKeyframe: Boolean,
    val parallelNetworkEnabled: Boolean,
    val bufferBudgetManaged: Boolean,
    val allowLargeTargetBuffer: Boolean,
    val vodCacheEnabled: Boolean,
    val vodCacheSizeMode: String,
    val vodCacheSizeMb: Int,
    val useParallelConnections: Boolean,
    val parallelConnectionCount: Int,
    val parallelChunkSizeKb: Int,
    val enableHttp2: Boolean,
    val exoPerformanceModeEnabled: Boolean,
    val streamAutoPlayMode: String,
    val streamAutoPlaySource: String,
    val streamAutoPlayNextEpisodeEnabled: Boolean,
    val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean,
    val streamAutoPlayReuseBingeGroup: Boolean,
    val streamAutoPlayTimeoutSeconds: Int,
    val stillWatchingEnabled: Boolean,
    val stillWatchingEpisodeThreshold: Int,
    val nextEpisodeThresholdMode: String,
    val nextEpisodeThresholdPercent: Float,
    val nextEpisodeThresholdMinutesBeforeEnd: Float,
    val streamReuseLastLinkEnabled: Boolean,
    val streamReuseLastLinkCacheHours: Int
)

/**
 * Uploads a playback-issue report to the configured endpoint. All free-text
 * fields are length-capped and scrubbed of sensitive material (URLs, auth
 * headers, tokens) before serialization.
 */
@Singleton
class PlaybackIssueReportRepository @Inject constructor(
    private val playbackIssueReportApi: PlaybackIssueReportApi
) {
    suspend fun submit(input: PlaybackIssueReportInput): Result<String> = runCatching {
        if (BuildConfig.PLAYBACK_REPORTS_BASE_URL.isBlank()) {
            error("Playback report endpoint is not configured")
        }
        val response = playbackIssueReportApi.createPlaybackIssueReport(input.toDto())
        if (!response.isSuccessful) {
            error("Playback report upload failed: HTTP ${response.code()}")
        }
        val body = response.body()
        body?.reportId?.trim()?.takeIf { it.isNotBlank() }
            ?: body?.id?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Playback report upload failed: missing report id")
    }

    private fun PlaybackIssueReportInput.toDto(): PlaybackIssueReportRequestDto {
        val streamUri = runCatching { Uri.parse(streamUrl) }.getOrNull()
        val urlWithoutQuery = streamUri?.stripQueryAndFragment()
        return PlaybackIssueReportRequestDto(
            schemaVersion = 1,
            createdAtMs = System.currentTimeMillis(),
            app = PlaybackIssueAppDto(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                debugBuild = BuildConfig.IS_DEBUG_BUILD
            ),
            device = PlaybackIssueDeviceDto(
                manufacturer = Build.MANUFACTURER.orEmpty().cap(80),
                brand = Build.BRAND.orEmpty().cap(80),
                model = Build.MODEL.orEmpty().cap(120),
                product = Build.PRODUCT.orEmpty().cap(120),
                androidRelease = Build.VERSION.RELEASE.orEmpty().cap(40),
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.orEmpty().map { it.cap(40) }
            ),
            content = PlaybackIssueContentDto(
                title = title.scrub(160),
                contentName = contentName.scrub(160),
                contentId = contentId.scrub(160),
                contentType = contentType.scrub(60),
                videoId = videoId.scrub(160),
                season = season,
                episode = episode,
                episodeTitle = episodeTitle.scrub(160),
                releaseYear = releaseYear.scrub(20)
            ),
            stream = PlaybackIssueStreamDto(
                host = streamUri?.host.scrub(160) ?: diagnostics.host.scrub(160),
                scheme = streamUri?.scheme.scrub(24),
                port = streamUri?.port?.takeIf { it >= 0 },
                urlHash = streamUrl.sha256(),
                urlWithoutQueryHash = urlWithoutQuery.sha256(),
                fileExtension = streamUri.fileExtension(),
                mimeType = streamMimeType.scrub(120),
                streamName = streamName.scrub(160),
                addonName = addonName.scrub(120),
                videoHash = videoHash.scrub(160),
                videoSize = videoSize,
                requestHeaderNames = requestHeaders.safeHeaderNames(),
                responseHeaderNames = responseHeaders.safeHeaderNames()
            ),
            player = PlaybackIssuePlayerDto(
                engine = playerEngine.cap(80),
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                audioTrack = selectedAudioTrack.scrub(160),
                subtitleTrack = selectedSubtitleTrack.scrub(160),
                isTorrentStream = isTorrentStream
            ),
            loading = loading.toDto(),
            error = PlaybackIssueErrorDto(
                displayMessage = error.displayMessage.scrub(1000),
                errorCode = error.errorCode,
                errorCodeName = error.errorCodeName.scrub(120),
                exceptionClass = error.exceptionClass.scrub(160),
                causeClass = error.causeClass.scrub(160),
                causeMessage = error.causeMessage.scrub(1000),
                httpStatus = error.httpStatus
            ),
            diagnostics = diagnostics.toDto(),
            playbackSettings = playbackSettings?.toDto(),
            playbackAnalytics = playbackAnalytics?.toDto()
        )
    }

    private fun PlaybackIssueLoadingInput.toDto(): PlaybackIssueLoadingDto =
        PlaybackIssueLoadingDto(
            phase = phase.cap(80),
            message = message.scrub(240),
            progress = progress?.coerceIn(0f, 1f),
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            phaseElapsedMs = phaseElapsedMs.coerceAtLeast(0L),
            reportReason = reportReason.cap(80),
            loadingOverlayVisible = loadingOverlayVisible,
            loadingStatusVisible = loadingStatusVisible,
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            exoPlayerCreated = exoPlayerCreated,
            exoPlaybackState = exoPlaybackState,
            exoPlaybackStateName = exoPlaybackStateName.scrub(80),
            exoIsLoading = exoIsLoading,
            exoPlayWhenReady = exoPlayWhenReady,
            mpvAttached = mpvAttached,
            startupRetryCount = startupRetryCount.coerceAtLeast(0),
            errorRetryCount = errorRetryCount.coerceAtLeast(0),
            timeoutRecoveryAttempts = timeoutRecoveryAttempts.coerceAtLeast(0),
            isLoadingAddonSubtitles = isLoadingAddonSubtitles,
            addonSubtitlesCount = addonSubtitlesCount.coerceAtLeast(0),
            isLoadingSourceStreams = isLoadingSourceStreams,
            isLoadingEpisodeStreams = isLoadingEpisodeStreams,
            torrentDownloadSpeed = torrentDownloadSpeed.coerceAtLeast(0L),
            torrentPeers = torrentPeers.coerceAtLeast(0),
            torrentSeeds = torrentSeeds.coerceAtLeast(0),
            rawEventLines = rawEventLines.takeLast(120).mapNotNull { it.scrubLogLine(2000) },
            events = events.takeLast(80).map { event ->
                PlaybackIssueLoadingEventDto(
                    timeMs = event.timeMs,
                    elapsedMs = event.elapsedMs.coerceAtLeast(0L),
                    phase = event.phase.cap(80),
                    message = event.message.scrub(240),
                    progress = event.progress?.coerceIn(0f, 1f),
                    detail = event.detail.scrub(240)
                )
            }
        )

    private fun LastPlaybackDiagnostics.toDto(): PlaybackIssueDiagnosticsDto =
        PlaybackIssueDiagnosticsDto(
            timestampMs = timestampMs,
            host = host.cap(160),
            hdrCapsKnown = hdrCapsKnown,
            displayDv = displayDv,
            displayHdr10 = displayHdr10,
            displayHdr10Plus = displayHdr10Plus,
            codecDv7Supported = codecDv7Supported,
            dv81DecoderName = dv81DecoderName.scrub(160),
            bridgeReady = bridgeReady,
            bridgeVersion = bridgeVersion.cap(120),
            bridgeReason = bridgeReason.scrub(180),
            dv7ModeRequested = dv7ModeRequested.cap(80),
            dv7ModeEffective = dv7ModeEffective.cap(80),
            dv7AutoDecision = dv7AutoDecision.scrub(80),
            bufferEngineEnabled = bufferEngineEnabled,
            parallelNetworkEnabled = parallelNetworkEnabled,
            firstFrameMs = firstFrameMs,
            dv7DoviCalls = dv7DoviCalls,
            dv7DoviSuccess = dv7DoviSuccess,
            dv7DoviSignalRewrites = dv7DoviSignalRewrites,
            dvSourceProfile = dvSourceProfile.scrub(80),
            videoResolution = videoResolution.scrub(80),
            videoCodec = videoCodec.scrub(120),
            videoHdrType = videoHdrType.scrub(120),
            rebufferCount = rebufferCount,
            rebufferTotalMs = rebufferTotalMs,
            result = result.cap(1000)
        )

    private fun PlaybackIssuePlaybackSettingsInput.toDto(): PlaybackIssuePlaybackSettingsDto =
        PlaybackIssuePlaybackSettingsDto(
            playerPreference = playerPreference.cap(60),
            internalPlayerEngine = internalPlayerEngine.cap(60),
            resolvedInternalPlayerEngine = resolvedInternalPlayerEngine.cap(60),
            autoSwitchInternalPlayerOnError = autoSwitchInternalPlayerOnError,
            decoderPriority = decoderPriority.coerceIn(0, 2),
            decoderPriorityName = decoderPriorityName.cap(80),
            effectiveDecoderPriority = effectiveDecoderPriority.coerceIn(0, 2),
            effectiveDecoderPriorityName = effectiveDecoderPriorityName.cap(80),
            downmixEnabled = downmixEnabled,
            audioOutputChannels = audioOutputChannels.cap(40),
            maintainOriginalAudioOnDownmix = maintainOriginalAudioOnDownmix,
            tunnelingEnabled = tunnelingEnabled,
            tunnelingEffective = tunnelingEffective,
            forceOpticalPassthrough = forceOpticalPassthrough,
            skipSilence = skipSilence,
            audioAmplificationDb = audioAmplificationDb.coerceIn(0, 10),
            centerMixLevelDb = centerMixLevelDb.coerceIn(-10, 30),
            persistAudioAmplification = persistAudioAmplification,
            rememberAudioDelayPerDevice = rememberAudioDelayPerDevice,
            preferredAudioLanguage = preferredAudioLanguage.cap(40),
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage.scrub(40),
            preferredSubtitleLanguage = preferredSubtitleLanguage.cap(40),
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage.scrub(40),
            useForcedSubtitles = useForcedSubtitles,
            showOnlyPreferredSubtitleLanguages = showOnlyPreferredSubtitleLanguages,
            useLibass = useLibass,
            activePlayerUsesLibass = activePlayerUsesLibass,
            libassRenderType = libassRenderType.cap(80),
            addonSubtitleStartupMode = addonSubtitleStartupMode.cap(80),
            externalPlayerForwardSubtitles = externalPlayerForwardSubtitles,
            subtitleOrganizationMode = subtitleOrganizationMode.cap(80),
            loadingOverlayEnabled = loadingOverlayEnabled,
            showPlayerLoadingStatus = showPlayerLoadingStatus,
            playbackIssueReportsEnabled = playbackIssueReportsEnabled,
            dv5ToDv81Enabled = dv5ToDv81Enabled,
            dv7ToDv81PreserveMappingEnabled = dv7ToDv81PreserveMappingEnabled,
            dv7HandlingMode = dv7HandlingMode.cap(80),
            dv7LibdoviModeOverride = dv7LibdoviModeOverride.coerceIn(-1, 4),
            stripHdr10PlusSei = stripHdr10PlusSei,
            mpvHardwareDecodeMode = mpvHardwareDecodeMode.cap(80),
            frameRateMatchingMode = frameRateMatchingMode.cap(80),
            resolutionMatchingEnabled = resolutionMatchingEnabled,
            resizeMode = resizeMode.coerceIn(0, 4),
            aspectMode = aspectMode.cap(80),
            bufferEngineEnabled = bufferEngineEnabled,
            minBufferMs = minBufferMs.coerceAtLeast(0),
            maxBufferMs = maxBufferMs.coerceAtLeast(0),
            bufferForPlaybackMs = bufferForPlaybackMs.coerceAtLeast(0),
            bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs.coerceAtLeast(0),
            targetBufferSizeMb = targetBufferSizeMb.coerceAtLeast(0),
            backBufferDurationMs = backBufferDurationMs.coerceAtLeast(0),
            effectiveBackBufferDurationMs = effectiveBackBufferDurationMs.coerceAtLeast(0),
            retainBackBufferFromKeyframe = retainBackBufferFromKeyframe,
            parallelNetworkEnabled = parallelNetworkEnabled,
            bufferBudgetManaged = bufferBudgetManaged,
            allowLargeTargetBuffer = allowLargeTargetBuffer,
            vodCacheEnabled = vodCacheEnabled,
            vodCacheSizeMode = vodCacheSizeMode.cap(60),
            vodCacheSizeMb = vodCacheSizeMb.coerceAtLeast(0),
            useParallelConnections = useParallelConnections,
            parallelConnectionCount = parallelConnectionCount.coerceAtLeast(0),
            parallelChunkSizeKb = parallelChunkSizeKb.coerceAtLeast(0),
            enableHttp2 = enableHttp2,
            exoPerformanceModeEnabled = exoPerformanceModeEnabled,
            streamAutoPlayMode = streamAutoPlayMode.cap(80),
            streamAutoPlaySource = streamAutoPlaySource.cap(80),
            streamAutoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayPreferBingeGroupForNextEpisode = streamAutoPlayPreferBingeGroupForNextEpisode,
            streamAutoPlayReuseBingeGroup = streamAutoPlayReuseBingeGroup,
            streamAutoPlayTimeoutSeconds = streamAutoPlayTimeoutSeconds.coerceAtLeast(0),
            stillWatchingEnabled = stillWatchingEnabled,
            stillWatchingEpisodeThreshold = stillWatchingEpisodeThreshold.coerceAtLeast(0),
            nextEpisodeThresholdMode = nextEpisodeThresholdMode.cap(80),
            nextEpisodeThresholdPercent = nextEpisodeThresholdPercent.coerceIn(0f, 100f),
            nextEpisodeThresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEnd.coerceAtLeast(0f),
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours.coerceAtLeast(0)
        )

    private fun PlaybackIssuePlaybackAnalyticsInput.toDto(): PlaybackIssuePlaybackAnalyticsDto =
        PlaybackIssuePlaybackAnalyticsDto(
            schemaVersion = schemaVersion,
            sessionStartedAtMs = sessionStartedAtMs,
            capturedAtMs = capturedAtMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            clickToFirstFrameMs = clickToFirstFrameMs?.coerceAtLeast(0L),
            initToFirstFrameMs = initToFirstFrameMs?.coerceAtLeast(0L),
            startPositionMs = startPositionMs?.coerceAtLeast(0L),
            eventCount = eventCount.coerceAtLeast(0),
            lastEventElapsedMs = lastEventElapsedMs?.coerceAtLeast(0L),
            playbackState = playbackState,
            playbackStateName = playbackStateName.scrub(80),
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isLoading = isLoading,
            playbackSpeed = playbackSpeed?.takeIf { it.isFinite() && it > 0f },
            playbackPitch = playbackPitch?.takeIf { it.isFinite() && it > 0f },
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bufferedPercentage = bufferedPercentage?.coerceIn(0, 100),
            firstFrameElapsedMs = firstFrameElapsedMs?.coerceAtLeast(0L),
            renderedFirstFrameCount = renderedFirstFrameCount.coerceAtLeast(0),
            rebufferCount = rebufferCount.coerceAtLeast(0),
            rebufferTotalMs = rebufferTotalMs.coerceAtLeast(0L),
            currentRebufferMs = currentRebufferMs.coerceAtLeast(0L),
            positionStallCount = positionStallCount.coerceAtLeast(0),
            longestPositionStallMs = longestPositionStallMs.coerceAtLeast(0L),
            droppedFrames = droppedFrames.coerceAtLeast(0),
            maxDroppedFramesInEvent = maxDroppedFramesInEvent.coerceAtLeast(0),
            videoDecoderName = videoDecoderName.scrub(160),
            videoDecoderInitMs = videoDecoderInitMs?.coerceAtLeast(0L),
            videoDecoderReleaseCount = videoDecoderReleaseCount.coerceAtLeast(0),
            videoRenderedOutputBuffers = videoRenderedOutputBuffers?.coerceAtLeast(0),
            videoDroppedBuffers = videoDroppedBuffers?.coerceAtLeast(0),
            videoMaxConsecutiveDroppedBuffers = videoMaxConsecutiveDroppedBuffers?.coerceAtLeast(0),
            videoFrameProcessingOffsetAverageUs = videoFrameProcessingOffsetAverageUs,
            videoFormat = videoFormat?.toDto(),
            audioDecoderName = audioDecoderName.scrub(160),
            audioDecoderInitMs = audioDecoderInitMs?.coerceAtLeast(0L),
            audioDecoderReleaseCount = audioDecoderReleaseCount.coerceAtLeast(0),
            audioUnderrunCount = audioUnderrunCount.coerceAtLeast(0),
            audioUnderrunBufferSize = audioUnderrunBufferSize?.coerceAtLeast(0),
            audioUnderrunBufferSizeMs = audioUnderrunBufferSizeMs?.coerceAtLeast(0L),
            audioUnderrunElapsedSinceLastFeedMs = audioUnderrunElapsedSinceLastFeedMs?.coerceAtLeast(0L),
            audioFormat = audioFormat?.toDto(),
            bandwidthEstimateBps = bandwidthEstimateBps?.coerceAtLeast(0L),
            bandwidthTransferDurationMs = bandwidthTransferDurationMs?.coerceAtLeast(0),
            bandwidthBytesTransferred = bandwidthBytesTransferred?.coerceAtLeast(0L),
            loadStartedCount = loadStartedCount.coerceAtLeast(0),
            loadCompletedCount = loadCompletedCount.coerceAtLeast(0),
            loadCanceledCount = loadCanceledCount.coerceAtLeast(0),
            loadErrorCount = loadErrorCount.coerceAtLeast(0),
            totalBytesLoaded = totalBytesLoaded.coerceAtLeast(0L),
            lastLoad = lastLoad?.toDto(),
            lastLoadError = lastLoadError?.toDto(),
            rawEventLines = rawEventLines.takeLast(220).mapNotNull { it.scrubLogLine(2000) },
            events = events.takeLast(140).map { it.toDto() },
            rawEvents = rawEvents.takeLast(220).mapNotNull { it.scrubLogLine(2000) },
            deepExoEvents = deepExoEvents.takeLast(220).map { it.toDto() },
            exoEvents = deepExoEvents.takeLast(220).map { it.toDto() },
            stutterSignals = stutterSignals.takeLast(120).map { it.toDto() },
            healthSnapshots = healthSnapshots.takeLast(80).map { it.toDto() },
            startupStages = startupStages.takeLast(80).map {
                PlaybackIssueLoadingEventDto(
                    timeMs = it.timeMs,
                    elapsedMs = it.elapsedMs.coerceAtLeast(0L),
                    phase = it.phase.cap(80),
                    message = it.message.scrub(240),
                    progress = it.progress?.coerceIn(0f, 1f),
                    detail = it.detail.scrub(240)
                )
            }
        )

    private fun PlaybackIssuePlaybackHealthSnapshotInput.toDto(): PlaybackIssuePlaybackHealthSnapshotDto =
        PlaybackIssuePlaybackHealthSnapshotDto(
            timeMs = timeMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            playbackState = playbackState.scrub(80),
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isLoading = isLoading,
            playbackSpeed = playbackSpeed?.takeIf { it.isFinite() && it > 0f },
            playbackPitch = playbackPitch?.takeIf { it.isFinite() && it > 0f },
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bufferedPercentage = bufferedPercentage?.coerceIn(0, 100),
            droppedFrames = droppedFrames.coerceAtLeast(0),
            audioUnderrunCount = audioUnderrunCount.coerceAtLeast(0),
            rebufferCount = rebufferCount.coerceAtLeast(0),
            rebufferTotalMs = rebufferTotalMs.coerceAtLeast(0L),
            bandwidthEstimateBps = bandwidthEstimateBps?.coerceAtLeast(0L),
            totalBytesLoaded = totalBytesLoaded.coerceAtLeast(0L),
            loadStartedCount = loadStartedCount.coerceAtLeast(0),
            loadCompletedCount = loadCompletedCount.coerceAtLeast(0),
            loadCanceledCount = loadCanceledCount.coerceAtLeast(0),
            loadErrorCount = loadErrorCount.coerceAtLeast(0)
        )

    private fun PlaybackIssuePlaybackFormatInput.toDto(): PlaybackIssuePlaybackFormatDto =
        PlaybackIssuePlaybackFormatDto(
            trackType = trackType.scrub(40),
            sampleMimeType = sampleMimeType.scrub(120),
            containerMimeType = containerMimeType.scrub(120),
            codecs = codecs.scrub(160),
            id = id.scrub(160),
            label = label.scrub(160),
            language = language.scrub(40),
            width = width?.coerceAtLeast(0),
            height = height?.coerceAtLeast(0),
            frameRate = frameRate?.takeIf { it > 0f },
            bitrate = bitrate?.coerceAtLeast(0),
            channelCount = channelCount?.coerceAtLeast(0),
            sampleRate = sampleRate?.coerceAtLeast(0),
            colorTransfer = colorTransfer,
            selectionFlags = selectionFlags,
            roleFlags = roleFlags,
            support = support.scrub(80),
            decoderReuseResult = decoderReuseResult.scrub(80),
            decoderDiscardReasons = decoderDiscardReasons
        )

    private fun PlaybackIssuePlaybackLoadInput.toDto(): PlaybackIssuePlaybackLoadDto =
        PlaybackIssuePlaybackLoadDto(
            host = host.scrub(160),
            scheme = scheme.scrub(24),
            dataType = dataType.scrub(80),
            trackType = trackType.scrub(40),
            httpMethod = httpMethod.scrub(12),
            position = position?.coerceAtLeast(0L),
            length = length?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bytesLoaded = bytesLoaded?.coerceAtLeast(0L),
            responseHeaderNames = responseHeaderNames.mapNotNull { it.scrub(80)?.lowercase() }
                .distinct()
                .sorted()
                .take(40)
        )

    private fun PlaybackIssuePlaybackLoadErrorInput.toDto(): PlaybackIssuePlaybackLoadErrorDto =
        PlaybackIssuePlaybackLoadErrorDto(
            host = host.scrub(160),
            dataType = dataType.scrub(80),
            trackType = trackType.scrub(40),
            exceptionClass = exceptionClass.scrub(160),
            message = message.scrub(500),
            httpStatus = httpStatus,
            wasCanceled = wasCanceled,
            bytesLoaded = bytesLoaded?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L)
        )

    private fun PlaybackIssuePlaybackEventInput.toDto(): PlaybackIssuePlaybackEventDto =
        PlaybackIssuePlaybackEventDto(
            timeMs = timeMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            name = name.cap(80),
            playbackState = playbackState.scrub(80),
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            details = details.entries
                .mapNotNull { (key, value) ->
                    key.scrub(50)?.let { safeKey ->
                        value.scrub(240)?.let { safeValue -> safeKey to safeValue }
                    }
                }
                .take(16)
                .toMap()
        )

    private fun Uri.stripQueryAndFragment(): String? {
        val safeScheme = scheme?.takeIf { it.isNotBlank() } ?: return null
        val safeHost = host?.takeIf { it.isNotBlank() } ?: return null
        val authority = if (port >= 0) "$safeHost:$port" else safeHost
        return buildUpon()
            .scheme(safeScheme)
            .encodedAuthority(authority)
            .encodedQuery(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun Uri?.fileExtension(): String? {
        val segment = this?.lastPathSegment?.substringBefore('?')?.substringBefore('#') ?: return null
        return segment.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.length in 1..10 }
            ?.takeIf { it.all { c -> c.isLetterOrDigit() } }
    }

    private fun Map<String, String>.safeHeaderNames(): List<String> =
        keys.mapNotNull { it.scrub(80)?.lowercase() }
            .distinct()
            .sorted()
            .take(40)

    private fun String?.sha256(): String? {
        val value = this?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String?.scrub(maxLength: Int): String? =
        this?.trim()
            ?.redactSensitive()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
            ?.cap(maxLength)

    private fun String.scrubLogLine(maxLength: Int): String? =
        replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .takeIf { it.isNotBlank() }
            ?.cap(maxLength)

    private fun String.redactSensitive(): String =
        replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "[redacted-url]")
            .replace(Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\b\s*:\s*[^\r\n]+"""), "$1: [redacted]")
            .replace(Regex("""(?i)\b(bearer|token|apikey|api_key)\b\s*[:=]\s*\S+"""), "$1=[redacted]")
            .replace(Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\b\s*=\s*\S+"""), "$1=[redacted]")

    private fun String?.cap(maxLength: Int): String =
        if (this == null) "" else if (length <= maxLength) this else take(maxLength)
}