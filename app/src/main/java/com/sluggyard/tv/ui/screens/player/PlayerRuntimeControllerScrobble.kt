package com.sluggyard.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.sluggyard.tv.data.local.toTrackPreference
import com.sluggyard.tv.domain.model.WatchProgress

internal fun PlayerRuntimeController.preparePlaybackBeforeStart(
    url: String,
    headers: Map<String, String>,
    loadSavedProgress: Boolean
) {
    logSwitchTrace(
        stage = "prepare-playback-before-start",
        message = "urlHash=${url.hashCode().toUInt().toString(16)} loadSavedProgress=$loadSavedProgress " +
            "clearPendingSwitchPref=true"
    )
    val clickElapsedMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    queuePlaybackRawEventLine(
        "PREPARE_PLAYBACK: clickElapsedMs=$clickElapsedMs host=${url.safeScrobbleHost()} " +
            "loadSavedProgress=$loadSavedProgress currentSeason=${currentSeason ?: -1} " +
            "currentEpisode=${currentEpisode ?: -1} streamName=${_uiState.value.currentStreamName ?: "n/a"}"
    )
    clearPendingEngineSwitchTrackPreference()
    playbackPreparationJob?.cancel()

    // Warm the Trakt episode mapping in the background (fire-and-forget).
    traktMappingJob?.cancel()
    traktMappingJob = scope.launch { warmTraktEpisodeMappingForCurrentPlayback() }

    playbackPreparationJob = scope.launch {
        setLoadingStatus(
            phase = "preparing_metadata",
            message = context.getString(com.sluggyard.tv.R.string.player_loading_preparing)
        )
        refreshScrobbleItem()

        if (persistedTrackPreference == null) {
            contentId?.let { id ->
                val loaded = trackPreferenceDataStore.load(id)?.toTrackPreference()
                logSwitchTrace(
                    stage = "track-pref-load",
                    message = "contentId=$id loadedAudio=${loaded?.audio?.language}/${loaded?.audio?.name} " +
                        "loadedSubtitle=${loaded?.subtitle?.javaClass?.simpleName ?: "none"}"
                )
                Log.d(
                    PlayerRuntimeController.TAG,
                    "TRACK_PREF load: contentId=$id S${currentSeason}E${currentEpisode} " +
                        "result=${if (loaded == null) "null (no saved preference)" else "audio=${loaded.audio?.language}/${loaded.audio?.name} subtitle=${loaded.subtitle?.javaClass?.simpleName}"}"
                )
                persistedTrackPreference = loaded
            } ?: Log.d(PlayerRuntimeController.TAG, "TRACK_PREF load: skipped (contentId is null)")

            // Subtitle delay is keyed per-videoId and loaded separately from track
            // selection. This must run before initializePlayer() because the
            // renderers factory snapshots subtitleDelayUs from
            // _uiState.value.subtitleDelayMs at build time.
            currentVideoId?.takeIf { it.isNotBlank() }?.let { vid ->
                val savedDelayMs = trackPreferenceDataStore.loadSubtitleDelayMs(vid)
                if (savedDelayMs != null && savedDelayMs != 0) {
                    subtitleDelayUs.set(savedDelayMs.toLong() * 1000L)
                    _uiState.update { it.copy(subtitleDelayMs = savedDelayMs) }
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "TRACK_PREF load: restored subtitleDelayMs=$savedDelayMs for videoId=$vid"
                    )
                }
            }
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "TRACK_PREF load: skipped (persistedTrackPreference already set: " +
                    "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                    "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName})"
            )
            logSwitchTrace(
                stage = "track-pref-load",
                message = "skipped=true reason=persisted-already-set " +
                    "audio=${persistedTrackPreference?.audio?.language}/${persistedTrackPreference?.audio?.name} " +
                    "subtitle=${persistedTrackPreference?.subtitle?.javaClass?.simpleName ?: "none"}"
            )
        }

        // Load saved watch progress BEFORE player init. Doing it here removes the
        // race where ExoPlayer's STATE_READY fired before the DB read finished,
        // silently dropping the resume seek and leaving playback stuck at 0:00
        // or buffering after a late seek.
        if (loadSavedProgress) {
            recordLoadingDiagnosticEvent(
                phase = "loading_saved_progress",
                message = context.getString(com.sluggyard.tv.R.string.player_loading_preparing)
            )
            loadSavedProgressSuspend(currentSeason, currentEpisode)
        }
        // Prefer the newer of rewrite checkpoint (startPositionMs) vs legacy watch DB.
        navigationArgs.startPositionMs?.takeIf { it > 0L }?.let { positionMs ->
            val existing = pendingResumeProgress
            if (existing == null || positionMs >= existing.position) {
                pendingResumeProgress = WatchProgress(
                    contentId = contentId.orEmpty(),
                    contentType = contentType.orEmpty(),
                    name = title,
                    poster = poster,
                    backdrop = backdrop,
                    logo = logo,
                    videoId = currentVideoId ?: contentId.orEmpty(),
                    season = currentSeason,
                    episode = currentEpisode,
                    episodeTitle = currentEpisodeTitle,
                    position = positionMs,
                    duration = existing?.duration?.takeIf { it > 0L } ?: 0L,
                    lastWatched = System.currentTimeMillis(),
                    // Keep Trakt percent if present so resolveResumePosition can re-map onto
                    // the real stream duration once known.
                    progressPercent = existing?.progressPercent,
                )
            }
        }
        recordLoadingDiagnosticEvent(
            phase = "initializing_player",
            message = context.getString(com.sluggyard.tv.R.string.player_loading_building)
        )
        initializePlayer(url, headers)
    }
}

private fun String.safeScrobbleHost(): String {
    return runCatching {
        android.net.Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    if (!traktEpisodeMappingService.isTraktAuthenticated()) {
        clearTraktEpisodeMapping()
        return
    }

    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        clearTraktEpisodeMapping()
        return
    }

    val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: run {
        clearTraktEpisodeMapping()
        return
    }
    val season = currentSeason ?: run {
        clearTraktEpisodeMapping()
        return
    }
    val episode = currentEpisode ?: run {
        clearTraktEpisodeMapping()
        return
    }

    currentTraktEpisodeMapping = withTimeoutOrNull(12_000L) {
        traktEpisodeMappingService.prefetchEpisodeMapping(
            contentId = resolvedContentId,
            contentType = contentType,
            videoId = currentVideoId,
            season = season,
            episode = episode
        )
    }
    currentTraktEpisodeMappingKey = currentEpisodeMappingCacheKey()
}

private fun PlayerRuntimeController.clearTraktEpisodeMapping() {
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
}

internal fun PlayerRuntimeController.currentEpisodeMappingCacheKey(): String? {
    val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val season = currentSeason ?: return null
    val episode = currentEpisode ?: return null
    val videoId = currentVideoId?.trim().orEmpty()
    return "$resolvedType|$resolvedContentId|$videoId|$season|$episode"
}
