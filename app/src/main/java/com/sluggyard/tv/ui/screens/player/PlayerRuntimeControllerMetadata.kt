package com.sluggyard.tv.ui.screens.player

import com.sluggyard.tv.R
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.data.local.AutoSkipSegmentType
import com.sluggyard.tv.data.repository.SkipInterval
import com.sluggyard.tv.domain.model.ContentType
import com.sluggyard.tv.domain.model.Meta
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.domain.model.resolveContentLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.fetchMetaDetailsForCurrentContent() {
    val lookup = PlayerMetaLookup.resolve(
        contentId = contentId,
        contentType = contentType,
        parentId = parentId,
        parentType = parentType,
    ) ?: return
    fetchMetaDetails(lookup.id, lookup.type)
}

internal fun PlayerRuntimeController.fetchMetaDetails(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return

    scope.launch {
        val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
            .first { it !is NetworkResult.Loading }
        when (result) {
            is NetworkResult.Success -> applyMetaDetails(result.data)
            is NetworkResult.Error -> Unit
            NetworkResult.Loading -> Unit
        }
    }

    scope.launch { enrichDescriptionFromTmdb(id, type) }
}

internal fun PlayerRuntimeController.applyMetaDetails(meta: Meta) {
    metaVideos = meta.videos
    metaGenres = meta.genres
    metaCountry = meta.country
    // Feed AUTO engine classification: rewrite launches often omit genres/language,
    // so meta is the authoritative signal for anime → MPV.
    if (contentGenres.isNullOrBlank() && meta.genres.isNotEmpty()) {
        contentGenres = meta.genres.joinToString(",")
    }
    // Adopt the meta-derived content language only if navigation args didn't supply one.
    if (contentLanguage == null) {
        contentLanguage = meta.resolveContentLanguage()
    }
    val description = resolveDescription(meta)

    _uiState.update { state ->
        state.copy(
            description = description ?: state.description,
            imdbRating = meta.imdbRating ?: state.imdbRating,
            castMembers = if (meta.castMembers.isNotEmpty()) meta.castMembers else state.castMembers
        )
    }
    recomputeNextEpisode(resetVisibility = false)
    maybeReselectAutoEngineForContentClassification()
}

internal fun PlayerRuntimeController.resolveDescription(meta: Meta): String? {
    val type = contentType
    // For series, prefer the current episode's overview when available.
    // Absolute-numbered anime may omit season; match on episode alone in that case.
    if (type in listOf("series", "tv") && currentEpisode != null) {
        val episodeOverview = meta.videos.firstOrNull { video ->
            video.episode == currentEpisode &&
                (currentSeason == null || video.season == null || video.season == currentSeason)
        }?.overview
        if (!episodeOverview.isNullOrBlank()) return episodeOverview
    }
    return meta.description
}

internal fun PlayerRuntimeController.updateEpisodeDescription() {
    val overview = metaVideos.firstOrNull { video ->
        video.episode == currentEpisode &&
            (currentSeason == null || video.season == null || video.season == currentSeason)
    }?.overview

    if (!overview.isNullOrBlank()) {
        _uiState.update { it.copy(description = overview) }
    }

    // Mirror the new episode into the MediaSession so Google Home reflects it.
    updateMediaSessionMetadata()

    // Re-enrich from TMDB for the freshly selected episode using the series/parent id.
    val lookup = PlayerMetaLookup.resolve(
        contentId = contentId,
        contentType = contentType,
        parentId = parentId,
        parentType = parentType,
    )
    if (lookup != null) {
        scope.launch { enrichDescriptionFromTmdb(lookup.id, lookup.type) }
    }
}

private suspend fun PlayerRuntimeController.enrichDescriptionFromTmdb(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return
    val settings = tmdbSettingsDataStore.settings.first()
    if (!settings.enabled || !settings.useBasicInfo) return

    val tmdbId = runCatching { tmdbService.ensureTmdbId(id, type) }.getOrNull() ?: return
    val resolvedContentType = when (type.lowercase()) {
        "series", "tv" -> ContentType.SERIES
        else -> ContentType.MOVIE
    }
    val enrichment = runCatching {
        tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = resolvedContentType,
            language = settings.language
        )
    }.getOrNull() ?: return

    val isSeries = type.lowercase() in listOf("series", "tv")
    val season = currentSeason
    val episode = currentEpisode

    // For series, pull episode-level overview/title from TMDB when possible.
    val episodeEnrichment = if (isSeries && season != null && episode != null) {
        runCatching {
            tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = listOf(season),
                language = settings.language
            )[season to episode]
        }.getOrNull()
    } else null

    val tmdbDescription = episodeEnrichment?.overview ?: enrichment.description
    if (settings.useBasicInfo && !tmdbDescription.isNullOrBlank()) {
        _uiState.update { it.copy(description = tmdbDescription) }
    }

    // TMDB score for the pause overlay when the addon meta carried no IMDb rating.
    enrichment.rating?.takeIf { it > 0.0 }?.let { rating ->
        _uiState.update { it.copy(tmdbVoteAverage = rating) }
    }

    // Localized title from TMDB.
    if (settings.useBasicInfo) {
        val tmdbTitle = enrichment.localizedTitle
        if (!tmdbTitle.isNullOrBlank()) {
            _uiState.update { it.copy(title = tmdbTitle) }
        }
    }

    // Logo artwork from TMDB.
    if (settings.useArtwork) {
        val tmdbLogo = enrichment.logo
        if (!tmdbLogo.isNullOrBlank()) {
            _uiState.update { it.copy(logo = tmdbLogo) }
        }
    }

    // Episode title from TMDB.
    if (settings.useBasicInfo) {
        val tmdbEpisodeTitle = episodeEnrichment?.title
        if (!tmdbEpisodeTitle.isNullOrBlank()) {
            _uiState.update { it.copy(currentEpisodeTitle = tmdbEpisodeTitle) }
        }
    }

    // Cast from TMDB only if the addon didn't supply any.
    if (settings.useBasicInfo && enrichment.castMembers.isNotEmpty()) {
        _uiState.update { state ->
            if (state.castMembers.isEmpty()) state.copy(castMembers = enrichment.castMembers)
            else state
        }
    }

    // Refresh MediaSession with the TMDB-enriched title/artwork.
    updateMediaSessionMetadata()
}

internal fun PlayerRuntimeController.recomputeNextEpisode(resetVisibility: Boolean) {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv", "other")) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    if (normalizedType == "other") {
        val currentId = currentVideoId
        val idx = if (currentId != null) metaVideos.indexOfFirst { it.id == currentId } else -1
        val resolvedNext = if (idx >= 0 && idx < metaVideos.size - 1) metaVideos[idx + 1] else null
        nextEpisodeVideo = resolvedNext
        if (resolvedNext == null) {
            clearNextEpisodeAndCancelPostPlay()
            return
        }
        val nextInfo = NextEpisodeInfo(
            videoId = resolvedNext.id,
            season = resolvedNext.season ?: 1,
            episode = resolvedNext.episode ?: (idx + 2),
            title = resolvedNext.title,
            thumbnail = resolvedNext.thumbnail,
            overview = resolvedNext.overview,
            released = resolvedNext.released,
            hasAired = true,
            unairedMessage = null,
            isOtherType = true
        )
        applyRecomputedNextEpisode(nextInfo, resetVisibility)
        return
    }

    val season = currentSeason
    val episode = currentEpisode
    // Episode number is required; season may be null for absolute-numbered anime
    // (PlayerNextEpisodeRules.resolveNextEpisode supports currentSeason=null).
    if (episode == null) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val resolvedNext = PlayerNextEpisodeRules.resolveNextEpisode(
        videos = metaVideos,
        currentSeason = season,
        currentEpisode = episode
    )

    nextEpisodeVideo = resolvedNext
    if (resolvedNext == null) {
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val nextEpisodeNumber = resolvedNext.episode ?: return
    val hasAired = PlayerNextEpisodeRules.hasEpisodeAired(resolvedNext.released)
    val nextInfo = NextEpisodeInfo(
        videoId = resolvedNext.id,
        season = resolvedNext.season ?: season ?: 1,
        episode = nextEpisodeNumber,
        title = resolvedNext.title,
        thumbnail = resolvedNext.thumbnail,
        overview = resolvedNext.overview,
        released = resolvedNext.released,
        hasAired = hasAired,
        unairedMessage = if (hasAired) null
        else context.getString(R.string.next_episode_not_aired_yet)
    )
    applyRecomputedNextEpisode(nextInfo, resetVisibility)
}

private fun PlayerRuntimeController.clearNextEpisodeAndCancelPostPlay() {
    val mode = _uiState.value.postPlayMode
    if (mode != null) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update {
        it.copy(
            nextEpisode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
}

private fun PlayerRuntimeController.applyRecomputedNextEpisode(
    nextInfo: NextEpisodeInfo,
    resetVisibility: Boolean,
) {
    val previousState = _uiState.value
    val previousNextEpisode = previousState.nextEpisode
    val previousMode = previousState.postPlayMode
    // If a still-watching prompt is already showing for a different episode, tear it
    // down — the upcoming episode changed out from under it.
    if (previousMode is PostPlayMode.StillWatching &&
        previousNextEpisode != null &&
        previousNextEpisode.videoId != nextInfo.videoId
    ) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update { state ->
        val sameEpisode = state.nextEpisode?.videoId == nextInfo.videoId
        val shouldResetVisibility = resetVisibility || !sameEpisode
        val updatedMode = if (shouldResetVisibility) {
            null
        } else {
            state.postPlayMode?.copyWithNextEpisode(nextInfo)
        }
        state.copy(
            nextEpisode = nextInfo,
            postPlayMode = updatedMode,
            postPlayDismissedForCurrentEpisode =
                if (shouldResetVisibility && !state.postPlayDismissedForCurrentEpisode) false
                else state.postPlayDismissedForCurrentEpisode,
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayOverlayState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

internal fun PlayerRuntimeController.evaluatePostPlayOverlayVisibility(positionMs: Long, durationMs: Long) {
    if (!hasRenderedFirstFrame) return

    val state = _uiState.value
    if (state.nextEpisode == null || nextEpisodeVideo == null) {
        if (state.postPlayMode != null) {
            _uiState.update { it.copy(postPlayMode = null) }
        }
        return
    }
    if (state.postPlayMode != null || state.postPlayDismissedForCurrentEpisode) return

    val effectiveDuration = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = skipIntervals,
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )

    if (!shouldShow) return
    if (_uiState.value.postPlayDismissedForCurrentEpisode) return

    val shouldEnterStillWatching = shouldEnterStillWatchingPrompt(
        stillWatchingEnabled = stillWatchingEnabledSetting,
        autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
        nextEpisodeHasAired = state.nextEpisode.hasAired,
        consecutiveAutoPlayCount = consecutiveAutoPlayCount,
        threshold = stillWatchingEpisodeThresholdSetting,
    )

    if (shouldEnterStillWatching) {
        enterStillWatchingPromptMode()
    } else {
        _uiState.update {
            it.copy(
                postPlayMode = PostPlayMode.AutoPlay(nextEpisode = state.nextEpisode),
                // Up next owns the end card — drop Skip outro so the two never stack.
                activeSkipInterval = null,
            )
        }
        if (state.nextEpisode.hasAired && streamAutoPlayNextEpisodeEnabledSetting) {
            playNextEpisode()
        }
    }
}

internal fun PlayerRuntimeController.showStreamSourceIndicator(stream: Stream) {
    val chosenSource = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).trim()
    if (chosenSource.isBlank()) return

    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update {
        it.copy(
            showStreamSourceIndicator = true,
            streamSourceIndicatorText = "Source: $chosenSource"
        )
    }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.updateActiveSkipInterval(positionMs: Long) {
    if (skipIntervals.isEmpty()) {
        if (_uiState.value.activeSkipInterval != null) {
            _uiState.update { it.copy(activeSkipInterval = null) }
        }
        return
    }

    val positionSec = positionMs / 1000.0
    val matched = skipIntervals.find { interval ->
        positionSec >= interval.startTime && positionSec < (interval.endTime - 0.5)
    }
    // Intro/recap clear as soon as the window ends (matched == null below → fade out).
    // Outro/credits yield to Up next when that card is showing or about to show, so Skip
    // outro and the next-episode banner never fight for the same remote focus.
    val active = matched?.takeUnless { interval ->
        isOutroLikeSkipType(interval.type) && shouldPreferNextEpisodeOverOutroSkip(positionMs)
    }

    val currentActive = _uiState.value.activeSkipInterval

    if (active != null) {
        if (currentActive == null || active.type != currentActive.type || active.startTime != currentActive.startTime) {
            lastActiveSkipType = active.type
            _uiState.update { it.copy(activeSkipInterval = active, skipIntervalDismissed = false) }
        }
        // Auto-skip still waits for settings so empty defaults do not skip prematurely.
        if (!playerSettingsInitialized) return
        val segmentType = AutoSkipSegmentType.fromSkipIntervalType(active.type)
        val activeKey = active.autoSkipKey()
        if (segmentType != null &&
            segmentType in autoSkipSegmentTypes &&
            activeKey !in autoSkippedIntervalKeys
        ) {
            autoSkippedIntervalKeys.add(activeKey)
            skipInterval(active)
        }
    } else if (currentActive != null) {
        // Past intro/outro end (or suppressed for Up next) — clear so the button fades out.
        _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
    }
}

private fun isOutroLikeSkipType(type: String): Boolean {
    val normalized = type.trim().lowercase()
    if (normalized in PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES) return true
    return "outro" in normalized ||
        "ending" in normalized ||
        "credits" in normalized ||
        normalized == "ed" ||
        "mixed-ed" in normalized
}

private fun PlayerRuntimeController.shouldPreferNextEpisodeOverOutroSkip(positionMs: Long): Boolean {
    val state = _uiState.value
    if (state.postPlayMode != null) return true
    if (state.nextEpisode == null || nextEpisodeVideo == null) return false
    if (state.postPlayDismissedForCurrentEpisode) return false
    val durationMs = lastKnownDuration
    if (durationMs <= 0L) return false
    return PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting,
    )
}

private fun SkipInterval.autoSkipKey(): String = "$provider:$type:$startTime:$endTime"

internal fun PlayerRuntimeController.tryShowParentalGuide() {
    val state = _uiState.value
    if (!state.parentalGuideHasShown && state.parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        _uiState.update { it.copy(showParentalGuide = true, parentalGuideHasShown = true) }
    }
}

internal fun PlayerRuntimeController.fetchParentalGuide(id: String?, type: String?, season: Int?, episode: Int?) {
    if (!parentalGuideEnabled) return
    if (id.isNullOrBlank()) return

    val imdbId = id.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return

    scope.launch {
        val guide = parentalGuideRepository.getParentalGuide(imdbId) ?: return@launch

        val labels = mapOf(
            "nudity" to context.getString(R.string.parental_nudity),
            "violence" to context.getString(R.string.parental_violence),
            "profanity" to context.getString(R.string.parental_profanity),
            "alcohol" to context.getString(R.string.parental_alcohol),
            "frightening" to context.getString(R.string.parental_frightening)
        )
        val severityRank = mapOf("severe" to 0, "moderate" to 1, "mild" to 2)

        val entries = listOfNotNull(
            guide.nudity?.let { "nudity" to it },
            guide.violence?.let { "violence" to it },
            guide.profanity?.let { "profanity" to it },
            guide.alcohol?.let { "alcohol" to it },
            guide.frightening?.let { "frightening" to it }
        )

        val warnings = entries
            .sortedBy { severityRank[it.second.lowercase()] ?: 3 }
            .map { (key, severity) ->
                val localizedSeverity = when (severity.lowercase()) {
                    "severe" -> context.getString(R.string.parental_severity_severe)
                    "moderate" -> context.getString(R.string.parental_severity_moderate)
                    "mild" -> context.getString(R.string.parental_severity_mild)
                    else -> severity
                }
                ParentalWarning(label = labels[key] ?: key, severity = localizedSeverity)
            }
            .take(5)

        _uiState.update {
            it.copy(
                parentalWarnings = warnings,
                showParentalGuide = false,
                parentalGuideHasShown = false
            )
        }

        if (_uiState.value.isPlaying) {
            tryShowParentalGuide()
        }
    }
}