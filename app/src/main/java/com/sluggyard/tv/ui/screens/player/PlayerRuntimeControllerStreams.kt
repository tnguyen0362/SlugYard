package com.sluggyard.tv.ui.screens.player

import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.sluggyard.tv.core.debrid.DirectDebridPlayableResult
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.core.player.StreamAutoPlaySelector
import com.sluggyard.tv.core.player.StreamScoringEngine
import com.sluggyard.tv.core.streamresolution.PlaybackHandoff
import com.sluggyard.tv.data.local.PlayerSettings
import com.sluggyard.tv.data.local.StreamAutoPlayMode
import com.sluggyard.tv.data.local.StreamAutoPlaySource
import com.sluggyard.tv.domain.model.AddonStreams
import com.sluggyard.tv.domain.model.ProxyHeaders
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.domain.model.StreamBehaviorHints
import com.sluggyard.tv.domain.model.StreamDebridCacheState
import com.sluggyard.tv.domain.model.Video
import com.sluggyard.tv.domain.model.WatchProgress
import com.sluggyard.tv.domain.model.enabledAddons
import com.sluggyard.tv.ui.components.SourceChipItem
import com.sluggyard.tv.ui.components.SourceChipStatus
import com.sluggyard.tv.ui.app.regularSeasonsThenSpecials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Safety ceiling so next-episode auto-play search can never block the UI indefinitely. */
private const val NEXT_EPISODE_SEARCH_HARD_CAP_MS = 120_000L

/**
 * Incrementally matches badges for source streams in the background, processing
 * only addon groups that have not been badged yet. Emits a UI refresh once per
 * processed chunk so the side panel fills in progressively without stalling.
 */
internal fun PlayerRuntimeController.scheduleSourceBadgeApplication() {
    val current = _uiState.value
    val pendingAddons = current.sourceAvailableAddons.filter { it !in sourceBadgedAddonNames }
    if (pendingAddons.isEmpty()) return

    sourceBadgeJob = scope.launch(Dispatchers.Default) {
        val streamsToBadge = pendingAddons.flatMap { addon ->
            _uiState.value.sourceAllStreams.filter { stream -> stream.addonName == addon }
        }
        if (streamsToBadge.isEmpty()) {
            sourceBadgedAddonNames = sourceBadgedAddonNames + pendingAddons.toSet()
            return@launch
        }
        streamsToBadge.chunked(BADGE_CHUNK_SIZE).forEach { chunk ->
            val group = AddonStreams(addonName = "", addonLogo = null, streams = chunk)
            val badgedChunk = streamBadgePresentation.apply(listOf(group))
                .firstOrNull()?.streams ?: chunk
            val badgedByKey = badgedChunk.associateBy { it.badgeMergeKey() }
            _uiState.update { state ->
                val merged = state.sourceAllStreams.map { existing ->
                    badgedByKey[existing.badgeMergeKey()] ?: existing
                }
                val activeFilter = state.sourceSelectedAddonFilter
                state.copy(
                    sourceAllStreams = merged,
                    sourceFilteredStreams = merged.filterByAddon(activeFilter)
                )
            }
            sourceBadgedAddonNames = sourceBadgedAddonNames + chunk.map { it.addonName }.toSet()
        }
    }
}

/**
 * Kicks off badge matching for the entire episode stream list in one batch.
 * Replaces the previous badge job so stale work cannot race the latest fetch.
 */
internal fun PlayerRuntimeController.scheduleEpisodeBadgeApplication() {
    episodeBadgeJob?.cancel()
    episodeBadgeJob = scope.launch(Dispatchers.Default) {
        val streams = _uiState.value.episodeAllStreams
        if (streams.isEmpty()) return@launch
        val group = AddonStreams(addonName = "", addonLogo = null, streams = streams)
        val badged = streamBadgePresentation.apply(listOf(group)).flatMap { it.streams }
        if (badged == streams) return@launch
        _uiState.update { state ->
            val activeFilter = state.episodeSelectedAddonFilter
            state.copy(
                episodeAllStreams = badged,
                episodeFilteredStreams = badged.filterByAddon(activeFilter)
            )
        }
    }
}

private const val BADGE_CHUNK_SIZE = 5

/** Stable identity used to merge badge results back onto the live stream list. */
private fun Stream.badgeMergeKey(): String {
    infoHash?.lowercase()?.let { hash -> return "$addonName|$hash:${fileIdx ?: ""}" }
    val playable = url ?: clientResolve?.let { resolve ->
        resolve.stream?.raw?.filename ?: resolve.infoHash
    }
    if (playable != null) return "$addonName|$playable"
    return "$addonName|${name}:${title}:${description?.hashCode() ?: 0}"
}

internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }

    val preferredSeason = currentSeason ?: _uiState.value.episodesSelectedSeason
    if (_uiState.value.episodesAll.isNotEmpty() && preferredSeason != null) {
        selectEpisodesSeason(preferredSeason)
    } else {
        loadEpisodesIfNeeded()
    }
}

/** A stream needs debrid prep when it has no playable URL yet but is already cache-confirmed. */
private fun Stream.requiresDebridPrep(): Boolean =
    getStreamUrl().isNullOrBlank() &&
        (isDirectDebrid() || (needsLocalDebridResolve() && debridCacheStatus?.state == StreamDebridCacheState.CACHED))

internal fun PlayerRuntimeController.showSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false
        )
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.buildSourceRequestKey(
    type: String,
    videoId: String,
    season: Int?,
    episode: Int?
): String = "$type|$videoId|${season ?: -1}|${episode ?: -1}"

internal fun PlayerRuntimeController.loadSourceStreams(forceRefresh: Boolean) {
    val (type, vid, seasonArg, episodeArg) = resolveSourceRequestArgs() ?: run {
        _uiState.update {
            it.copy(
                isLoadingSourceStreams = false,
                sourceStreamsError = "Unable to identify the current title or episode",
            )
        }
        return
    }

    val requestKey = buildSourceRequestKey(type = type, videoId = vid, season = seasonArg, episode = episodeArg)
    val state = _uiState.value
    val hasPayload = state.sourceAllStreams.isNotEmpty() || state.sourceStreamsError != null

    // Fully completed cache hit — nothing to do.
    if (!forceRefresh && requestKey == sourceStreamsCacheRequestKey && hasPayload && sourceStreamsFetchCompleted) {
        return
    }
    // Same request already in flight — avoid a duplicate fetch.
    if (!forceRefresh && state.isLoadingSourceStreams && requestKey == sourceStreamsCacheRequestKey) {
        return
    }

    val targetChanged = requestKey != sourceStreamsCacheRequestKey
    val isResume = !forceRefresh && !targetChanged && hasPayload && !sourceStreamsFetchCompleted
    sourceStreamsScope?.cancel()
    sourceStreamsJob = null
    val freshScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    sourceStreamsScope = freshScope
    sourceChipErrorDismissJob?.cancel()
    sourceStreamsJob = freshScope.launch {
        sourceStreamsCacheRequestKey = requestKey
        sourceStreamsFetchCompleted = false
        if (forceRefresh || targetChanged) sourceBadgedAddonNames = emptySet()
        _uiState.update {
            it.copy(
                isLoadingSourceStreams = true,
                sourceStreamsError = null,
                sourceAllStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceAllStreams,
                sourceSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.sourceSelectedAddonFilter,
                sourceFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceFilteredStreams,
                sourceAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.sourceAvailableAddons,
                sourceChips = if (forceRefresh || targetChanged) emptyList() else it.sourceChips
            )
        }

        val installed = addonRepository.getInstalledAddons().first().enabledAddons()
        val installedOrder = installed.map { it.displayName }
        val installedNames = installedOrder.toSet()
        var debridPrepLaunched = false

        // On resume keep existing chip statuses; otherwise reset chips to LOADING.
        if (!isResume) {
            updateSourceChipsForFetchStart(type, vid, installed)
        }

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = vid,
            season = seasonArg,
            episode = episodeArg
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> handleSourceStreamsSuccess(
                    payload = result.data,
                    installedOrder = installedOrder,
                    installedNames = installedNames,
                    season = seasonArg,
                    episode = episodeArg,
                    isResume = isResume,
                    debridPrepLaunched = debridPrepLaunched,
                    onDebridPrepLaunched = { debridPrepLaunched = true }
                )
                is NetworkResult.Error -> _uiState.update {
                    it.copy(isLoadingSourceStreams = false, sourceStreamsError = result.message)
                }
                NetworkResult.Loading -> _uiState.update { it.copy(isLoadingSourceStreams = true) }
            }
        }
        sourceStreamsFetchCompleted = true
        markRemainingSourceChipsAsError()
    }
}

/** Resolves the (type, videoId, season, episode) tuple for a source stream request. */
private fun PlayerRuntimeController.resolveSourceRequestArgs(): SourceRequestArgs? {
    val isSeries = contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null
    return if (isSeries) {
        val type = contentType ?: return null
        val vid = currentVideoId ?: contentId ?: return null
        SourceRequestArgs(type, vid, currentSeason, currentEpisode)
    } else {
        val type = contentType ?: "movie"
        val vid = contentId ?: return null
        SourceRequestArgs(type, vid, null, null)
    }
}

private data class SourceRequestArgs(
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?
)

private fun PlayerRuntimeController.handleSourceStreamsSuccess(
    payload: List<AddonStreams>,
    installedOrder: List<String>,
    installedNames: Set<String>,
    season: Int?,
    episode: Int?,
    isResume: Boolean,
    debridPrepLaunched: Boolean,
    onDebridPrepLaunched: () -> Unit
) {
    val ordered = StreamAutoPlaySelector.orderAddonStreams(payload, installedOrder)
    val freshStreams = ordered.flatMap { it.streams }
    val freshAddons = ordered.map { it.addonName }
    _uiState.update { state ->
        val mergedStreams = if (isResume && state.sourceAllStreams.isNotEmpty()) {
            coalesceStreams(state.sourceAllStreams, freshStreams)
        } else {
            freshStreams
        }
        // Preserve badges already computed by prior badge jobs.
        val previouslyBadged = state.sourceAllStreams
            .filter { it.badges.isNotEmpty() }
            .associateBy { it.badgeMergeKey() }
        val withBadges = if (previouslyBadged.isEmpty()) {
            mergedStreams
        } else {
            mergedStreams.map { stream ->
                val prior = previouslyBadged[stream.badgeMergeKey()]
                if (prior != null && stream.badges.isEmpty()) stream.copy(badges = prior.badges) else stream
            }
        }
        val mergedAddons = if (isResume && state.sourceAvailableAddons.isNotEmpty()) {
            (state.sourceAvailableAddons + freshAddons).distinct()
        } else {
            freshAddons
        }
        val activeFilter = state.sourceSelectedAddonFilter?.takeIf { it in mergedAddons }
        val filtered = withBadges.filterByAddon(activeFilter)
        state.copy(
            isLoadingSourceStreams = false,
            sourceAllStreams = withBadges,
            sourceSelectedAddonFilter = activeFilter,
            sourceFilteredStreams = filtered,
            sourceAvailableAddons = mergedAddons,
            sourceChips = mergeSourceChipStatuses(
                existing = state.sourceChips,
                succeededNames = ordered.map { it.addonName }
            ),
            sourceStreamsError = null
        )
    }
    launchSourceDebridPreparationIfNeeded(
        launched = debridPrepLaunched,
        streams = freshStreams,
        season = season,
        episode = episode,
        installedAddonNames = installedNames,
        markLaunched = onDebridPrepLaunched
    )
    scheduleSourceBadgeApplication()
}

/**
 * Combines cached and freshly fetched streams, letting the newer entry win for
 * each dedupe key (matched by addon + url/infoHash).
 */
private fun coalesceStreams(cached: List<Stream>, fresh: List<Stream>): List<Stream> {
    val byKey = LinkedHashMap<String, Stream>()
    cached.forEach { byKey[it.dedupeKey()] = it }
    fresh.forEach { byKey[it.dedupeKey()] = it }
    return byKey.values.toList()
}

private fun Stream.dedupeKey(): String =
    infoHash?.lowercase()?.let { hash -> "$addonName|$hash:${fileIdx ?: ""}" }
        ?: "$addonName|${getStreamUrl() ?: externalUrl ?: ytId ?: "${name}:${title}"}"

private fun PlayerRuntimeController.launchSourceDebridPreparationIfNeeded(
    launched: Boolean,
    streams: List<Stream>,
    season: Int?,
    episode: Int?,
    installedAddonNames: Set<String>,
    markLaunched: () -> Unit
) {
    if (launched || streams.none { it.requiresDebridPrep() }) return
    markLaunched()
    scope.launch {
        val settings = playerSettingsDataStore.playerSettings.first()
        directDebridStreamPreparer.prepare(
            streams = streams,
            season = season,
            episode = episode,
            playerSettings = settings,
            installedAddonNames = installedAddonNames,
            preferredBingeGroup = currentStreamBingeGroup,
            contentContext = StreamScoringEngine.ContentContext(
                contentType = contentType,
                genres = contentGenres,
                contentLanguage = contentLanguage,
                title = title,
                season = season,
                episode = episode
            )
        ) { original, prepared -> replacePreparedSourceStream(original, prepared) }
    }
}

private fun PlayerRuntimeController.replacePreparedSourceStream(original: Stream, prepared: Stream) {
    _uiState.update { state ->
        val updated = replacePreparedFlatStreams(
            streams = state.sourceAllStreams,
            original = original,
            prepared = prepared
        )
        if (updated == state.sourceAllStreams) {
            state
        } else {
            val activeFilter = state.sourceSelectedAddonFilter
            state.copy(
                sourceAllStreams = updated,
                sourceFilteredStreams = updated.filterByAddon(activeFilter)
            )
        }
    }
}

internal fun PlayerRuntimeController.dismissSourcesPanel() {
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    sourceChipErrorDismissJob?.cancel()
    _uiState.update {
        it.copy(showSourcesPanel = false, isLoadingSourceStreams = false)
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.filterSourceStreamsByAddon(addonName: String?) {
    val all = _uiState.value.sourceAllStreams
    val filtered = all.filterByAddon(addonName)
    _uiState.update {
        it.copy(sourceSelectedAddonFilter = addonName, sourceFilteredStreams = filtered)
    }
}

private suspend fun PlayerRuntimeController.updateSourceChipsForFetchStart(
    type: String,
    videoId: String,
    installedAddons: List<com.sluggyard.tv.domain.model.Addon>
) {
    val names = installedAddons
        .filter { it.matchesStreamResource(type, videoId) }
        .map { it.displayName }
        .distinct()
    _uiState.update {
        it.copy(sourceChips = names.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) })
    }
}

/** Merges newly succeeded addon names into the existing chip list, marking matches SUCCESS. */
private fun PlayerRuntimeController.mergeSourceChipStatuses(
    existing: List<SourceChipItem>,
    succeededNames: List<String>
): List<SourceChipItem> {
    if (succeededNames.isEmpty()) return existing
    if (existing.isEmpty()) {
        return succeededNames.distinct().map { SourceChipItem(it, SourceChipStatus.SUCCESS) }
    }
    val successSet = succeededNames.toSet()
    val merged = existing.map { chip ->
        if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
    }.toMutableList()
    val known = merged.map { it.name }.toSet()
    succeededNames.forEach { name -> if (name !in known) merged += SourceChipItem(name, SourceChipStatus.SUCCESS) }
    return merged
}

/** Flips any still-LOADING chips to ERROR once the fetch completes, then auto-dismisses them. */
private fun PlayerRuntimeController.markRemainingSourceChipsAsError() {
    var markedAny = false
    _uiState.update { state ->
        if (!state.sourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
        markedAny = true
        state.copy(
            sourceChips = state.sourceChips.map { chip ->
                if (chip.status == SourceChipStatus.LOADING) chip.copy(status = SourceChipStatus.ERROR) else chip
            }
        )
    }
    if (!markedAny) return
    sourceChipErrorDismissJob?.cancel()
    sourceChipErrorDismissJob = scope.launch {
        delay(SOURCE_CHIP_ERROR_DISMISS_MS)
        _uiState.update { state ->
            state.copy(sourceChips = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR })
        }
    }
}

private const val SOURCE_CHIP_ERROR_DISMISS_MS = 1_600L

/** True when the addon advertises a `stream` resource compatible with [type]/[videoId]. */
private fun com.sluggyard.tv.domain.model.Addon.matchesStreamResource(type: String, videoId: String): Boolean {
    return resources.any { resource ->
        if (resource.name != "stream") return@any false
        if (resource.types.isNotEmpty() && resource.types.none { it.equals(type, ignoreCase = true) }) return@any false
        val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
            ?: idPrefixes.takeIf { it.isNotEmpty() }
        prefixes == null || prefixes.any { videoId.startsWith(it) }
    }
}

/** Captures URL/headers/metadata for the currently selected HTTP stream. */
private fun PlayerRuntimeController.applySelectedStreamState(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    val (cleanUrl, mergedHeaders) = PlayerMediaSourceFactory.extractUserInfoAuth(url, headers)
    currentStreamUrl = cleanUrl
    currentHeaders = mergedHeaders
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    currentStreamResponseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty()
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = cleanUrl,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    applyStreamMetadata(stream)
}

/**
 * Applies stream metadata common to HTTP and torrent paths. Binge-group, addon
 * info and video hints are always populated so next-episode binge matching works
 * regardless of how the stream was selected.
 */
private fun PlayerRuntimeController.applyStreamMetadata(stream: Stream) {
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentAddonName = stream.addonName
    currentAddonLogo = stream.addonLogo
    currentStreamDescription = stream.description
    currentVideoCodec = null
    currentVideoWidth = null
    currentVideoHeight = null
    currentVideoBitrate = null

    // Persist the binge group for this content so later episode plays (from
    // CW, Details, or next-episode) can reuse the same source group.
    val bingeGroup = stream.behaviorHints?.bingeGroup
    val cid = contentId
    if (bingeGroup != null && cid != null) {
        scope.launch(NonCancellable) { bingeGroupCacheDataStore.save(cid, bingeGroup) }
    }
}

private fun PlayerRuntimeController.persistSelectedStreamForReuse(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    // Always remember the last stream fingerprint so Continue Watching / Play can prefer
    // it. Direct URL reuse age still respects streamReuseLastLink* when reading.
    val key = streamCacheKey ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)
        ?.takeIf { it.isNotBlank() } ?: title
    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = url,
            streamName = streamName,
            headers = headers,
            filename = currentFilename,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year
        )
    }
}

private fun PlayerRuntimeController.persistTorrentStreamForReuse(stream: Stream) {
    val key = streamCacheKey ?: return
    val infoHash = stream.getEffectiveInfoHash() ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)
        ?.takeIf { it.isNotBlank() } ?: title
    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = "",
            streamName = streamName,
            headers = emptyMap(),
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            infoHash = infoHash,
            fileIdx = stream.getEffectiveFileIdx(),
            sources = stream.sources,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year
        )
    }
}

/**
 * Hands an external (browser-only) stream off to the system browser. Returns
 * true when the stream was handled (caller should not continue processing).
 */
private fun PlayerRuntimeController.openExternalStreamInBrowser(
    stream: Stream,
    fromEpisodePanel: Boolean
): Boolean {
    if (!stream.isExternal()) return false
    val externalUrl = stream.getStreamUrl()
    if (externalUrl.isNullOrBlank()) {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = context.getString(com.sluggyard.tv.R.string.player_stream_error_invalid_external_url))
            } else {
                it.copy(sourceStreamsError = context.getString(com.sluggyard.tv.R.string.player_stream_error_invalid_external_url))
            }
        }
        return true
    }

    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(browserIntent) }
        .onSuccess {
            _uiState.update {
                if (fromEpisodePanel) {
                    it.copy(
                        showEpisodesPanel = false,
                        showEpisodeStreams = false,
                        isLoadingEpisodeStreams = false,
                        episodeStreamsError = null
                    )
                } else {
                    it.copy(
                        showSourcesPanel = false,
                        isLoadingSourceStreams = false,
                        sourceStreamsError = null
                    )
                }
            }
        }
        .onFailure { error ->
            _uiState.update {
                if (fromEpisodePanel) {
                    it.copy(episodeStreamsError = error.message ?: context.getString(com.sluggyard.tv.R.string.player_stream_error_open_external_link_failed))
                } else {
                    it.copy(sourceStreamsError = error.message ?: context.getString(com.sluggyard.tv.R.string.player_stream_error_open_external_link_failed))
                }
            }
        }
    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(stream: Stream, resumePositionMs: Long? = null) {
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = false)) return

    if (stream.isTorrent()) {
        resolveDebridThenSwitchSource(stream, currentSeason, currentEpisode)
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        if (stream.isDirectDebrid()) {
            resolveDebridThenSwitchSource(stream, currentSeason, currentEpisode)
            return
        }
        _uiState.update { it.copy(sourceStreamsError = context.getString(com.sluggyard.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    if (resumePositionMs != null) {
        pendingResumeProgress = if (resumePositionMs > 0L) {
            WatchProgress(
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
                position = resumePositionMs,
                duration = 0L,
                lastWatched = System.currentTimeMillis(),
            )
        } else null
    }
    if (resumePositionMs == null) {
        // The progress read must finish before beginHttpSourceStream() initializes the player;
        // otherwise STATE_READY can fire before pendingResumeProgress is populated.
        scope.launch {
            loadSavedProgressSuspend(currentSeason, currentEpisode)
            beginHttpSourceStream(stream, url)
        }
    } else {
        beginHttpSourceStream(stream, url)
    }
}

/**
 * Narrow bridge for the Rewrite source sheet. Rewrite performs discovery, ranking, and debrid
 * resolution itself; the retained player only receives the already-resolved URL at this boundary.
 */
internal fun PlayerRuntimeController.switchToPlaybackSource(handoff: PlaybackHandoff) {
    val previousSeason = currentSeason
    val previousEpisode = currentEpisode
    val previousVideoId = currentVideoId
    contentId = handoff.contentId
    contentType = handoff.contentType
    currentVideoId = handoff.contentId
    currentSeason = handoff.season
    currentEpisode = handoff.episode
    currentEpisodeTitle = handoff.episodeTitle
    if (!handoff.parentId.isNullOrBlank()) parentId = handoff.parentId
    if (!handoff.parentType.isNullOrBlank()) parentType = handoff.parentType
    _uiState.update {
        it.copy(
            contentType = handoff.contentType,
            currentVideoId = handoff.contentId,
            currentSeason = handoff.season,
            currentEpisode = handoff.episode,
            currentEpisodeTitle = handoff.episodeTitle,
        )
    }
    val episodeIdentityChanged =
        previousSeason != handoff.season ||
            previousEpisode != handoff.episode ||
            previousVideoId != handoff.contentId
    if (episodeIdentityChanged) {
        // beginHttpSourceStream does not refresh episode-scoped metadata; mirror the legacy
        // switchToEpisodeStreamCommon path so Up Next, pause description, and IntroDB/AniSkip
        // all rebind to the newly selected episode.
        recomputeNextEpisode(resetVisibility = true)
        updateEpisodeDescription()
        if (metaVideos.isEmpty()) {
            fetchMetaDetailsForCurrentContent()
        }
        playbackStartedForParentalGuide = false
        skipIntervals = emptyList()
        skipIntroFetchedKey = null
        skipIntroInFlightKey = null
        lastActiveSkipType = null
        autoSkippedIntervalKeys.clear()
        _uiState.update {
            it.copy(
                activeSkipInterval = null,
                skipIntervalDismissed = false,
                parentalWarnings = emptyList(),
                showParentalGuide = false,
                parentalGuideHasShown = false,
            )
        }
        fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
        // Prefer parent/series id for IntroDB/IMDb; episode id still works via normalizeSkipLookupId.
        val skipId = parentId?.takeIf { it.isNotBlank() }
            ?: currentVideoId
            ?: contentId
        fetchSkipIntervals(skipId, currentSeason, currentEpisode)
    }
    val source = handoff.source
    val isTorrent = source.infoHash != null && !source.url.startsWith("http", ignoreCase = true)
    switchToSourceStream(
        Stream(
            name = source.streamName ?: source.sourceId,
            title = source.streamName ?: source.sourceId,
            description = source.streamDescription,
            url = source.url.takeUnless { isTorrent },
            ytId = null,
            infoHash = source.infoHash.takeIf { isTorrent },
            fileIdx = source.fileIndex.takeIf { isTorrent },
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = source.bingeGroup,
                countryWhitelist = null,
                proxyHeaders = source.requestHeaders.takeIf { it.isNotEmpty() }?.let {
                    ProxyHeaders(request = it, response = null)
                },
                videoHash = source.videoHash,
                videoSize = source.videoSizeBytes,
                filename = source.filename,
            ),
            addonName = source.addonName ?: "Rewrite",
            addonLogo = null,
            sources = source.trackers.map { "tracker:$it" }.takeIf { it.isNotEmpty() },
        ),
        resumePositionMs = handoff.resumePositionMs,
    )
}

/** Resolves a torrent/direct-debrid source stream, then either re-enters the HTTP path or the torrent path. */
private fun PlayerRuntimeController.resolveDebridThenSwitchSource(stream: Stream, season: Int?, episode: Int?) {
    debridResolveJob?.cancel()
    _uiState.update { it.copy(isLoadingSourceStreams = true, sourceStreamsError = null) }
    debridResolveJob = scope.launch {
        val resolved = resolveDirectDebridStreamIfNeeded(stream, season, episode)
        debridResolveJob = null
        when {
            resolved != null && !resolved.getStreamUrl().isNullOrBlank() -> switchToSourceStream(resolved)
            resolved != null -> switchToTorrentSourceStream(resolved)
            else -> _uiState.update {
                it.copy(
                    isLoadingSourceStreams = false,
                    sourceStreamsError = context.getString(com.sluggyard.tv.R.string.player_stream_error_invalid_url)
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.beginHttpSourceStream(stream: Stream, url: String) {
    // Stop any active torrent before switching to HTTP playback.
    stopTorrentStream()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(stream.behaviorHints?.proxyHeaders?.request)
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applySelectedStreamState(stream = stream, url = url, headers = newHeaders)
    persistSelectedStreamForReuse(stream = stream, url = url, headers = newHeaders)

    // Reset per-stream error/track flags for the new selection.
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    lastSavedPosition = 0L
    _exoPlayer?.stop()
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = url,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = false
        )
    }
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)

    val player = _exoPlayer
    if (player != null) {
        scope.launch {
            try {
                val settings = playerSettingsDataStore.playerSettings.first()
                runAfrPreflightIfEnabled(
                    url = url,
                    headers = newHeaders,
                    frameRateMatchingMode = settings.frameRateMatchingMode,
                    resolutionMatchingEnabled = settings.resolutionMatchingEnabled
                )
                player.setMediaSource(
                    mediaSourceFactory.createMediaSource(
                        context = context,
                        url = url,
                        headers = newHeaders,
                        filename = currentFilename,
                        responseHeaders = currentStreamResponseHeaders,
                        mimeTypeOverride = currentStreamMimeType,
                        audioDelayUsProvider = audioDelayUs::get
                    )
                )
                player.playWhenReady = true
                player.prepare()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(com.sluggyard.tv.R.string.player_error_play_stream_failed)) }
            }
        }
    } else {
        initializePlayer(url, newHeaders)
    }
}

internal fun PlayerRuntimeController.dismissEpisodesPanel() {
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    _uiState.update {
        it.copy(
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectEpisodesSeason(season: Int) {
    val all = _uiState.value.episodesAll
    if (all.isEmpty()) return
    val seasons = _uiState.value.episodesAvailableSeasons
    if (seasons.isNotEmpty() && season !in seasons) return

    val episodesForSeason = all
        .filter { (it.season ?: -1) == season }
        .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

    _uiState.update {
        it.copy(episodesSelectedSeason = season, episodes = episodesForSeason)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.switchToTorrentSourceStream(stream: Stream) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    stopTorrentStream()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    flushPlaybackSnapshotForSwitchOrExit()
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)
    hasRetriedCurrentStreamAfter416 = false
    errorRetryCount = 0
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    lastSavedPosition = 0L
    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = true
        )
    }
    applyStreamMetadata(stream)
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
    persistTorrentStreamForReuse(stream)
}

private fun PlayerRuntimeController.switchToTorrentEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video?,
    isAutoPlay: Boolean
) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay
    )
    stopTorrentStream()
    switchToEpisodeStreamCommon(stream, forcedTargetVideo)
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
    persistTorrentStreamForReuse(stream)
}

internal fun PlayerRuntimeController.loadEpisodesIfNeeded() {
    val type = contentType
    val id = contentId
    if (type.isNullOrBlank() || id.isNullOrBlank()) {
        _uiState.update { it.copy(isLoadingEpisodes = false, episodesError = "Unable to identify this series") }
        return
    }
    if (type !in listOf("series", "tv")) {
        _uiState.update { it.copy(isLoadingEpisodes = false, episodesError = "Episodes are unavailable for this title") }
        return
    }
    if (_uiState.value.episodesAll.isNotEmpty() || _uiState.value.isLoadingEpisodes) return

    scope.launch {
        _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }
        val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
            .first { it !is NetworkResult.Loading }
        when (result) {
            is NetworkResult.Success -> {
                val allEpisodes = result.data.videos.sortedWith(
                    compareBy<Video> { it.season ?: Int.MAX_VALUE }
                        .thenBy { it.episode ?: Int.MAX_VALUE }
                        .thenBy { it.title }
                )
                applyMetaDetails(result.data)

                val seasons = allEpisodes.mapNotNull { it.season }.distinct()
                    .regularSeasonsThenSpecials { it }
                val preferredSeason = when {
                    currentSeason != null && seasons.contains(currentSeason) -> currentSeason
                    initialSeason != null && seasons.contains(initialSeason) -> initialSeason
                    else -> seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
                }
                val selectedSeason = preferredSeason ?: 1
                val episodesForSeason = allEpisodes
                    .filter { (it.season ?: -1) == selectedSeason }
                    .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

                _uiState.update {
                    it.copy(
                        isLoadingEpisodes = false,
                        episodesAll = allEpisodes,
                        episodesAvailableSeasons = seasons,
                        episodesSelectedSeason = selectedSeason,
                        episodes = episodesForSeason,
                        episodesError = null
                    )
                }
            }
            is NetworkResult.Error -> _uiState.update {
                it.copy(isLoadingEpisodes = false, episodesError = result.message)
            }
            NetworkResult.Loading -> Unit
        }
    }
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video) {
    loadStreamsForEpisode(video = video, forceRefresh = false)
}

internal fun PlayerRuntimeController.buildEpisodeRequestKey(type: String, video: Video): String {
    return "$type|${video.id}|${video.season ?: -1}|${video.episode ?: -1}"
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(
    video: Video,
    forceRefresh: Boolean,
    showPicker: Boolean = true
) {
    val type = contentType
    if (type.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.sluggyard.tv.R.string.player_stream_error_missing_content_type)) }
        return
    }

    val requestKey = buildEpisodeRequestKey(type = type, video = video)
    val state = _uiState.value
    val hasPayload = state.episodeAllStreams.isNotEmpty() || state.episodeStreamsError != null
    if (!forceRefresh && requestKey == episodeStreamsCacheRequestKey && hasPayload) {
        _uiState.update {
            it.copy(
                showEpisodesPanel = showPicker,
                showEpisodeStreams = showPicker,
                showLoadingOverlay = if (showPicker) it.showLoadingOverlay else it.loadingOverlayEnabled,
                loadingMessage = if (showPicker) it.loadingMessage else context.getString(com.sluggyard.tv.R.string.player_loading_preparing),
                isLoadingEpisodeStreams = false,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }
        return
    }

    val targetChanged = requestKey != episodeStreamsCacheRequestKey
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    val freshScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    episodeStreamsScope = freshScope
    episodeStreamsJob = freshScope.launch {
        episodeStreamsCacheRequestKey = requestKey
        val previousAddonFilter = _uiState.value.episodeSelectedAddonFilter
        _uiState.update {
            it.copy(
                showEpisodesPanel = showPicker,
                showEpisodeStreams = showPicker,
                showLoadingOverlay = if (showPicker) it.showLoadingOverlay else it.loadingOverlayEnabled,
                loadingMessage = if (showPicker) it.loadingMessage else context.getString(com.sluggyard.tv.R.string.player_loading_preparing),
                isLoadingEpisodeStreams = true,
                episodeStreamsError = null,
                episodeAllStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeAllStreams,
                episodeSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.episodeSelectedAddonFilter,
                episodeFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeFilteredStreams,
                episodeAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.episodeAvailableAddons,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }

        val installed = addonRepository.getInstalledAddons().first().enabledAddons()
        val installedOrder = installed.map { it.displayName }
        val installedNames = installedOrder.toSet()
        val settings = playerSettingsDataStore.playerSettings.first()
        var debridPrepLaunched = false
        var lastSuccessData: List<AddonStreams>? = null
        var autoPlayStarted = false

        fun pickAutoPlayCandidate(groups: List<AddonStreams>): Stream? {
            val ordered = StreamAutoPlaySelector.orderAddonStreams(groups, installedOrder).flatMap { it.streams }
            return StreamAutoPlaySelector.selectAutoPlayStream(
                streams = ordered,
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                source = settings.streamAutoPlaySource,
                installedAddonNames = installedNames,
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                selectedPlugins = settings.streamAutoPlaySelectedPlugins,
                preferredBingeGroup = currentStreamBingeGroup,
                preferBingeGroupInSelection = currentStreamBingeGroup != null,
                contentContext = StreamScoringEngine.ContentContext(
                    contentType = contentType,
                    genres = metaGenres?.joinToString(","),
                    contentLanguage = contentLanguage,
                    title = title,
                    season = video.season,
                    episode = video.episode
                )
            )
        }

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = video.id,
            season = video.season,
            episode = video.episode
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    lastSuccessData = result.data
                    val ordered = StreamAutoPlaySelector.orderAddonStreams(result.data, installedOrder)
                    val allStreams = ordered.flatMap { it.streams }
                    val availableAddons = ordered.map { it.addonName }
                    val activeFilter = previousAddonFilter?.takeIf { it in availableAddons }
                    val filtered = allStreams.filterByAddon(activeFilter)
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeAllStreams = allStreams,
                            episodeSelectedAddonFilter = activeFilter,
                            episodeFilteredStreams = filtered,
                            episodeAvailableAddons = availableAddons,
                            episodeStreamsError = null
                        )
                    }
                    launchEpisodeDebridPreparationIfNeeded(
                        launched = debridPrepLaunched,
                        streams = allStreams,
                        season = video.season,
                        episode = video.episode,
                        installedAddonNames = installedNames
                    ) { debridPrepLaunched = true }
                    scheduleEpisodeBadgeApplication()

                    // A direct-debrid or cache-confirmed candidate is safe to
                    // play immediately — don't make it wait behind a dead
                    // addon's HTTP timeout. Non-validated torrents still wait
                    // for the final scorer below.
                    if (!autoPlayStarted) {
                        val candidate = pickAutoPlayCandidate(result.data)
                        val validated = candidate?.isDirectDebrid() == true ||
                            candidate?.debridCacheStatus?.state == StreamDebridCacheState.CACHED
                        if (candidate != null && validated) {
                            autoPlayStarted = true
                            switchToEpisodeStream(
                                stream = candidate,
                                forcedTargetVideo = video,
                                isAutoPlay = true
                            )
                        }
                    }
                }
                is NetworkResult.Error -> _uiState.update {
                    it.copy(isLoadingEpisodeStreams = false, episodeStreamsError = result.message)
                }
                NetworkResult.Loading -> _uiState.update { it.copy(isLoadingEpisodeStreams = true) }
            }
        }

        // If no validated candidate arrived incrementally, fall back to the full
        // final-scoring pass that needs all providers.
        if (!autoPlayStarted) {
            val selected = lastSuccessData?.let(::pickAutoPlayCandidate)
            if (selected != null) {
                switchToEpisodeStream(
                    stream = selected,
                    forcedTargetVideo = video,
                    isAutoPlay = true
                )
            } else if (!showPicker) {
                _uiState.update { it.copy(showLoadingOverlay = false) }
                showEpisodeStreamPicker(video = video, forceRefresh = false)
            }
        }
    }
}

private fun PlayerRuntimeController.launchEpisodeDebridPreparationIfNeeded(
    launched: Boolean,
    streams: List<Stream>,
    season: Int?,
    episode: Int?,
    installedAddonNames: Set<String>,
    markLaunched: () -> Unit
) {
    if (launched || streams.none { it.requiresDebridPrep() }) return
    markLaunched()
    scope.launch {
        val settings = playerSettingsDataStore.playerSettings.first()
        directDebridStreamPreparer.prepare(
            streams = streams,
            season = season,
            episode = episode,
            playerSettings = settings,
            installedAddonNames = installedAddonNames,
            preferredBingeGroup = currentStreamBingeGroup,
            contentContext = StreamScoringEngine.ContentContext(
                contentType = contentType,
                genres = contentGenres,
                contentLanguage = contentLanguage,
                title = title,
                season = season,
                episode = episode
            )
        ) { original, prepared -> replacePreparedEpisodeStream(original, prepared) }
    }
}

private fun PlayerRuntimeController.replacePreparedEpisodeStream(original: Stream, prepared: Stream) {
    _uiState.update { state ->
        val updated = replacePreparedFlatStreams(
            streams = state.episodeAllStreams,
            original = original,
            prepared = prepared
        )
        if (updated == state.episodeAllStreams) {
            state
        } else {
            val activeFilter = state.episodeSelectedAddonFilter
            state.copy(
                episodeAllStreams = updated,
                episodeFilteredStreams = updated.filterByAddon(activeFilter)
            )
        }
    }
}

private fun PlayerRuntimeController.replacePreparedFlatStreams(
    streams: List<Stream>,
    original: Stream,
    prepared: Stream
): List<Stream> {
    if (streams.isEmpty()) return streams
    return directDebridStreamPreparer.replacePreparedStream(
        groups = listOf(AddonStreams(addonName = "", addonLogo = null, streams = streams)),
        original = original,
        prepared = prepared
    ).firstOrNull()?.streams ?: streams
}

private fun List<Stream>.filterByAddon(addonName: String?): List<Stream> =
    if (addonName == null) this else filter { it.addonName == addonName }

internal fun PlayerRuntimeController.reloadEpisodeStreams() {
    val state = _uiState.value
    val targetId = state.episodeStreamsForVideoId
    val targetVideo = sequenceOf(
        state.episodes.firstOrNull { it.id == targetId },
        state.episodesAll.firstOrNull { it.id == targetId },
        state.episodes.firstOrNull { it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode },
        state.episodesAll.firstOrNull { it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode }
    ).firstOrNull { it != null }

    if (targetVideo != null) {
        loadStreamsForEpisode(video = targetVideo, forceRefresh = true)
    }
}

internal fun PlayerRuntimeController.switchToEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video? = null,
    isAutoPlay: Boolean = false
) {
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = true)) return

    if (stream.isTorrent()) {
        val season = forcedTargetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
        val episode = forcedTargetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
        resolveDebridThenSwitchEpisode(stream, forcedTargetVideo, isAutoPlay, season, episode)
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        if (stream.isDirectDebrid()) {
            val season = forcedTargetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
            val episode = forcedTargetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
            resolveDebridThenSwitchEpisode(stream, forcedTargetVideo, isAutoPlay, season, episode)
            return
        }
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.sluggyard.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    beginHttpEpisodeStream(stream, url, forcedTargetVideo, isAutoPlay)
}

/** Resolves a torrent/direct-debrid episode stream, then routes to HTTP or torrent episode switching. */
private fun PlayerRuntimeController.resolveDebridThenSwitchEpisode(
    stream: Stream,
    forcedTargetVideo: Video?,
    isAutoPlay: Boolean,
    season: Int?,
    episode: Int?
) {
    debridResolveJob?.cancel()
    _uiState.update { it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null) }
    debridResolveJob = scope.launch {
        val resolved = resolveDirectDebridStreamIfNeeded(stream, season, episode)
        when {
            resolved != null && !resolved.getStreamUrl().isNullOrBlank() -> {
                debridResolveJob = null
                switchToEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            }
            resolved != null -> {
                debridResolveJob = null
                switchToTorrentEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            }
            else -> {
                debridResolveJob = null
                _uiState.update {
                    it.copy(
                        isLoadingEpisodeStreams = false,
                        episodeStreamsError = context.getString(com.sluggyard.tv.R.string.player_stream_error_invalid_url)
                    )
                }
            }
        }
    }
}

private fun PlayerRuntimeController.beginHttpEpisodeStream(
    stream: Stream,
    url: String,
    forcedTargetVideo: Video?,
    isAutoPlay: Boolean
) {
    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay
    )

    // Stop any active torrent before switching to HTTP playback.
    stopTorrentStream()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    flushPlaybackSnapshotForSwitchOrExit()

    // Pause the old stream immediately so its audio/video does not bleed into
    // the new episode while it is being prepared.
    _exoPlayer?.stop()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(stream.behaviorHints?.proxyHeaders?.request)
    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    currentStreamUrl = url
    currentHeaders = newHeaders
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentFilename = stream.behaviorHints?.filename
        ?: url.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()

    applySelectedStreamState(stream = stream, url = url, headers = newHeaders)
    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    persistSelectedStreamForReuse(stream = stream, url = url, headers = newHeaders)
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
    lastSavedPosition = 0L

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = url,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = false,
            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,
            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true,
            playbackEnded = false
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)
    updateEpisodeDescription()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    skipIntroInFlightKey = null
    lastActiveSkipType = null
    autoSkippedIntervalKeys.clear()

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    // videoId carries the episode-specific (mal:/kitsu:) id AniSkip needs; contentId is the parent.
    fetchSkipIntervals(currentVideoId ?: contentId, currentSeason, currentEpisode)

    queuePlaybackRawEventLine(
        "LINK_SELECTED: source=in_player_source host=${url.traceHost()} " +
            "streamName=${stream.name} addon=${stream.addonName} " +
            "contentId=${contentId ?: "n/a"} videoId=${currentVideoId ?: "n/a"} " +
            "S${currentSeason ?: "-"}E${currentEpisode ?: "-"} torrent=false"
    )
    preparePlaybackBeforeStart(url = url, headers = newHeaders, loadSavedProgress = true)
}

private fun String.traceHost(): String =
    runCatching {
        Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")

/**
 * Shared episode stream setup used by both torrent and HTTP episode switching.
 * Resets per-stream state, updates UI, and re-fetches parental/skip metadata.
 */
private fun PlayerRuntimeController.switchToEpisodeStreamCommon(
    stream: Stream,
    forcedTargetVideo: Video? = null
) {
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    flushPlaybackSnapshotForSwitchOrExit()

    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applyStreamMetadata(stream)
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename

    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasRetriedCurrentStreamAfter416 = false
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false

    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    refreshScrobbleItem()

    lastSavedPosition = 0L
    _exoPlayer?.stop()
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = true,
            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,
            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true,
            playbackEnded = false
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)
    updateEpisodeDescription()
    refreshSubtitlesForCurrentEpisode()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    skipIntroInFlightKey = null
    lastActiveSkipType = null
    autoSkippedIntervalKeys.clear()

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(currentVideoId ?: contentId, currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}

internal suspend fun PlayerRuntimeController.resolveDirectDebridStreamIfNeeded(
    stream: Stream,
    season: Int?,
    episode: Int?
): Stream? {
    recordLoadingDiagnosticEvent(
        phase = "resolving_debrid",
        message = context.getString(com.sluggyard.tv.R.string.player_loading_preparing),
        detail = stream.addonName
    )
    return when (val result = directDebridResolver.resolveToPlayableStream(stream, season, episode)) {
        is DirectDebridPlayableResult.Success -> {
            recordLoadingDiagnosticEvent(
                phase = "resolving_debrid_done",
                message = context.getString(com.sluggyard.tv.R.string.player_loading_preparing),
                detail = stream.addonName
            )
            result.stream
        }
        DirectDebridPlayableResult.MissingApiKey,
        DirectDebridPlayableResult.NotCached,
        DirectDebridPlayableResult.Stale,
        DirectDebridPlayableResult.Error -> {
            recordLoadingDiagnosticEvent(
                phase = "resolving_debrid_failed",
                message = context.getString(com.sluggyard.tv.R.string.player_loading_preparing),
                detail = result.javaClass.simpleName
            )
            null
        }
    }
}

internal fun PlayerRuntimeController.playNextEpisode(userInitiated: Boolean = false) {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return
    val state = _uiState.value
    val nextInfo = state.nextEpisode ?: return
    if (!nextInfo.hasAired) return
    val activeAutoPlay = state.postPlayMode as? PostPlayMode.AutoPlay
    if (activeAutoPlay != null && (activeAutoPlay.searching || activeAutoPlay.countdownSec != null)) return

    val episodeForMode = state.nextEpisode ?: nextInfo
    _uiState.update {
        it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = episodeForMode, searching = true))
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            val settings = playerSettingsDataStore.playerSettings.first()
            val manualAutoSelectEnabled = settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
                (settings.streamAutoPlayNextEpisodeEnabled || settings.streamAutoPlayPreferBingeGroupForNextEpisode)
            val bingeGroupOnlyManualMode = manualAutoSelectEnabled &&
                !settings.streamAutoPlayNextEpisodeEnabled &&
                settings.streamAutoPlayPreferBingeGroupForNextEpisode
            if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !manualAutoSelectEnabled) {
                _uiState.update {
                    it.copy(postPlayMode = null, postPlayDismissedForCurrentEpisode = true)
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = true)
                return@launch
            }

            val installed = addonRepository.getInstalledAddons().first().enabledAddons()
            val installedOrder = installed.map { it.displayName }
            val effectiveMode = if (manualAutoSelectEnabled) StreamAutoPlayMode.FIRST_STREAM else settings.streamAutoPlayMode
            val effectiveSource = if (manualAutoSelectEnabled) StreamAutoPlaySource.ALL_SOURCES else settings.streamAutoPlaySource
            val effectiveSelectedAddons = if (manualAutoSelectEnabled) emptySet() else settings.streamAutoPlaySelectedAddons
            val effectiveSelectedPlugins = if (manualAutoSelectEnabled) emptySet() else settings.streamAutoPlaySelectedPlugins
            val effectiveRegex = if (manualAutoSelectEnabled) "" else settings.streamAutoPlayRegex

            var selectedStream: Stream? = null
            var lastSuccessData: List<AddonStreams>? = null
            var autoSelectTriggered = false
            var timeoutElapsed = false
            var lastError: NetworkResult.Error? = null
            // Completed as soon as a stream is selected or the addon search
            // finishes, so the waiting code below resumes without polling.
            val searchSettled = CompletableDeferred<Unit>()

            fun selectFrom(data: List<AddonStreams>): Stream? {
                val ordered = StreamAutoPlaySelector.orderAddonStreams(data, installedOrder)
                val allStreams = ordered.flatMap { it.streams }
                return StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = allStreams,
                    mode = effectiveMode,
                    regexPattern = effectiveRegex,
                    source = effectiveSource,
                    installedAddonNames = installedOrder.toSet(),
                    selectedAddons = effectiveSelectedAddons,
                    selectedPlugins = effectiveSelectedPlugins,
                    preferredBingeGroup = if (settings.streamAutoPlayPreferBingeGroupForNextEpisode) currentStreamBingeGroup else null,
                    preferBingeGroupInSelection = settings.streamAutoPlayPreferBingeGroupForNextEpisode,
                    bingeGroupOnly = bingeGroupOnlyManualMode,
                    contentContext = StreamScoringEngine.ContentContext(
                        contentType = contentType,
                        genres = contentGenres,
                        contentLanguage = contentLanguage,
                        title = title,
                        season = nextVideo.season,
                        episode = nextVideo.episode
                    )
                )
            }

            fun selectBingeGroupOnly(data: List<AddonStreams>): Stream? {
                if (currentStreamBingeGroup == null || !settings.streamAutoPlayPreferBingeGroupForNextEpisode) return null
                val ordered = StreamAutoPlaySelector.orderAddonStreams(data, installedOrder)
                val allStreams = ordered.flatMap { it.streams }
                return StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = allStreams,
                    mode = effectiveMode,
                    regexPattern = effectiveRegex,
                    source = effectiveSource,
                    installedAddonNames = installedOrder.toSet(),
                    selectedAddons = effectiveSelectedAddons,
                    selectedPlugins = effectiveSelectedPlugins,
                    preferredBingeGroup = currentStreamBingeGroup,
                    preferBingeGroupInSelection = true,
                    bingeGroupOnly = true,
                    contentContext = StreamScoringEngine.ContentContext(
                        contentType = contentType,
                        genres = contentGenres,
                        contentLanguage = contentLanguage,
                        title = title,
                        season = nextVideo.season,
                        episode = nextVideo.episode
                    )
                )
            }

            fun recordSelection(candidate: Stream) {
                autoSelectTriggered = true
                selectedStream = candidate
                searchSettled.complete(Unit)
            }

            val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
            val innerJob = launch {
                streamRepository.getStreamsFromAllAddons(
                    type = type,
                    videoId = nextVideo.id,
                    season = nextVideo.season,
                    episode = nextVideo.episode
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            lastSuccessData = result.data
                            if (!autoSelectTriggered) {
                                val candidate = when {
                                    timeoutElapsed -> selectFrom(result.data)
                                    settings.streamAutoPlayPreferBingeGroupForNextEpisode -> selectBingeGroupOnly(result.data)
                                    else -> null
                                }
                                if (candidate != null) recordSelection(candidate)
                            }
                        }
                        is NetworkResult.Error -> lastError = result
                        NetworkResult.Loading -> Unit
                    }
                }
                // Every addon has responded: take whatever matched, then settle
                // so the waiting code below resumes even if nothing was selected.
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { data -> selectFrom(data)?.let { recordSelection(it) } }
                }
                searchSettled.complete(Unit)
            }

            val timeoutMs = timeoutSeconds * 1_000L
            when {
                PlayerSettings.isBoundedTimeout(timeoutSeconds) -> {
                    // Wait for the timeout, resuming as soon as a stream is settled.
                    withTimeoutOrNull(timeoutMs) { searchSettled.await() }
                    timeoutElapsed = true
                    if (!autoSelectTriggered) {
                        val data = lastSuccessData
                        if (data != null) {
                            // Streams arrived: full select once. If nothing
                            // matches, respect the timeout and stop (the caller
                            // shows the picker).
                            selectFrom(data)?.let { recordSelection(it) }
                        } else {
                            // No addon responded yet: keep waiting for the first
                            // usable result, bounded so we never hang indefinitely.
                            withTimeoutOrNull(timeoutMs) { searchSettled.await() }
                            if (!autoSelectTriggered) {
                                lastSuccessData?.let { selectFrom(it)?.let { s -> recordSelection(s) } }
                            }
                        }
                    }
                    innerJob.cancel()
                }
                timeoutSeconds == 0 -> {
                    timeoutElapsed = true
                    withTimeoutOrNull(NEXT_EPISODE_SEARCH_HARD_CAP_MS) { searchSettled.await() }
                    if (!autoSelectTriggered) {
                        lastSuccessData?.let { data -> selectFrom(data)?.let { recordSelection(it) } }
                    }
                    innerJob.cancel()
                }
                else -> {
                    withTimeoutOrNull(NEXT_EPISODE_SEARCH_HARD_CAP_MS) { searchSettled.await() }
                    if (!autoSelectTriggered) {
                        lastSuccessData?.let { data -> selectFrom(data)?.let { recordSelection(it) } }
                    }
                    innerJob.cancel()
                }
            }

            val streamToPlay = selectedStream?.let {
                resolveDirectDebridStreamIfNeeded(it, nextVideo.season, nextVideo.episode)
            }
            if (streamToPlay != null) {
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                for (remaining in 3 downTo 1) {
                    _uiState.update { current ->
                        val epForMode = current.nextEpisode ?: nextInfo
                        current.copy(
                            postPlayMode = PostPlayMode.AutoPlay(
                                nextEpisode = epForMode,
                                searching = false,
                                sourceName = sourceName,
                                countdownSec = remaining
                            )
                        )
                    }
                    delay(1000)
                }
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                        playbackEnded = false
                    )
                }
                switchToEpisodeStream(
                    stream = streamToPlay,
                    forcedTargetVideo = nextVideo,
                    isAutoPlay = !userInitiated
                )
            } else {
                _uiState.update {
                    it.copy(postPlayMode = null, postPlayDismissedForCurrentEpisode = true)
                }
                showEpisodeStreamPicker(
                    video = nextVideo,
                    forceRefresh = lastError != null || selectedStream != null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(postPlayMode = null, postPlayDismissedForCurrentEpisode = true)
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}
