package com.sluggyard.tv.ui.screens.player

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.sluggyard.tv.R
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import com.sluggyard.tv.ui.screens.player.PlayerMediaSourceFactory
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.configureLaunch(source: ResolvedPlaybackSource) {
    if (initialPlaybackStarted) return
    initialStreamUrl = source.url
    currentStreamUrl = source.url
    currentHeaders = PlayerMediaSourceFactory.sanitizeHeaders(source.requestHeaders)
    currentFilename = source.filename
        ?: source.url.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
        ?: currentFilename
    currentVideoHash = source.videoHash ?: currentVideoHash
    currentVideoSize = source.videoSizeBytes ?: currentVideoSize
    currentStreamDescription = source.streamDescription ?: currentStreamDescription
    currentAddonName = source.addonName ?: currentAddonName
    currentInfoHash = source.infoHash
    isTorrentStream = source.infoHash != null && !source.url.startsWith("http", ignoreCase = true)
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = source.url,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders,
    )
    source.bingeGroup?.takeIf { it.isNotBlank() }?.let { binge ->
        contentId?.let { cid ->
            scope.launch(kotlinx.coroutines.NonCancellable) {
                bingeGroupCacheDataStore.save(cid, binge)
            }
        }
    }
    _uiState.update {
        it.copy(
            currentStreamUrl = source.url,
            currentStreamName = source.streamName ?: source.sourceId,
            currentStreamAddonName = source.addonName ?: it.currentStreamAddonName,
            currentStreamInfoHash = source.infoHash,
            currentStreamFileIdx = source.fileIndex,
            error = null,
        )
    }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return
    initialPlaybackStarted = true

    val bingeGroup = navigationArgs.bingeGroup
    val cid = contentId
    if (bingeGroup != null && cid != null) {
        scope.launch(kotlinx.coroutines.NonCancellable) {
            bingeGroupCacheDataStore.save(cid, bingeGroup)
        }
    }

    val infoHash = currentInfoHash ?: navigationArgs.infoHash
    val fileIdx = _uiState.value.currentStreamFileIdx ?: navigationArgs.fileIdx
    val clickElapsedMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    queuePlaybackRawEventLine(
        "PLAYER_START_REQUEST: clickElapsedMs=$clickElapsedMs host=${initialStreamUrl.safeStartupHost()} " +
            "contentId=${contentId ?: "n/a"} videoId=${currentVideoId ?: "n/a"} " +
            "S${currentSeason ?: "-"}E${currentEpisode ?: "-"} infoHash=${infoHash != null} " +
            "startFromBeginning=${navigationArgs.startFromBeginning} streamName=${streamName ?: "n/a"}"
    )
    Log.d(
        "PlayerStartup",
        "startInitialPlayback: infoHash=${infoHash != null}, streamHost=${initialStreamUrl.safeStartupHost()} " +
            "streamHash=${initialStreamUrl.hashCode().toUInt().toString(16)}",
    )

    val isTorrentLaunch = infoHash != null && !currentStreamUrl.startsWith("http", ignoreCase = true)
    if (isTorrentLaunch) {
        torrentStreamJob = scope.launch {
            try {
                Log.d("PlayerStartup", "Starting torrent stream for $infoHash")
                observeTorrentState()
                val localUrl = startTorrentStream(
                    infoHash = infoHash!!,
                    fileIdx = fileIdx,
                    filename = currentFilename ?: navigationArgs.filename,
                    trackers = navigationArgs.torrentTrackers,
                )
                Log.d("PlayerStartup", "Torrent stream ready: $localUrl")
                currentStreamUrl = localUrl
                currentHeaders = emptyMap()
                preparePlaybackBeforeStart(
                    url = localUrl,
                    headers = emptyMap(),
                    loadSavedProgress = true,
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("PlayerStartup", "Failed to start torrent", e)
                _uiState.update {
                    it.copy(
                        error = context.getString(
                            R.string.player_error_failed_start_torrent,
                            e.message ?: context.getString(R.string.error_unknown),
                        ),
                        showLoadingOverlay = false,
                    )
                }
            }
        }
        return
    }

    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = !navigationArgs.startFromBeginning,
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}

private fun String.safeStartupHost(): String {
    return runCatching {
        android.net.Uri.parse(this).host
            ?: substringBefore("://").takeIf { it.isNotBlank() }
            ?: "unknown"
    }.getOrDefault("unknown")
}
