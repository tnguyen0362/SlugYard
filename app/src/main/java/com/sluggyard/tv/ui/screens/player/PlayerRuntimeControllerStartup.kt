package com.sluggyard.tv.ui.screens.player

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.sluggyard.tv.R
import com.sluggyard.tv.core.streamresolution.ResolvedPlaybackSource
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.configureRewriteLaunch(source: ResolvedPlaybackSource) {
    if (initialPlaybackStarted) return
    initialStreamUrl = source.url
    currentStreamUrl = source.url
    currentHeaders = emptyMap()
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = source.url,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders,
    )
    _uiState.update {
        it.copy(
            currentStreamUrl = source.url,
            currentStreamName = source.sourceId,
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

    val infoHash = navigationArgs.infoHash
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

    val isTorrentLaunch = infoHash != null && !initialStreamUrl.startsWith("http")
    if (isTorrentLaunch) {
        torrentStreamJob = scope.launch {
            try {
                Log.d("PlayerStartup", "Starting torrent stream for $infoHash")
                observeTorrentState()
                val localUrl = startTorrentStream(
                    infoHash = infoHash,
                    fileIdx = navigationArgs.fileIdx,
                    filename = navigationArgs.filename,
                    trackers = navigationArgs.torrentTrackers
                )
                Log.d("PlayerStartup", "Torrent stream ready: $localUrl")
                currentStreamUrl = localUrl
                currentHeaders = emptyMap()
                preparePlaybackBeforeStart(
                    url = localUrl,
                    headers = emptyMap(),
                    loadSavedProgress = true
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e("PlayerStartup", "Failed to start torrent", e)
                _uiState.update {
                    it.copy(
                        error = context.getString(
                            R.string.player_error_failed_start_torrent,
                            e.message ?: context.getString(R.string.error_unknown)
                        ),
                        showLoadingOverlay = false
                    )
                }
            }
        }
        return
    }

    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = !navigationArgs.startFromBeginning
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
