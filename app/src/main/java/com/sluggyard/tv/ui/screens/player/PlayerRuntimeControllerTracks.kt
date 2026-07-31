@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import com.sluggyard.tv.core.player.FrameRateUtils
import com.sluggyard.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.sluggyard.tv.data.local.InternalPlayerEngine
import com.sluggyard.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.sluggyard.tv.ui.util.languageCodeToName

/**
 * ExoPlayer track-list -> UI TrackInfo mapping, engine-switch track matching, and persisted
 * track-preference restore.
 *
 * Re-authored clean-room implementation. Public extension API is preserved verbatim; only
 * the internal expression has been rewritten.
 */

@UnstableApi
internal fun PlayerRuntimeController.updateAvailableTracks(tracks: Tracks) {
    logSwitchTrace(
        stage = "exo-tracks-update-start",
        message = "groupCount=${tracks.groups.size} uiAudioIndex=${_uiState.value.selectedAudioTrackIndex} " +
            "uiSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
    )

    val audioAccumulator = mutableListOf<TrackInfo>()
    val subtitleAccumulator = mutableListOf<TrackInfo>()
    var selectedAudioIndex = -1
    var selectedSubtitleIndex = -1

    // Video state collected in a single pass so we can publish one coherent snapshot.
    var streamHasVideo = false
    var firstVideoFormat: Format? = null
    var selectedVideoFormat: Format? = null
    var bestVideoSupport = C.FORMAT_UNSUPPORTED_TYPE
    var selectedVideoSupport = C.FORMAT_UNSUPPORTED_TYPE

    tracks.groups.forEachIndexed { groupIndex, group ->
        when (group.type) {
            C.TRACK_TYPE_VIDEO -> {
                var runningBestSupport = bestVideoSupport
                scanVideoGroup(
                    group = group,
                    initialBestSupport = runningBestSupport,
                    onHasVideo = { streamHasVideo = true },
                    onFirstFormat = { if (firstVideoFormat == null) firstVideoFormat = it },
                    onBetterSupport = { improved -> runningBestSupport = improved },
                    onSelectedFormat = { format, support ->
                        val prior = selectedVideoFormat
                        if (prior == null || format.width > prior.width ||
                            (format.width == prior.width && format.bitrate > prior.bitrate)
                        ) {
                            selectedVideoFormat = format
                            selectedVideoSupport = support
                        }
                    }
                )
                bestVideoSupport = runningBestSupport
            }
            C.TRACK_TYPE_AUDIO -> scanAudioGroup(
                group = group,
                audioTracks = audioAccumulator,
                onSelected = { selectedAudioIndex = it }
            )
            C.TRACK_TYPE_TEXT -> scanTextGroup(
                group = group,
                subtitleTracks = subtitleAccumulator,
                onSelected = { selectedSubtitleIndex = it }
            )
        }
    }

    currentStreamHasVideoTrack = streamHasVideo
    publishVideoTrackSnapshot(
        selectedVideoFormat = selectedVideoFormat,
        firstVideoFormat = firstVideoFormat,
        selectedVideoSupport = selectedVideoSupport,
        bestVideoSupport = bestVideoSupport
    )?.let { return it } // VC-1 bypass retry triggered; abort this track update.

    hasScannedTextTracksOnce = true
    Log.d(
        PlayerRuntimeController.TAG,
        "TRACKS updated: internalSubs=${subtitleAccumulator.size}, selectedInternalIndex=$selectedSubtitleIndex, " +
            "selectedAddon=${_uiState.value.selectedAddonSubtitle?.lang}, " +
            "pendingAddonLang=$pendingAddonSubtitleLanguage, pendingAddonTrackId=$pendingAddonSubtitleTrackId, " +
            "internalDetails=[${subtitleAccumulator.describeSubtitleTracks()}]"
    )

    // Resolve any pending addon subtitle override now that text tracks are fresh.
    pendingAddonSubtitleTrackId?.takeIf { it.isNotBlank() }?.let { pendingId ->
        if (applyAddonSubtitleOverride(pendingId)) {
            Log.d(PlayerRuntimeController.TAG, "Selecting pending addon subtitle track id=$pendingId")
            pendingAddonSubtitleTrackId = null
            pendingAddonSubtitleLanguage = null
        }
    }

    // Pending addon language with no explicit track id: try to land on an internal track
    // matching that language so the user sees something immediately.
    val pendingAddonLang = pendingAddonSubtitleLanguage
    if (pendingAddonSubtitleTrackId.isNullOrBlank() &&
        pendingAddonLang != null &&
        subtitleAccumulator.isNotEmpty() &&
        _uiState.value.selectedAddonSubtitle == null
    ) {
        val preferredIndex = findBestInternalSubtitleTrackIndex(
            subtitleTracks = subtitleAccumulator,
            targets = listOf(pendingAddonLang)
        )
        if (preferredIndex >= 0) {
            selectSubtitleTrack(preferredIndex)
            selectedSubtitleIndex = preferredIndex
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "Skipping pending subtitle track switch: no text track matches language=$pendingAddonLang"
            )
        }
        pendingAddonSubtitleLanguage = null
    }

    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioAccumulator)?.let { restored ->
        selectedAudioIndex = restored
    }

    _uiState.update { state ->
        state.copy(
            audioTracks = audioAccumulator,
            subtitleTracks = subtitleAccumulator,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = selectedSubtitleIndex
        )
    }
    updateAudioControlAvailability(audioAccumulator, selectedAudioIndex)
    logSwitchTrace(
        stage = "exo-tracks-update-end",
        message = "audioCount=${audioAccumulator.size} subtitleCount=${subtitleAccumulator.size} " +
            "selectedAudioIndex=$selectedAudioIndex selectedSubtitleIndex=$selectedSubtitleIndex"
    )
    rememberEffectiveExoSubtitleSelectionForEngineSwitch(
        subtitleTracks = subtitleAccumulator,
        selectedSubtitleIndex = selectedSubtitleIndex
    )
    applyPersistedTrackPreference(
        audioTracks = audioAccumulator,
        subtitleTracks = subtitleAccumulator
    )
    if (currentStreamHasVideoTrack) maybeScheduleFirstFrameWatchdog() else cancelFirstFrameWatchdog()
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    maybeAdjustLibassPipelineForTracks(tracks)
}

private fun scanVideoGroup(
    group: Tracks.Group,
    initialBestSupport: Int,
    onHasVideo: () -> Unit,
    onFirstFormat: (Format) -> Unit,
    onBetterSupport: (Int) -> Unit,
    onSelectedFormat: (Format, Int) -> Unit
) {
    if (group.length == 0) return
    onHasVideo()
    var bestRank = formatSupportRank(initialBestSupport)
    for (i in 0 until group.length) {
        val format = group.getTrackFormat(i)
        if (i == 0) onFirstFormat(format)
        val support = group.getTrackSupport(i)
        val rank = formatSupportRank(support)
        if (rank > bestRank) {
            bestRank = rank
            onBetterSupport(support)
        }
        if (group.isTrackSelected(i)) {
            onSelectedFormat(format, support)
        }
    }
}

private fun PlayerRuntimeController.publishVideoTrackSnapshot(
    selectedVideoFormat: Format?,
    firstVideoFormat: Format?,
    selectedVideoSupport: Int,
    bestVideoSupport: Int
): Unit? {
    val effective = selectedVideoFormat ?: firstVideoFormat ?: run {
        // No video at all — clear the snapshot and return.
        currentVideoTrackMimeType = null
        currentVideoTrackCodecs = null
        currentVideoTrackWidth = 0
        currentVideoTrackHeight = 0
        currentVideoTrackColorTransfer = null
        currentVideoTrackSelected = false
        currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
        currentVideoTrackIsLikelyVc1 = false
        lastLoggedVideoTrackSignature = null
        return null
    }

    currentVideoTrackMimeType = effective.sampleMimeType
    currentVideoTrackCodecs = effective.codecs
    currentVideoTrackWidth = effective.width.coerceAtLeast(0)
    currentVideoTrackHeight = effective.height.coerceAtLeast(0)
    currentVideoTrackBitrate = effective.bitrate
    currentVideoTrackColorTransfer = effective.colorInfo?.colorTransfer
    currentVideoTrackSelected = selectedVideoFormat != null
    currentVideoTrackBestSupport = if (selectedVideoFormat != null) selectedVideoSupport else bestVideoSupport
    currentVideoTrackIsLikelyVc1 = isLikelyVc1VideoFormat(
        sampleMimeType = effective.sampleMimeType,
        codecs = effective.codecs,
        label = effective.label
    )
    playbackAnalyticsDiagnostics.onVideoTrackSnapshot(
        format = effective,
        support = Util.getFormatSupportString(currentVideoTrackBestSupport),
        selected = currentVideoTrackSelected
    )

    // Emit the selected-video frame-rate + codec/size telemetry, then log a deduplicated
    // signature line so we can spot silent track re-selections.
    selectedVideoFormat?.let { format ->
        if (format.frameRate > 0f) {
            val raw = format.frameRate
            val snapped = FrameRateUtils.snapToStandardRate(raw)
            val ambiguousCinema = PlayerFrameRateHeuristics.isAmbiguousCinema24(raw)
            if (!ambiguousCinema) frameRateProbeJob?.cancel()
            _uiState.update {
                it.copy(
                    detectedFrameRateRaw = raw,
                    detectedFrameRate = snapped,
                    detectedFrameRateSource = FrameRateSource.TRACK
                )
            }
        }
        currentVideoCodec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
        currentVideoWidth = format.width.takeIf { it > 0 }
        currentVideoHeight = format.height.takeIf { it > 0 }
        currentVideoBitrate = format.bitrate.takeIf { it > 0 }
    }

    val signature = buildString {
        append(currentVideoTrackMimeType ?: "unknown"); append('|')
        append(currentVideoTrackCodecs ?: "unknown"); append('|')
        append(currentVideoTrackWidth); append('x'); append(currentVideoTrackHeight)
        append("|vc1="); append(currentVideoTrackIsLikelyVc1)
        append("|selected="); append(currentVideoTrackSelected)
        append("|support="); append(Util.getFormatSupportString(currentVideoTrackBestSupport))
        append("|vc1Fallback="); append(isVc1SoftwareFallbackActiveForCurrentPlayback)
        append("|vc1TrackBypass="); append(isVc1TrackSelectionBypassActiveForCurrentPlayback)
    }
    if (signature != lastLoggedVideoTrackSignature) {
        lastLoggedVideoTrackSignature = signature
        Log.i(
            PlayerRuntimeController.TAG,
            "VIDEO_TRACK: mime=${currentVideoTrackMimeType ?: "unknown"} " +
                "codecs=${currentVideoTrackCodecs ?: "unknown"} " +
                "size=${currentVideoTrackWidth}x${currentVideoTrackHeight} " +
                "vc1=$currentVideoTrackIsLikelyVc1 " +
                "selected=$currentVideoTrackSelected " +
                "support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                "vc1FallbackActive=$isVc1SoftwareFallbackActiveForCurrentPlayback " +
                "vc1TrackBypassActive=$isVc1TrackSelectionBypassActiveForCurrentPlayback"
        )
    }

    // VC-1 software-preferred retry left the track unselected: force a track-selection
    // bypass retry and signal the caller to abort this track update.
    if (currentVideoTrackIsLikelyVc1 &&
        !currentVideoTrackSelected &&
        isVc1SoftwareFallbackActiveForCurrentPlayback &&
        !isVc1TrackSelectionBypassActiveForCurrentPlayback
    ) {
        val position = _exoPlayer?.currentPosition ?: 0L
        vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
        Log.w(
            PlayerRuntimeController.TAG,
            "VIDEO_TRACK: VC-1 track present but unselected after software-preferred retry, " +
                "forcing track-selection bypass support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$position"
        )
        retryCurrentStreamWithVc1TrackSelectionBypass(position)
        return Unit
    }
    return null
}

private fun PlayerRuntimeController.scanAudioGroup(
    group: Tracks.Group,
    audioTracks: MutableList<TrackInfo>,
    onSelected: (Int) -> Unit
) {
    for (i in 0 until group.length) {
        val format = group.getTrackFormat(i)
        val isSelected = group.isTrackSelected(i)
        if (isSelected) onSelected(audioTracks.size)

        val codec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
        val channels = CustomDefaultTrackNameProvider.getChannelLayoutName(format.channelCount)
        val langDisplay = format.language?.takeIf { it != "und" }?.let { languageCodeToName(it) }
        val baseName = format.label ?: langDisplay
            ?: context.getString(com.sluggyard.tv.R.string.player_track_audio_fallback, audioTracks.size + 1)
        val suffix = listOfNotNull(codec, channels).joinToString(" ")
        val displayName = if (suffix.isNotEmpty()) "$baseName ($suffix)" else baseName

        audioTracks += TrackInfo(
            index = audioTracks.size,
            name = displayName,
            language = format.language,
            trackId = format.id,
            codec = codec,
            channelCount = format.channelCount.takeIf { it > 0 },
            isSelected = isSelected,
            sampleRate = format.sampleRate.takeIf { it > 0 }
        )
    }
}

private fun PlayerRuntimeController.scanTextGroup(
    group: Tracks.Group,
    subtitleTracks: MutableList<TrackInfo>,
    onSelected: (Int) -> Unit
) {
    for (i in 0 until group.length) {
        val format = group.getTrackFormat(i)
        // Addon subtitle tracks are managed separately — skip them here.
        if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) continue
        val isSelected = group.isTrackSelected(i)
        if (isSelected) onSelected(subtitleTracks.size)

        val forcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        val texts = listOfNotNull(format.label, format.language, format.id)
        val nameHintsForced = texts.any { it.contains("forced", ignoreCase = true) }
        val songsAndSigns = PlayerSubtitleUtils.isSignsAndSongsTrack(texts)

        subtitleTracks += TrackInfo(
            index = subtitleTracks.size,
            name = format.label ?: format.language
                ?: context.getString(com.sluggyard.tv.R.string.player_track_subtitle_fallback, subtitleTracks.size + 1),
            language = format.language,
            trackId = format.id,
            codec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
                ?: format.codecs?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() },
            isForced = forcedFlag || nameHintsForced || songsAndSigns,
            isSelected = isSelected,
            isSignsAndSongs = songsAndSigns,
            sampleMimeType = format.sampleMimeType
        )
    }
}

private fun List<TrackInfo>.describeSubtitleTracks(): String =
    joinToString(" | ") { it.describeSubtitleTrack() }

private fun TrackInfo.describeSubtitleTrack(selectedOverride: Boolean? = null): String =
    "index=$index lang=${language ?: "und"} codec=${codec ?: "unknown"} " +
        "mime=${sampleMimeType ?: "unknown"} selected=${selectedOverride ?: isSelected}"

/**
 * Heuristic VC-1 detector. MIME type is authoritative; the codec/label haystack is checked
 * for explicit VC-1 identifiers only — a bare "vc1" substring would also match "avc1"/"hvc1".
 */
private fun isLikelyVc1VideoFormat(
    sampleMimeType: String?,
    codecs: String?,
    label: String?
): Boolean {
    if (sampleMimeType?.equals(MimeTypes.VIDEO_VC1, ignoreCase = true) == true) return true
    val haystack = listOfNotNull(codecs, label).joinToString(" ").lowercase(Locale.ROOT)
    return haystack.contains("wvc1") ||
        haystack.contains("vc-1") ||
        haystack.contains("wmv3") ||
        Regex("(?<![a-z0-9])vc1(?![a-z0-9])").containsMatchIn(haystack)
}

private fun formatSupportRank(@C.FormatSupport formatSupport: Int): Int = when (formatSupport) {
    C.FORMAT_HANDLED -> 4
    C.FORMAT_EXCEEDS_CAPABILITIES -> 3
    C.FORMAT_UNSUPPORTED_DRM -> 2
    C.FORMAT_UNSUPPORTED_SUBTYPE -> 1
    else -> 0
}

/**
 * Remember the effective Exo subtitle selection so a subsequent engine switch can restore it
 * even if the capture at switch time misses it (e.g. because the user disabled text tracks
 * via ExoPlayer parameters rather than the UI).
 */
private fun PlayerRuntimeController.rememberEffectiveExoSubtitleSelectionForEngineSwitch(
    subtitleTracks: List<TrackInfo>,
    selectedSubtitleIndex: Int
) {
    if (isUsingMpvEngine()) return

    val selection: PlayerRuntimeController.RememberedSubtitleSelection? = when {
        selectedSubtitleIndex >= 0 -> {
            val track = subtitleTracks.getOrNull(selectedSubtitleIndex) ?: return
            PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = _uiState.value,
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId,
                    isForced = track.isForced,
                    selectedUiTrackOverride = track
                )
            )
        }
        _uiState.value.selectedAddonSubtitle != null -> {
            val addon = _uiState.value.selectedAddonSubtitle ?: return
            PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                id = addon.id,
                url = addon.url,
                language = addon.lang,
                addonName = addon.addonName
            )
        }
        else -> null
    }

    if (selection != null) {
        logSwitchTrace(
            stage = "remember-effective-exo-subtitle",
            message = "selection=${describeRememberedSubtitleForSwitchTrace(selection)} selectedSubtitleIndex=$selectedSubtitleIndex"
        )
        effectiveSubtitleSelectionForEngineSwitch =
            PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
                streamUrl = currentStreamUrl,
                selection = selection
            )
    } else {
        logSwitchTrace(
            stage = "remember-effective-exo-subtitle",
            message = "selection=none selectedSubtitleIndex=$selectedSubtitleIndex addonSelected=${_uiState.value.selectedAddonSubtitle != null}"
        )
    }
}

internal fun PlayerRuntimeController.maybeAdjustLibassPipelineForTracks(tracks: Tracks) {
    if (libassPipelineSwitchInFlight) return

    val hasAssSsa = tracks.hasAssSsaTextTrack()
    if (hasAssSsa) hasDetectedAssSsaTrackForCurrentStream = true

    // Only rebuild for libass when the user asked for it AND we detected ASS/SSA. Dropping
    // libass is only worth a rebuild when it's blocking DV conversion.
    val desiredUseLibass = requestedUseLibassByUser && hasDetectedAssSsaTrackForCurrentStream
    if (desiredUseLibass == activePlayerUsesLibass) return
    if (!desiredUseLibass) {
        val libassBlockingDvConvert = activePlayerUsesLibass &&
            isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
            tracks.hasDolbyVisionConvertibleVideoTrack()
        if (!libassBlockingDvConvert) return
    }

    val player = _exoPlayer ?: return
    val resumePosition = player.currentPosition.takeIf { it > 0L }
    libassPipelineOverrideForCurrentStream = desiredUseLibass
    libassPipelineSwitchInFlight = true

    _uiState.update { state ->
        state.copy(
            pendingSeekPosition = resumePosition ?: state.pendingSeekPosition,
            showLoadingOverlay = state.loadingOverlayEnabled
        )
    }

    scope.launch {
        releasePlayer()
        initializePlayer(currentStreamUrl, currentHeaders)
    }
}

private fun Tracks.hasAssSsaTextTrack(): Boolean {
    groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEach
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            if (format.sampleMimeType == MimeTypes.TEXT_SSA) return true
            val codecHit = format.codecs
                ?.split(',')
                ?.asSequence()
                ?.map { it.trim().lowercase(Locale.US) }
                ?.any { codec ->
                    codec == MimeTypes.TEXT_SSA ||
                        codec == "s_text/ass" ||
                        codec == "s_text/ssa" ||
                        codec.endsWith("/x-ssa")
                } == true
            if (codecHit) return true
        }
    }
    return false
}

// Profile 7 only: it's the profile AUTO conversion rewrites. DV5/DV8 don't convert, so they
// shouldn't trigger a libass-drop reload.
private val DV_PROFILE_7_CODEC = Regex("^(dvhe|dvh1|dvav|dva1)\\.0?7\\.")

private fun Tracks.hasDolbyVisionProfile7VideoTrack(): Boolean {
    groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (i in 0 until group.length) {
            val codec = group.getTrackFormat(i).codecs?.lowercase(Locale.US).orEmpty()
            if (DV_PROFILE_7_CODEC.containsMatchIn(codec)) return true
        }
    }
    return false
}

// Profiles that the DolbyVisionExtractorsFactory actually converts (7 always, 5 when enabled).
private val DV_CONVERTIBLE_CODEC = Regex("^(dvhe|dvh1|dvav|dva1)\\.0?[57]\\.")

private fun Tracks.hasDolbyVisionConvertibleVideoTrack(): Boolean {
    groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (i in 0 until group.length) {
            val codec = group.getTrackFormat(i).codecs?.lowercase(Locale.US).orEmpty()
            if (DV_CONVERTIBLE_CODEC.containsMatchIn(codec)) return true
        }
    }
    return false
}

/** Normalize a free-form track match value: lowercase, collapse whitespace, drop blanks. */
internal fun PlayerRuntimeController.normalizeTrackMatchValue(value: String?): String? = value
    ?.lowercase()
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf { it.isNotBlank() }

/**
 * After a subtitle refresh ExoPlayer sometimes reorders audio groups; restore the audio
 * selection the user had before the refresh. Returns the restored index, or null if no
 * pending restore exists / no match was found.
 */
internal fun PlayerRuntimeController.maybeRestorePendingAudioSelectionAfterSubtitleRefresh(
    audioTracks: List<TrackInfo>
): Int? {
    val pending = pendingAudioSelectionAfterSubtitleRefresh ?: return null
    if (pending.streamUrl != currentStreamUrl) {
        logSwitchTrace(
            stage = "restore-audio-after-subtitle-refresh",
            message = "action=clear reason=stream-mismatch pendingStream=${pending.streamUrl} currentStream=$currentStreamUrl"
        )
        pendingAudioSelectionAfterSubtitleRefresh = null
        return null
    }
    if (audioTracks.isEmpty()) return null

    val targetLang = normalizeTrackMatchValue(pending.language)
    val targetName = normalizeTrackMatchValue(pending.name)

    fun languageMatches(trackLanguage: String?): Boolean {
        val trackLang = normalizeTrackMatchValue(trackLanguage)
        return !targetLang.isNullOrBlank() && !trackLang.isNullOrBlank() &&
            (trackLang == targetLang ||
                trackLang.startsWith("$targetLang-") ||
                trackLang.startsWith("${targetLang}_"))
    }

    val exactNameIdx = if (!targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { normalizeTrackMatchValue(it.name) == targetName }
    } else -1

    val nameContainsIdx = if (exactNameIdx < 0 && !targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { normalizeTrackMatchValue(it.name)?.contains(targetName) == true }
    } else -1

    val languageIdx = if (exactNameIdx < 0 && nameContainsIdx < 0) {
        audioTracks.indexOfFirst { languageMatches(it.language) }
    } else -1

    val resolvedIdx = when {
        exactNameIdx >= 0 -> exactNameIdx
        nameContainsIdx >= 0 -> nameContainsIdx
        else -> languageIdx
    }

    pendingAudioSelectionAfterSubtitleRefresh = null
    if (resolvedIdx < 0) {
        logSwitchTrace(
            stage = "restore-audio-after-subtitle-refresh",
            message = "result=no-match lang=$targetLang name=$targetName candidates=${describeTrackCandidatesForRestoreLog(audioTracks)}"
        )
        Log.d(
            PlayerRuntimeController.TAG,
            "Audio restore skipped after subtitle refresh: no match for lang=$targetLang name=$targetName"
        )
        return null
    }

    val restored = audioTracks[resolvedIdx]
    logSwitchTrace(
        stage = "restore-audio-after-subtitle-refresh",
        message = "result=match index=$resolvedIdx lang=${restored.language} name=${restored.name}"
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "Restoring audio after subtitle refresh index=$resolvedIdx lang=${restored.language} name=${restored.name}"
    )
    selectAudioTrack(resolvedIdx)
    return resolvedIdx
}

/**
 * Regular track matching: strict (trackId/name) first, then language fallback. Used by
 * persisted-preference restore on the same engine.
 */
internal fun PlayerRuntimeController.findMatchingTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val strict = findStrictMatchingTrackIndex(tracks, target)
    if (strict >= 0) {
        logSwitchTrace(
            stage = "track-match-regular",
            message = "result=strict index=$strict target=${describeRememberedTrackForSwitchTrace(target)}"
        )
        return strict
    }
    val fallback = findLanguageFallbackTrackIndex(tracks, target)
    logSwitchTrace(
        stage = "track-match-regular",
        message = "result=language-fallback index=$fallback target=${describeRememberedTrackForSwitchTrace(target)}"
    )
    return fallback
}

/**
 * Engine-switch track matching (Exo -> MPV). Strict first, then sparse-MPV hint matching
 * (only when actually switching to MPV), then language fallback.
 */
internal fun PlayerRuntimeController.findMatchingTrackIndexForEngineSwitchToMpv(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection,
    sourceEngine: InternalPlayerEngine
): Int {
    val strict = findStrictMatchingTrackIndex(tracks, target)
    if (strict >= 0) {
        logSwitchTrace(
            stage = "track-match-switch",
            message = "result=strict index=$strict sourceEngine=$sourceEngine target=${describeRememberedTrackForSwitchTrace(target)}"
        )
        return strict
    }

    if (sourceEngine == InternalPlayerEngine.EXOPLAYER && isUsingMpvEngine()) {
        val hinted = findEngineSwitchHintTrackIndex(tracks, target)
        if (hinted >= 0) {
            logSwitchTrace(
                stage = "track-match-switch",
                message = "result=hint index=$hinted sourceEngine=$sourceEngine target=${describeRememberedTrackForSwitchTrace(target)}"
            )
            return hinted
        }
    }

    val fallback = findLanguageFallbackTrackIndex(tracks, target)
    logSwitchTrace(
        stage = "track-match-switch",
        message = "result=language-fallback index=$fallback sourceEngine=$sourceEngine " +
            "target=${describeRememberedTrackForSwitchTrace(target)}"
    )
    return fallback
}

private fun PlayerRuntimeController.describeTrackInfoForRestoreLog(track: TrackInfo): String =
    "index=${track.index} lang=${track.language} name=${track.name} id=${track.trackId} " +
        "forced=${track.isForced} selected=${track.isSelected}"

private fun PlayerRuntimeController.describeTrackCandidatesForRestoreLog(
    tracks: List<TrackInfo>
): String = tracks.joinToString(prefix = "[", postfix = "]") { "{${describeTrackInfoForRestoreLog(it)}}" }

private fun PlayerRuntimeController.describeRememberedTrackForSwitchTrace(
    selection: PlayerRuntimeController.RememberedTrackSelection?
): String {
    if (selection == null) return "none"
    return "lang=${selection.language} name=${selection.name} trackId=${selection.trackId} " +
        "indexHint=${selection.indexHint} languageIndexHint=${selection.languageIndexHint} " +
        "forcedHint=${selection.isForcedHint}"
}

private fun PlayerRuntimeController.describeRememberedSubtitleForSwitchTrace(
    selection: PlayerRuntimeController.RememberedSubtitleSelection?
): String = when (selection) {
    null -> "none"
    PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> "disabled"
    is PlayerRuntimeController.RememberedSubtitleSelection.Internal ->
        "internal:${describeRememberedTrackForSwitchTrace(selection.track)}"
    is PlayerRuntimeController.RememberedSubtitleSelection.Addon ->
        "addon:${selection.language}/${selection.addonName}/${selection.id}"
}

/**
 * Strict matching: trackId (with lang/name corroboration) -> exact name (with lang) ->
 * name-contains (with lang). Returns -1 when nothing matches strictly.
 */
private fun PlayerRuntimeController.findStrictMatchingTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val targetTrackId = normalizeTrackMatchValue(target.trackId)
    val targetName = normalizeTrackMatchValue(target.name)
    val targetLang = normalizeTrackMatchValue(target.language)

    if (!targetTrackId.isNullOrBlank()) {
        val idx = tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.trackId) == targetTrackId &&
                (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang) &&
                (targetName.isNullOrBlank() ||
                    normalizeTrackMatchValue(track.name) == targetName ||
                    normalizeTrackMatchValue(track.name)?.contains(targetName) == true)
        }
        if (idx >= 0) return idx
    }

    if (!targetName.isNullOrBlank()) {
        val exactName = tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName &&
                (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
        if (exactName >= 0) return exactName

        val nameContains = tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true &&
                (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
        if (nameContains >= 0) return nameContains
    }

    return -1
}

/**
 * Language fallback: collect tracks whose language matches the target's language (including
 * regional variants), narrow by forced-flag if the hint is set, then prefer the track whose
 * detected variant matches the target's variant.
 */
private fun PlayerRuntimeController.findLanguageFallbackTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val targetLang = normalizeTrackMatchValue(target.language)
    val result = if (targetLang.isNullOrBlank()) {
        -1
    } else {
        val targetVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = target.language,
            name = target.name,
            trackId = target.trackId
        )
        val langCandidates = tracks.indices.filter { idx ->
            val trackLang = normalizeTrackMatchValue(tracks[idx].language)
            !trackLang.isNullOrBlank() && (
                trackLang == targetLang ||
                    trackLang.startsWith("$targetLang-") ||
                    trackLang.startsWith("${targetLang}_")
                )
        }
        // Forced hint narrows the candidate set so a non-forced track doesn't get restored
        // when the user had a forced track selected.
        val filtered = target.isForcedHint?.let { forcedHint ->
            langCandidates.filter { idx -> tracks[idx].isForced == forcedHint }
                .ifEmpty { langCandidates }
        } ?: langCandidates
        when {
            filtered.size <= 1 -> filtered.firstOrNull() ?: -1
            else -> {
                // Prefer the track whose detected variant matches the target's variant.
                val variantMatch = filtered.firstOrNull { idx ->
                    val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                        language = tracks[idx].language,
                        name = tracks[idx].name,
                        trackId = tracks[idx].trackId
                    )
                    candidateVariant == targetVariant
                }
                if (variantMatch != null) {
                    // Among same-variant candidates, rank dialogue over signs-and-songs.
                    val sameVariant = filtered.filter { idx ->
                        val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                            language = tracks[idx].language,
                            name = tracks[idx].name,
                            trackId = tracks[idx].trackId
                        )
                        candidateVariant == targetVariant
                    }
                    PlayerSubtitleUtils.rankSameLanguageSubtitleCandidates(
                        tracks = tracks,
                        candidates = sameVariant,
                        preferForced = null
                    ).takeIf { it >= 0 } ?: variantMatch
                } else {
                    // No variant match: rank dialogue over signs-and-songs across all
                    // language candidates so a saved English dialogue track beats an English
                    // signs-and-songs track that shares the same language code.
                    PlayerSubtitleUtils.rankSameLanguageSubtitleCandidates(
                        tracks = tracks,
                        candidates = filtered,
                        preferForced = null
                    )
                }
            }
        }
    }
    logSwitchTrace(
        stage = "track-match-language-fallback",
        message = "targetLang=$targetLang result=$result target=${describeRememberedTrackForSwitchTrace(target)}"
    )
    return result
}

/**
 * Sparse-MPV hint matching. When MPV exposes only bare "Subtitle N" names, direct trackId
 * matching fails; fall back to the captured index/variant-ordinal hints, scoped by the
 * forced flag and language-variant candidate set.
 */
private fun PlayerRuntimeController.findEngineSwitchHintTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val indexHint = target.indexHint?.takeIf { it >= 0 } ?: -1
    val languageIndexHint = target.languageIndexHint?.takeIf { it >= 0 }
    val targetForced = target.isForcedHint
    val sparseMetadata = hasSparseMpvSubtitleMetadataForEngineSwitch(tracks)
    val targetVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = target.language,
        name = target.name,
        trackId = target.trackId
    )

    val baseCandidates = tracks.indices.filter { idx ->
        val track = tracks[idx]
        target.language.isNullOrBlank() ||
            PlayerSubtitleUtils.matchesLanguageCode(track.language, target.language) ||
            PlayerSubtitleUtils.detectTrackLanguageVariant(
                language = track.language,
                name = track.name,
                trackId = track.trackId
            ) == targetVariant
    }
    val sparseCandidates = if (sparseMetadata) {
        if (targetForced == null) {
            tracks.indices.toList()
        } else {
            tracks.indices.filter { idx -> tracks[idx].isForced == targetForced }
                .ifEmpty { tracks.indices.toList() }
        }
    } else {
        emptyList()
    }

    if (baseCandidates.isEmpty()) {
        if (indexHint in sparseCandidates) {
            logSwitchTrace(
                stage = "track-match-hint",
                message = "result=indexHint-from-sparse index=$indexHint " +
                    "indexHint=$indexHint languageIndexHint=$languageIndexHint targetForced=$targetForced"
            )
            return indexHint
        }
        if (languageIndexHint != null && languageIndexHint in sparseCandidates.indices) {
            val resolved = sparseCandidates[languageIndexHint]
            logSwitchTrace(
                stage = "track-match-hint",
                message = "result=languageIndexHint-from-sparse index=$resolved " +
                    "indexHint=$indexHint languageIndexHint=$languageIndexHint targetForced=$targetForced"
            )
            return resolved
        }
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=-1 reason=empty-base-and-no-sparse-match indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                "targetForced=$targetForced sparseMetadata=$sparseMetadata"
        )
        return -1
    }

    val preferred = if (targetForced == null) {
        baseCandidates
    } else {
        baseCandidates.filter { idx -> tracks[idx].isForced == targetForced }.ifEmpty { baseCandidates }
    }

    if (indexHint in preferred) {
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=indexHint-preferred index=$indexHint indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferred sparseMetadata=$sparseMetadata"
        )
        return indexHint
    }
    if (languageIndexHint != null && languageIndexHint in preferred.indices) {
        val resolved = preferred[languageIndexHint]
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=languageIndexHint-preferred index=$resolved indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferred sparseMetadata=$sparseMetadata"
        )
        return resolved
    }
    if (indexHint in baseCandidates) {
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=indexHint-base index=$indexHint indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferred sparseMetadata=$sparseMetadata"
        )
        return indexHint
    }
    if (indexHint in sparseCandidates) {
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=indexHint-sparse index=$indexHint indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferred sparseMetadata=$sparseMetadata"
        )
        return indexHint
    }
    if (languageIndexHint != null && languageIndexHint in sparseCandidates.indices) {
        val resolved = sparseCandidates[languageIndexHint]
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=languageIndexHint-sparse index=$resolved indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferred sparseMetadata=$sparseMetadata"
        )
        return resolved
    }

    logSwitchTrace(
        stage = "track-match-hint",
        message = "result=-1 reason=no-hint-match indexHint=$indexHint languageIndexHint=$languageIndexHint " +
            "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferred " +
            "sparseCandidates=$sparseCandidates sparseMetadata=$sparseMetadata"
    )
    return -1
}

/**
 * Heuristic: MPV is exposing sparse subtitle metadata when most tracks have no language and
 * only a generic "Subtitle" name. Used to decide whether hint-based matching is allowed.
 */
private fun PlayerRuntimeController.hasSparseMpvSubtitleMetadataForEngineSwitch(
    tracks: List<TrackInfo>
): Boolean {
    if (tracks.isEmpty()) return false
    val sparseCount = tracks.count { track ->
        val normalizedName = normalizeTrackMatchValue(track.name)
        track.language.isNullOrBlank() && (
            normalizedName.isNullOrBlank() ||
                normalizedName == "subtitle" ||
                normalizedName.startsWith("subtitle ")
            )
    }
    return sparseCount > 0 && sparseCount * 2 >= tracks.size
}

/**
 * Apply the persisted (or engine-switch-pending) track preference to the freshly scanned
 * track lists. Audio is restored immediately when a match is found; subtitle restore is
 * more nuanced — it can defer when addon subtitles are still loading, fall back to an addon
 * when no internal match exists, or treat the preference as "disabled" and clear it.
 */
internal fun PlayerRuntimeController.applyPersistedTrackPreference(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>
) {
    val switchPending = pendingEngineSwitchTrackPreference?.takeIf { it.streamUrl == currentStreamUrl }
    if (pendingEngineSwitchTrackPreference != null && switchPending == null) {
        logSwitchTrace(
            stage = "restore-switch-pref-clear",
            message = "reason=stream-mismatch pendingStream=${pendingEngineSwitchTrackPreference?.streamUrl} currentStream=$currentStreamUrl"
        )
        pendingEngineSwitchTrackPreference = null
    }
    val usingSwitchPending = switchPending != null
    val pendingCandidate = switchPending?.preference ?: persistedTrackPreference
    logSwitchTrace(
        stage = "restore-enter",
        message = "usingSwitchPending=$usingSwitchPending switchPending=${switchPending != null} persisted=${persistedTrackPreference != null} " +
            "audioTracks=${audioTracks.size} subtitleTracks=${subtitleTracks.size} " +
            "uiAudioIndex=${_uiState.value.selectedAudioTrackIndex} uiSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex} " +
            "pendingAudio=${describeRememberedTrackForSwitchTrace(pendingCandidate?.audio)} " +
            "pendingSubtitle=${describeRememberedSubtitleForSwitchTrace(pendingCandidate?.subtitle)}"
    )

    val pending: PlayerRuntimeController.TrackPreference = pendingCandidate ?: run {
        logSwitchTrace(stage = "restore-skip", message = "reason=no-pending-preference")
        return
    }
    val switchSourceEngine = switchPending?.sourceEngine
    var updatedPending = pending
    var updatedSubtitleIndex: Int? = null
    var updatedAddonSubtitle: Subtitle? = null

    // --- Audio restore ---
    pending.audio?.let { audioSelection ->
        if (audioTracks.isEmpty()) {
            logSwitchTrace(stage = "restore-audio", message = "result=defer reason=no-audio-tracks")
            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio deferred (no tracks yet)")
        } else {
            val index = findMatchingTrackIndex(audioTracks, audioSelection)
            if (index >= 0) {
                val alreadySelected = audioTracks.getOrNull(index)?.isSelected == true
                logSwitchTrace(
                    stage = "restore-audio",
                    message = "result=match index=$index alreadySelected=$alreadySelected " +
                        "target=${describeRememberedTrackForSwitchTrace(audioSelection)} " +
                        "matched=${audioTracks.getOrNull(index)?.let { describeTrackInfoForRestoreLog(it) }}"
                )
                if (!alreadySelected) {
                    Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=$index lang=${audioTracks[index].language} name=${audioTracks[index].name}")
                    selectAudioTrack(index)
                    _uiState.update { it.copy(selectedAudioTrackIndex = index) }
                } else {
                    Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=$index already selected, clearing")
                    updatedPending = updatedPending.copy(audio = null)
                }
            } else {
                logSwitchTrace(
                    stage = "restore-audio",
                    message = "result=no-match target=${describeRememberedTrackForSwitchTrace(audioSelection)} " +
                        "candidates=${describeTrackCandidatesForRestoreLog(audioTracks)}"
                )
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio no match for lang=${audioSelection.language} name=${audioSelection.name}, clearing")
                updatedPending = updatedPending.copy(audio = null)
            }
        }
    }

    // --- Subtitle restore ---
    when (val subtitleSelection = pending.subtitle) {
        null -> Unit
        PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> {
            val alreadyDisabled = subtitleTracks.none { it.isSelected }
            logSwitchTrace(
                stage = "restore-subtitle-disabled",
                message = "alreadyDisabled=$alreadyDisabled subtitleTrackCount=${subtitleTracks.size}"
            )
            autoSubtitleSelected = true
            subtitleDisabledByPersistedPreference = true
            if (!alreadyDisabled) {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle disabled (re-applying)")
                disableSubtitles()
                updatedSubtitleIndex = -1
            } else {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle already disabled, clearing")
                updatedSubtitleIndex = -1
                updatedPending = updatedPending.copy(subtitle = null)
            }
        }
        is PlayerRuntimeController.RememberedSubtitleSelection.Internal -> {
            if (subtitleTracks.isEmpty()) {
                logSwitchTrace(
                    stage = "restore-subtitle-internal",
                    message = "result=defer reason=no-subtitle-tracks target=${describeRememberedTrackForSwitchTrace(subtitleSelection.track)}"
                )
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle deferred (no tracks yet)")
            } else {
                val index = if (usingSwitchPending && switchSourceEngine != null) {
                    findMatchingTrackIndexForEngineSwitchToMpv(
                        tracks = subtitleTracks,
                        target = subtitleSelection.track,
                        sourceEngine = switchSourceEngine
                    )
                } else {
                    findMatchingTrackIndex(subtitleTracks, subtitleSelection.track)
                }
                logSwitchTrace(
                    stage = "restore-subtitle-internal",
                    message = "mode=${if (usingSwitchPending && switchSourceEngine != null) "switch-hint-aware" else "regular"} " +
                        "sourceEngine=$switchSourceEngine resultIndex=$index target=${describeRememberedTrackForSwitchTrace(subtitleSelection.track)}"
                )
                if (index >= 0) {
                    val alreadySelected = subtitleTracks.getOrNull(index)?.isSelected == true
                    logSwitchTrace(
                        stage = "restore-subtitle-internal-match",
                        message = "index=$index alreadySelected=$alreadySelected " +
                            "matched=${subtitleTracks.getOrNull(index)?.let { describeTrackInfoForRestoreLog(it) }}"
                    )
                    autoSubtitleSelected = true
                    if (!alreadySelected) {
                        Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=$index (re-applying)")
                        selectSubtitleTrack(index)
                    } else {
                        Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=$index already selected, keeping for pipeline restart")
                    }
                    updatedSubtitleIndex = index
                } else {
                    val shouldDeferSwitchRestore = usingSwitchPending &&
                        switchSourceEngine == InternalPlayerEngine.EXOPLAYER &&
                        isUsingMpvEngine() &&
                        hasSparseMpvSubtitleMetadataForEngineSwitch(subtitleTracks)
                    logSwitchTrace(
                        stage = "restore-subtitle-internal-no-match",
                        message = "shouldDeferSwitchRestore=$shouldDeferSwitchRestore " +
                            "target=${describeRememberedTrackForSwitchTrace(subtitleSelection.track)} " +
                            "candidates=${describeTrackCandidatesForRestoreLog(subtitleTracks)}"
                    )
                    val resolvedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                        language = subtitleSelection.track.language,
                        name = subtitleSelection.track.name,
                        trackId = subtitleSelection.track.trackId
                    )
                    if (shouldDeferSwitchRestore) {
                        logSwitchTrace(
                            stage = "restore-subtitle-internal-no-match",
                            message = "action=defer reason=sparse-mpv-metadata"
                        )
                    } else {
                        val state = _uiState.value
                        val addonFallback = state.addonSubtitles.firstOrNull { subtitle ->
                            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, resolvedVariant)
                        }
                        if (addonFallback != null) {
                            logSwitchTrace(
                                stage = "restore-subtitle-internal-fallback-addon",
                                message = "addonId=${addonFallback.id} addonLang=${addonFallback.lang} variant=$resolvedVariant"
                            )
                            Log.d(
                                PlayerRuntimeController.TAG,
                                "TRACK_PREF restore: internal no match, falling back to addon lang=${addonFallback.lang} variant=$resolvedVariant"
                            )
                            autoSubtitleSelected = true
                            subtitleAddonRestoredByPersistedPreference = true
                            pendingRestoredAddonSubtitle = addonFallback
                            selectAddonSubtitle(addonFallback)
                            updatedAddonSubtitle = addonFallback
                            updatedPending = updatedPending.copy(subtitle = null)
                        } else {
                            logSwitchTrace(
                                stage = "restore-subtitle-internal-no-match",
                                message = "action=clear reason=no-addon-fallback variant=$resolvedVariant"
                            )
                            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle no match, no addon fallback for variant=$resolvedVariant, clearing")
                            updatedPending = updatedPending.copy(subtitle = null)
                        }
                    }
                }
            }
        }
        is PlayerRuntimeController.RememberedSubtitleSelection.Addon -> {
            val state = _uiState.value
            // Only restore addon subtitle on exact match (same addon + same track ID).
            // Looser matches should NOT override the normal auto-selection logic which
            // prefers embedded tracks over addon subtitles.
            val addonMatch = state.addonSubtitles.firstOrNull { subtitle ->
                subtitle.addonName == subtitleSelection.addonName && subtitle.id == subtitleSelection.id
            }
            if (addonMatch != null) {
                logSwitchTrace(
                    stage = "restore-subtitle-addon",
                    message = "result=match addonId=${addonMatch.id} addonLang=${addonMatch.lang} addon=${addonMatch.addonName}"
                )
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Restoring same-series addon subtitle lang=${addonMatch.lang} id=${addonMatch.id}"
                )
                autoSubtitleSelected = true
                subtitleAddonRestoredByPersistedPreference = true
                pendingRestoredAddonSubtitle = addonMatch
                selectAddonSubtitle(addonMatch)
                updatedAddonSubtitle = addonMatch
                // Keep the pending preference alive until MPV confirms the addon is active,
                // so a late track-list refresh can still restore it.
                val keepUntilMpvConfirms = usingSwitchPending && isUsingMpvEngine()
                val addonConfirmedByMpv = !keepUntilMpvConfirms || isMpvAddonSubtitleTrackActive(addonMatch)
                if (addonConfirmedByMpv) {
                    updatedPending = updatedPending.copy(subtitle = null)
                } else {
                    logSwitchTrace(
                        stage = "restore-subtitle-addon",
                        message = "result=defer-clear reason=mpv-addon-not-active-yet " +
                            "addonId=${addonMatch.id} addonLang=${addonMatch.lang}"
                    )
                }
            } else {
                val addonsStillLoading = state.isLoadingAddonSubtitles || state.addonSubtitles.isEmpty()
                if (addonsStillLoading) {
                    // Addon subtitles haven't loaded yet — keep the preference so it can be
                    // restored once they arrive. Block auto-selection to prevent internal
                    // tracks from overriding the user's choice.
                    logSwitchTrace(
                        stage = "restore-subtitle-addon",
                        message = "result=defer targetAddonId=${subtitleSelection.id} targetLang=${subtitleSelection.language} " +
                            "addonPool=${state.addonSubtitles.size} isLoadingAddonSubtitles=${state.isLoadingAddonSubtitles}"
                    )
                    autoSubtitleSelected = true
                    subtitleAddonRestoredByPersistedPreference = true
                } else {
                    logSwitchTrace(
                        stage = "restore-subtitle-addon",
                        message = "result=no-exact-match targetAddonId=${subtitleSelection.id} targetLang=${subtitleSelection.language} " +
                            "addonPool=${state.addonSubtitles.size}, falling back to auto-selection"
                    )
                    // Clear the persisted subtitle preference so auto-selection can run its
                    // normal logic (prefer embedded over addon).
                    updatedPending = updatedPending.copy(subtitle = null)
                    autoSubtitleSelected = false
                    subtitleAddonRestoredByPersistedPreference = false
                }
            }
        }
    }

    _uiState.update { state ->
        state.copy(
            selectedSubtitleTrackIndex = updatedSubtitleIndex ?: state.selectedSubtitleTrackIndex,
            selectedAddonSubtitle = updatedAddonSubtitle ?: if (updatedSubtitleIndex != null) null else state.selectedAddonSubtitle
        )
    }
    val normalizedPending = updatedPending.takeUnless { it.audio == null && it.subtitle == null }
    if (usingSwitchPending) {
        logSwitchTrace(
            stage = "restore-exit-switch",
            message = "remainingAudio=${describeRememberedTrackForSwitchTrace(normalizedPending?.audio)} " +
                "remainingSubtitle=${describeRememberedSubtitleForSwitchTrace(normalizedPending?.subtitle)}"
        )
        pendingEngineSwitchTrackPreference = normalizedPending?.let { preference ->
            PlayerRuntimeController.PendingEngineSwitchTrackPreference(
                streamUrl = currentStreamUrl,
                preference = preference,
                sourceEngine = switchSourceEngine ?: currentInternalPlayerEngine
            )
        }
    } else {
        logSwitchTrace(
            stage = "restore-exit-persisted",
            message = "remainingAudio=${describeRememberedTrackForSwitchTrace(normalizedPending?.audio)} " +
                "remainingSubtitle=${describeRememberedSubtitleForSwitchTrace(normalizedPending?.subtitle)}"
        )
        persistedTrackPreference = normalizedPending
    }
}

internal fun PlayerRuntimeController.subtitleLanguageTargets(): List<String> {
    val preferred = _uiState.value.subtitleStyle.preferredLanguage.lowercase()
    if (preferred == "none") return emptyList()
    val secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage?.lowercase()
    return listOfNotNull(preferred, secondary)
}

/**
 * Find the best internal subtitle track index for the given priority-ordered language
 * targets. Handles forced-only/normal-only filtering, regional variant resolution
 * (pt vs pt-br, es vs es-419), and tie-breaking by regional tags.
 */
internal fun PlayerRuntimeController.findBestInternalSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>,
    forcedOnly: Boolean = false,
    normalOnly: Boolean = false,
    selectedAudioTrack: TrackInfo? = null
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (forcedOnly) {
            val forcedIndex = findBestForcedSubtitleTrackIndex(
                subtitleTracks = subtitleTracks,
                target = target,
                selectedAudioTrack = selectedAudioTrack
            )
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }
        val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
        val candidates = subtitleTracks.indices.filter { idx ->
            val track = subtitleTracks[idx]
            (!normalOnly || !track.isForced) && subtitleTrackMatchesLanguage(track, target)
        }
        if (candidates.isEmpty()) {
            // Regional-target fallback: a track with a generic "pt"/"es" language may still
            // be the right regional variant based on its name/trackId tags.
            if (normalizedTarget == "pt-br") {
                val brazilianFromGeneric = findBrazilianPortugueseInGenericPtTracks(subtitleTracks, normalOnly)
                if (brazilianFromGeneric >= 0) {
                    Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick internal pt-br via generic-pt tags index=$brazilianFromGeneric")
                    return brazilianFromGeneric
                }
                if (targetPosition == 0) return -1
            }
            if (normalizedTarget == "es-419") {
                val latinoFromGeneric = findLatinoSpanishInGenericEsTracks(subtitleTracks, normalOnly)
                if (latinoFromGeneric >= 0) {
                    Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick internal es-419 via generic-es tags index=$latinoFromGeneric")
                    return latinoFromGeneric
                }
                if (targetPosition == 0) return -1
            }
            continue
        }
        val preferred = candidates.filter { idx -> !subtitleTracks[idx].isForced }
            .takeIf { it.isNotEmpty() }
            ?: if (normalOnly) {
                // Forced tracks are the only candidates but we explicitly want non-forced.
                continue
            } else {
                candidates
            }
        if (preferred.size == 1) {
            // For regional targets, verify the single candidate is actually the right variant.
            // A track with language="por" matches both "pt" and "pt-br" by language code,
            // but may be the wrong accent based on its name tags.
            if (normalizedTarget == "pt" || normalizedTarget == "es") {
                val track = subtitleTracks[preferred.first()]
                val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                )
                if (variant != normalizedTarget && variant != track.language?.lowercase()) {
                    // Single candidate is a different variant (e.g. PT-BR when we want PT).
                    continue
                }
            }
            return preferred.first()
        }

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = preferred,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        if (normalizedTarget == "es" || normalizedTarget == "es-419") {
            val tieBroken = breakSpanishSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = preferred,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return preferred.first()
    }
    return -1
}

private fun findBestForcedSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    target: String,
    selectedAudioTrack: TrackInfo?
): Int {
    // isForced is set from both the ExoPlayer SELECTION_FLAG_FORCED and name/label/id
    // containing "forced".
    val directMatch = subtitleTracks.indexOfFirst { track ->
        track.isForced &&
            subtitleTrackMatchesLanguage(track, target) &&
            selectedAudioTrack != null &&
            subtitleTrackMatchesSelectedAudioLanguage(track, selectedAudioTrack)
    }
    if (directMatch >= 0) return directMatch

    // For regional variants (pt-br, es-419) the track may have a generic language code
    // but carry regional tags in its name/trackId. Use detectTrackLanguageVariant to
    // resolve the actual variant and match against the target.
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    if (normalizedTarget == "pt-br" || normalizedTarget == "es-419") {
        return subtitleTracks.indexOfFirst { track ->
            track.isForced &&
                selectedAudioTrack != null &&
                subtitleTrackMatchesSelectedAudioLanguage(track, selectedAudioTrack) &&
                PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                ) == normalizedTarget
        }
    }
    return -1
}

private fun subtitleTrackMatchesLanguage(track: TrackInfo, target: String): Boolean =
    trackMatchesLanguage(track.name, track.language, track.trackId, target)

private fun audioTrackMatchesLanguage(track: TrackInfo, target: String): Boolean =
    trackMatchesLanguage(track.name, track.language, track.trackId, target)

private fun trackMatchesLanguage(
    name: String?,
    language: String?,
    trackId: String?,
    target: String
): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(language, target)) return true
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val targetName = languageCodeToName(target).lowercase(Locale.ROOT)
    val haystack = listOfNotNull(name, language, trackId).joinToString(" ").lowercase(Locale.ROOT)
    return languageCodeAppearsInHaystack(haystack, normalizedTarget) ||
        (targetName.isNotBlank() && haystack.contains(targetName))
}

internal fun PlayerRuntimeController.selectedAudioMatchesResolvedPreferredAudio(track: TrackInfo): Boolean =
    mpvPreferredAudioLanguages.any { target -> audioTrackMatchesLanguage(track, target) }

/**
 * Word-boundary check for a normalized language code in a haystack. Prevents "es" from
 * matching inside "he" or "pt" inside "pt-br" without explicit boundary handling.
 */
private fun languageCodeAppearsInHaystack(haystack: String, normalizedTarget: String): Boolean {
    if (normalizedTarget.isBlank()) return false
    var searchFrom = 0
    while (searchFrom <= haystack.length - normalizedTarget.length) {
        val matchIndex = haystack.indexOf(normalizedTarget, startIndex = searchFrom)
        if (matchIndex < 0) return false
        val before = matchIndex - 1
        val after = matchIndex + normalizedTarget.length
        val startsAtBoundary = before < 0 || !haystack[before].isLetterOrDigit()
        val endsAtBoundary = after >= haystack.length || !haystack[after].isLetterOrDigit()
        if (startsAtBoundary && endsAtBoundary) return true
        searchFrom = matchIndex + 1
    }
    return false
}

private fun subtitleTrackMatchesSelectedAudioLanguage(
    subtitleTrack: TrackInfo,
    selectedAudioTrack: TrackInfo
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (subtitleTrackMatchesLanguage(subtitleTrack, audioLanguage)) return true
    }
    val subtitleLangName = subtitleTrack.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { languageCodeToName(it).lowercase(Locale.ROOT) }
    val audioHaystack = listOfNotNull(selectedAudioTrack.name, selectedAudioTrack.language, selectedAudioTrack.trackId)
        .joinToString(" ").lowercase(Locale.ROOT)
    return !subtitleLangName.isNullOrBlank() && audioHaystack.contains(subtitleLangName)
}

internal fun selectedAudioTrackForSubtitleMatching(state: PlayerUiState): TrackInfo? =
    state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }

internal fun selectedAudioLanguageTarget(track: TrackInfo): String? {
    track.language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }?.let { return it }
    val haystack = listOfNotNull(track.name, track.trackId).joinToString(" ").lowercase(Locale.ROOT)
    return AVAILABLE_SUBTITLE_LANGUAGES.firstOrNull { language ->
        val code = language.code.lowercase(Locale.ROOT)
        val name = languageCodeToName(language.code).lowercase(Locale.ROOT)
        languageCodeAppearsInHaystack(haystack, code) || (name.isNotBlank() && haystack.contains(name))
    }?.code
}

private fun addonSubtitleIsForced(subtitle: Subtitle): Boolean =
    listOf(subtitle.id, subtitle.url, subtitle.addonName).any { it.contains("forced", ignoreCase = true) }

private fun addonSubtitleMatchesLanguage(subtitle: Subtitle, target: String): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)) return true
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val targetName = languageCodeToName(target).lowercase(Locale.ROOT)
    val haystack = listOf(subtitle.lang, subtitle.id, subtitle.url, subtitle.addonName)
        .joinToString(" ").lowercase(Locale.ROOT)
    return languageCodeAppearsInHaystack(haystack, normalizedTarget) ||
        (targetName.isNotBlank() && haystack.contains(targetName))
}

private fun addonSubtitleMatchesSelectedAudioLanguage(
    subtitle: Subtitle,
    selectedAudioTrack: TrackInfo
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (addonSubtitleMatchesLanguage(subtitle, audioLanguage)) return true
    }
    val subtitleLangName = subtitle.lang
        .takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { languageCodeToName(it).lowercase(Locale.ROOT) }
    val audioHaystack = listOfNotNull(selectedAudioTrack.name, selectedAudioTrack.language, selectedAudioTrack.trackId)
        .joinToString(" ").lowercase(Locale.ROOT)
    return !subtitleLangName.isNullOrBlank() && audioHaystack.contains(subtitleLangName)
}

internal fun PlayerRuntimeController.findBrazilianPortugueseInGenericPtTracks(
    subtitleTracks: List<TrackInfo>,
    normalOnly: Boolean = false
): Int {
    val genericPt = subtitleTracks.indices.filter { idx ->
        if (normalOnly && subtitleTracks[idx].isForced) return@filter false
        val trackLanguage = subtitleTracks[idx].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPt.isEmpty()) return -1

    val brazilianNonForced = genericPt.filter { idx ->
        !subtitleTracks[idx].isForced &&
            subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    }
    if (brazilianNonForced.isNotEmpty()) return brazilianNonForced.first()

    return genericPt.firstOrNull { idx ->
        subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    } ?: genericPt.firstOrNull { idx ->
        subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakPortugueseSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilian(idx: Int) = subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    fun hasEuropean(idx: Int) = subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)

    return if (normalizedTarget == "pt-br") {
        candidateIndexes.firstOrNull { hasBrazilian(it) && !hasEuropean(it) }
            ?: candidateIndexes.firstOrNull { hasBrazilian(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasEuropean(it) && !hasBrazilian(it) }
            ?: candidateIndexes.firstOrNull { hasEuropean(it) }
            ?: candidateIndexes.firstOrNull { !hasBrazilian(it) }
            ?: candidateIndexes.first()
    }
}

internal fun PlayerRuntimeController.findLatinoSpanishInGenericEsTracks(
    subtitleTracks: List<TrackInfo>,
    normalOnly: Boolean = false
): Int {
    val genericEs = subtitleTracks.indices.filter { idx ->
        if (normalOnly && subtitleTracks[idx].isForced) return@filter false
        val trackLanguage = subtitleTracks[idx].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "es"
    }
    if (genericEs.isEmpty()) return -1

    val latinoNonForced = genericEs.filter { idx ->
        !subtitleTracks[idx].isForced &&
            subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.LATINO_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.CASTILIAN_TAGS)
    }
    if (latinoNonForced.isNotEmpty()) return latinoNonForced.first()

    return genericEs.firstOrNull { idx ->
        subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.LATINO_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.CASTILIAN_TAGS)
    } ?: genericEs.firstOrNull { idx ->
        subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.LATINO_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakSpanishSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasLatino(idx: Int) = subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.LATINO_TAGS)
    fun hasCastilian(idx: Int) = subtitleHasAnyTag(subtitleTracks[idx], PlayerSubtitleUtils.CASTILIAN_TAGS)

    return if (normalizedTarget == "es-419") {
        candidateIndexes.firstOrNull { hasLatino(it) && !hasCastilian(it) }
            ?: candidateIndexes.firstOrNull { hasLatino(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasCastilian(it) && !hasLatino(it) }
            ?: candidateIndexes.firstOrNull { hasCastilian(it) }
            ?: candidateIndexes.firstOrNull { !hasLatino(it) }
            ?: candidateIndexes.first()
    }
}

internal fun PlayerRuntimeController.subtitleHasAnyTag(track: TrackInfo, tags: List<String>): Boolean {
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ").lowercase(Locale.ROOT)
    return tags.any { tag -> haystack.contains(tag) }
}

/**
 * More lenient than [audioTrackMatchesLanguage] for regional variants: if the target is
 * "pt-br" and the audio language is generic "pt", consider it a match because the audio
 * is likely Brazilian Portuguese even without explicit regional tags. Same for "es-419"
 * matching generic "es" audio.
 */
private fun audioMatchesSubtitleTargetForForced(audioTrack: TrackInfo, target: String): Boolean {
    if (audioTrackMatchesLanguage(audioTrack, target)) return true
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val baseTarget = normalizedTarget.substringBefore('-')
    if (baseTarget == normalizedTarget) return false // not a regional variant
    val audioVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = audioTrack.language,
        name = audioTrack.name,
        trackId = audioTrack.trackId
    )
    // If audio is generic base (e.g. "pt") or the same regional variant (e.g. "pt-br"), match.
    return audioVariant == baseTarget || audioVariant == normalizedTarget
}

/**
 * Auto-select the preferred subtitle track from the currently available internal/addon
 * track pools. Honors forced-subtitle mode, primary/secondary language priority, and the
 * "prefer embedded over addon" rule. Defers until text tracks have been scanned and the
 * player is ready so we don't pick an addon too early.
 */
internal fun PlayerRuntimeController.tryAutoSelectPreferredSubtitleFromAvailableTracks() {
    if (autoSubtitleSelected) return

    val state = _uiState.value
    val preferredTargets = subtitleLanguageTargets()
    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val primaryTarget = preferredTargets.firstOrNull()
    val useForcedSubtitles = state.subtitleStyle.useForcedSubtitles
    val forcedTarget = when {
        !useForcedSubtitles -> null
        primaryTarget != null && selectedAudioTrack != null &&
            audioMatchesSubtitleTargetForForced(selectedAudioTrack, primaryTarget) -> primaryTarget
        primaryTarget == null && selectedAudioTrack != null &&
            selectedAudioMatchesResolvedPreferredAudio(selectedAudioTrack) ->
            selectedAudioLanguageTarget(selectedAudioTrack)
        else -> null
    }
    val forcedOnly = forcedTarget != null
    val targets = when {
        forcedTarget != null -> listOf(forcedTarget)
        primaryTarget != null -> preferredTargets
        else -> emptyList()
    }
    Log.d(
        PlayerRuntimeController.TAG,
        "AUTO_SUB eval: targets=$targets, forcedOnly=$forcedOnly, selectedAudio=${selectedAudioTrack?.language}/${selectedAudioTrack?.name}, scannedText=$hasScannedTextTracksOnce, " +
            "internalCount=${state.subtitleTracks.size}, selectedInternal=${state.selectedSubtitleTrackIndex}, " +
            "selectedInternalDetails=${state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.describeSubtitleTrack(selectedOverride = true)}, " +
            "addonCount=${state.addonSubtitles.size}, selectedAddon=${state.selectedAddonSubtitle?.lang}"
    )
    if (useForcedSubtitles && selectedAudioTrack == null) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer: selected audio track unknown")
        return
    }
    if (targets.isEmpty()) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred=none")
        if (isUsingMpvEngine()) mpvView?.disableSubtitles()
        return
    }

    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = targets,
        forcedOnly = forcedOnly,
        normalOnly = !forcedOnly,
        selectedAudioTrack = selectedAudioTrack
    )
    if (internalIndex >= 0 && hasScannedTextTracksOnce) {
        // Determine which target position this internal match satisfies, taking regional
        // variant into account so a PT-BR track is not treated as a primary match when the
        // user wants PT.
        val matchedTrack = state.subtitleTracks[internalIndex]
        val trackVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = matchedTrack.language,
            name = matchedTrack.name,
            trackId = matchedTrack.trackId
        )
        val matchedTargetPosition = targets.indexOfFirst { target ->
            val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
            trackVariant == normalizedTarget ||
                PlayerSubtitleUtils.matchesLanguageCode(trackVariant, target)
        }
        val addonsLoaded = !state.isLoadingAddonSubtitles
        if (matchedTargetPosition > 0 && !addonsLoaded) {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SUB defer: internal match is secondary target pos=$matchedTargetPosition, addons still loading"
            )
            return
        }
        // If internal match is secondary and a primary addon match exists, prefer the addon.
        if (matchedTargetPosition > 0 && addonsLoaded) {
            val primary = targets.first()
            val primaryAddonMatch = state.addonSubtitles.firstOrNull { subtitle ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, primary)
            }
            if (primaryAddonMatch != null) {
                autoSubtitleSelected = true
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB pick addon (primary) over internal (secondary): addon lang=${primaryAddonMatch.lang} vs internal variant=$trackVariant"
                )
                selectAddonSubtitle(primaryAddonMatch)
                return
            }
        }
        autoSubtitleSelected = true
        val currentInternal = state.selectedSubtitleTrackIndex
        val currentAddon = state.selectedAddonSubtitle
        if (currentInternal != internalIndex || currentAddon != null) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick internal index=$internalIndex lang=${state.subtitleTracks[internalIndex].language}")
            selectSubtitleTrack(internalIndex)
            _uiState.update { it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null) }
        } else {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred internal already selected")
        }
        return
    }

    if (forcedOnly) {
        val requiredForcedTarget = forcedTarget ?: return
        if (!hasScannedTextTracksOnce) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer forced: text tracks not scanned yet")
            return
        }
        if (state.isLoadingAddonSubtitles) {
            // Disable any non-forced subtitle ExoPlayer auto-selected while we wait.
            val hasNonForcedActive = state.selectedSubtitleTrackIndex >= 0 &&
                state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.isForced != true
            if (hasNonForcedActive) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SUB forced: disabling non-forced subtitle while addons load")
                disableSubtitles()
                _uiState.update { it.copy(selectedSubtitleTrackIndex = -1) }
            }
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer forced: addon subtitles still loading")
            return
        }
        val forcedAddonMatch = state.addonSubtitles.firstOrNull { subtitle ->
            addonSubtitleIsForced(subtitle) &&
                addonSubtitleMatchesLanguage(subtitle, requiredForcedTarget) &&
                selectedAudioTrack != null &&
                addonSubtitleMatchesSelectedAudioLanguage(subtitle, selectedAudioTrack)
        }
        if (forcedAddonMatch != null) {
            autoSubtitleSelected = true
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick forced addon lang=${forcedAddonMatch.lang} id=${forcedAddonMatch.id}")
            selectAddonSubtitle(forcedAddonMatch)
            return
        }
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: forced subtitles requested but no forced match found")
        disableSubtitles()
        return
    }

    val selectedAddon = state.selectedAddonSubtitle
    val selectedAddonMatchesTarget = selectedAddon != null &&
        (!useForcedSubtitles || !addonSubtitleIsForced(selectedAddon)) &&
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(selectedAddon.lang, target) }
    if (selectedAddonMatchesTarget) {
        val matching = selectedAddon ?: return
        val selectedMatchesPrimary = PlayerSubtitleUtils.matchesLanguageCode(matching.lang, targets.first())
        if (selectedMatchesPrimary) {
            autoSubtitleSelected = true
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: matching addon already selected (primary match)")
            return
        }
        Log.d(
            PlayerRuntimeController.TAG,
            "AUTO_SUB: selected addon ${matching.lang} matches secondary target, checking for primary addon"
        )
    }

    // Wait until we have at least one full text-track scan to avoid choosing addon too early.
    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: text tracks not scanned yet")
        return
    }

    val playerReady = if (isUsingMpvEngine()) mpvView != null
    else _exoPlayer?.playbackState == Player.STATE_READY
    if (!playerReady) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: player not ready")
        return
    }

    val addonMatch = run {
        // Try each target in priority order so primary language is preferred over secondary.
        for (target in targets) {
            val match = state.addonSubtitles.firstOrNull { subtitle ->
                (!useForcedSubtitles || !addonSubtitleIsForced(subtitle)) &&
                    PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
            }
            if (match != null) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB addon fallback: target=$target matched addon lang=${match.lang} id=${match.id} " +
                        "(addons=${state.addonSubtitles.size}, targets=$targets)"
                )
                return@run match
            }
        }
        null
    }
    if (addonMatch != null) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick addon lang=${addonMatch.lang} id=${addonMatch.id}")
        selectAddonSubtitle(addonMatch)
    } else {
        val internalSummary = state.subtitleTracks.joinToString(" | ") { track ->
            "${track.index}:${track.language ?: "null"}/${track.name}:forced=${track.isForced}"
        }
        val addonSummary = state.addonSubtitles.joinToString(" | ") { subtitle ->
            "${subtitle.id}:${subtitle.lang}:${subtitle.format ?: "unknown"}"
        }
        Log.d(
            PlayerRuntimeController.TAG,
            "AUTO_SUB no addon match for targets=$targets internalTracks=[$internalSummary] " +
                "addonTracks=[$addonSummary]",
        )
    }
}

/**
 * Probe the source for the frame rate after a grace period. Cancels any prior probe. When
 * [preserveCurrentDetection] is true, only the afrProbeRunning flag is reset; the detected
 * rate is kept. When [allowAmbiguousTrackOverride] is true, a probe result may override an
 * ambiguous 24fps cinema track detection.
 */
internal fun PlayerRuntimeController.startFrameRateProbe(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingEnabled: Boolean,
    preserveCurrentDetection: Boolean = false,
    allowAmbiguousTrackOverride: Boolean = false
) {
    frameRateProbeJob?.cancel()
    _uiState.update { state ->
        if (!preserveCurrentDetection) {
            state.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        } else {
            state.copy(afrProbeRunning = false)
        }
    }
    if (!frameRateMatchingEnabled) return

    val token = ++frameRateProbeToken
    frameRateProbeJob = scope.launch(Dispatchers.IO) {
        try {
            delay(PlayerRuntimeController.TRACK_FRAME_RATE_GRACE_MS)
            if (!isActive) return@launch
            val stateSnapshot = withContext(Dispatchers.Main) { _uiState.value }
            val trackAlreadySet = stateSnapshot.detectedFrameRateSource == FrameRateSource.TRACK &&
                stateSnapshot.detectedFrameRate > 0f
            if (trackAlreadySet) {
                if (!allowAmbiguousTrackOverride) return@launch
                val trackRaw = if (stateSnapshot.detectedFrameRateRaw > 0f) {
                    stateSnapshot.detectedFrameRateRaw
                } else {
                    stateSnapshot.detectedFrameRate
                }
                if (!PlayerFrameRateHeuristics.isAmbiguousCinema24(trackRaw)) return@launch
            }

            withContext(Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = true) }
                }
            }

            val detection = FrameRateUtils.detectFrameRateFromSource(context, url, headers)
                ?: return@launch
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    val state = _uiState.value
                    val shouldApplyInitial = state.detectedFrameRate <= 0f
                    val shouldOverrideAmbiguousTrack = allowAmbiguousTrackOverride &&
                        PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection)
                    if (shouldApplyInitial || shouldOverrideAmbiguousTrack) {
                        _uiState.update {
                            it.copy(
                                detectedFrameRateRaw = detection.raw,
                                detectedFrameRate = detection.snapped,
                                detectedFrameRateSource = FrameRateSource.PROBE
                            )
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = false) }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.applySubtitlePreferences(preferred: String, secondary: String?) {
    if (isUsingMpvEngine()) {
        mpvView?.applySubtitleLanguagePreferences(
            preferred = preferred,
            secondary = secondary,
            preferAss = shouldPreferAssSubtitles(),
        )
        // Anime has an explicit playback default: English ASS. Do not immediately undo the
        // MPV fallback when the global subtitle preference is still unset/"none".
        if (preferred == "none" && !shouldPreferAssSubtitles()) {
            mpvView?.disableSubtitles()
            _uiState.update { it.copy(selectedSubtitleTrackIndex = -1, selectedAddonSubtitle = null) }
        }
        return
    }

    _exoPlayer?.let { player ->
        val builder = player.trackSelectionParameters.buildUpon()

        if (preferred == "none") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.setPreferredTextLanguage(null)
        } else {
            val userDisabledSubtitles = autoSubtitleSelected &&
                _uiState.value.selectedSubtitleTrackIndex == -1 &&
                _uiState.value.selectedAddonSubtitle == null
            // Suppress ExoPlayer auto-select when forced mode is active — our custom logic
            // handles track selection.
            val useForcedSubtitles = _uiState.value.subtitleStyle.useForcedSubtitles
            val shouldSuppressExoAutoSelect = useForcedSubtitles && !autoSubtitleSelected
            if (!userDisabledSubtitles && !shouldSuppressExoAutoSelect) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            if (!shouldSuppressExoAutoSelect) {
                builder.setPreferredTextLanguage(preferred)
            }
        }

        // When forced subtitles are disabled, tell ExoPlayer to ignore SELECTION_FLAG_FORCED.
        val useForcedSubtitles = _uiState.value.subtitleStyle.useForcedSubtitles
        val currentFlags = player.trackSelectionParameters.ignoredTextSelectionFlags
        val newFlags = if (!useForcedSubtitles) {
            currentFlags or C.SELECTION_FLAG_FORCED
        } else {
            currentFlags and C.SELECTION_FLAG_FORCED.inv()
        }
        builder.setIgnoredTextSelectionFlags(newFlags)
        player.trackSelectionParameters = builder.build()
    }
}
