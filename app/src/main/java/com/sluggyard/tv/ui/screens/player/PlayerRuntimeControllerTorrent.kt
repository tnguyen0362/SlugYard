package com.sluggyard.tv.ui.screens.player

import android.util.Log
import com.sluggyard.tv.R
import com.sluggyard.tv.core.torrent.TorrentState
import com.sluggyard.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

private const val TAG = "PlayerTorrent"

private const val TORRENT_PRELOAD_TARGET_BYTES = 5_242_880L // ~5 MiB TorrServer preload window

/**
 * Hands the torrent off to TorrServer and returns the local HTTP URL ExoPlayer should read from.
 * TorrServer owns piece management, buffering, and Range-based seeking.
 */
internal suspend fun PlayerRuntimeController.startTorrentStream(
    infoHash: String,
    fileIdx: Int?,
    filename: String? = null,
    trackers: List<String> = emptyList()
): String {
    isTorrentStream = true
    currentInfoHash = infoHash
    currentFileIdx = fileIdx

    setLoadingStatus(
        phase = "torrent_starting_engine",
        message = context.getString(R.string.player_torrent_starting_engine),
        showOverlay = true
    )
    _uiState.update {
        it.copy(
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_torrent_starting_engine),
            loadingProgress = null,
            isTorrentStream = true
        )
    }

    val effectiveFilename = filename ?: currentFilename
    return torrentService.startStream(infoHash, fileIdx, effectiveFilename, trackers)
}

/**
 * Tears down the active torrent stream and clears the local torrent bookkeeping.
 */
internal fun PlayerRuntimeController.stopTorrentStream() {
    torrentStreamJob?.cancel()
    torrentStreamJob = null
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = null

    if (isTorrentStream) {
        scope.launch(NonCancellable) { torrentService.stopStream() }
    }

    isTorrentStream = false
    currentInfoHash = null
    currentFileIdx = null
}

/**
 * Subscribes to TorrentService state and projects it onto PlayerUiState torrent fields.
 */
internal fun PlayerRuntimeController.observeTorrentState() {
    torrentStateObserverJob?.cancel()
    torrentStateObserverJob = scope.launch {
        torrentService.state.collectLatest { state ->
            when (state) {
                is TorrentState.Idle -> Unit

                is TorrentState.Connecting -> {
                    if (!hasRenderedFirstFrame) {
                        recordLoadingDiagnosticEvent(
                            phase = "torrent_connecting_peers",
                            message = context.getString(R.string.player_torrent_connecting_peers)
                        )
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = context.getString(R.string.player_torrent_connecting_peers),
                                loadingProgress = null,
                                torrentBufferingMessage = null
                            )
                        }
                    }
                }

                is TorrentState.Streaming -> {
                    val speedText = formatSpeed(context, state.downloadSpeed)
                    val peerText = context.getString(R.string.player_torrent_peer_info, state.seeds, state.peers)
                    val preloadedText = formatMB(context, state.preloadedBytes)
                    val statsHidden = _uiState.value.hideTorrentStats

                    if (!hasRenderedFirstFrame) {
                        // Pre-roll preload: TorrServer buffers ~5 MiB before playback begins.
                        val progress = (state.preloadedBytes.toFloat() / TORRENT_PRELOAD_TARGET_BYTES).coerceIn(0f, 1f)
                        val message = if (statsHidden) null
                        else context.getString(R.string.player_torrent_buffered_status, preloadedText, peerText, speedText)
                        recordLoadingDiagnosticEvent(
                            phase = "torrent_preloading",
                            message = message,
                            progress = progress,
                            detail = "${state.seeds}/${state.peers}"
                        )
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = true,
                                loadingMessage = message,
                                loadingProgress = progress,
                                torrentDownloadSpeed = state.downloadSpeed,
                                torrentUploadSpeed = state.uploadSpeed,
                                torrentPeers = state.peers,
                                torrentSeeds = state.seeds,
                                torrentBufferProgress = state.bufferProgress,
                                torrentTotalProgress = state.totalProgress,
                                torrentBufferingMessage = null
                            )
                        }
                    } else {
                        // Mid-playback stats; the rebuffer message is owned by the progress loop.
                        val message = if (statsHidden) null
                        else context.getString(R.string.player_torrent_status, peerText, speedText)
                        _uiState.update {
                            it.copy(
                                loadingProgress = null,
                                torrentDownloadSpeed = state.downloadSpeed,
                                torrentUploadSpeed = state.uploadSpeed,
                                torrentPeers = state.peers,
                                torrentSeeds = state.seeds,
                                torrentBufferProgress = state.bufferProgress,
                                torrentTotalProgress = state.totalProgress,
                                torrentBufferingMessage = message
                            )
                        }
                    }
                }

                is TorrentState.Error -> {
                    Log.e(TAG, "Torrent error: ${state.message}")
                    _uiState.update {
                        it.copy(
                            error = context.getString(R.string.player_error_torrent, state.message),
                            showLoadingOverlay = false,
                            torrentBufferingMessage = null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Kicks off a torrent stream when the user switches source/episode mid-playback.
 */
internal fun PlayerRuntimeController.launchTorrentSourceStream(
    stream: Stream,
    infoHash: String,
    loadSavedProgress: Boolean
) {
    torrentStreamJob?.cancel()
    torrentStreamJob = scope.launch {
        try {
            observeTorrentState()

            currentTorrentSources = stream.sources
            val trackers = stream.sources
                ?.filter { it.startsWith("tracker:") }
                ?.map { it.removePrefix("tracker:") }
                ?: emptyList()
            val localUrl = startTorrentStream(
                infoHash = infoHash,
                fileIdx = stream.getEffectiveFileIdx(),
                filename = stream.behaviorHints?.filename,
                trackers = trackers
            )

            currentStreamUrl = localUrl
            currentHeaders = emptyMap()
            currentStreamMimeType = null

            preparePlaybackBeforeStart(
                url = localUrl,
                headers = emptyMap(),
                loadSavedProgress = loadSavedProgress
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start torrent stream", e)
            _uiState.update {
                it.copy(
                    error = context.getString(
                        R.string.player_error_failed_start_torrent,
                        e.message ?: context.getString(R.string.error_unknown)
                    ),
                    showLoadingOverlay = false,
                    loadingProgress = null
                )
            }
        }
    }
}

private fun formatSpeed(context: android.content.Context, bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 ->
            context.getString(R.string.unit_speed_mb_s, String.format("%.1f", bytesPerSec / 1_048_576.0))
        bytesPerSec >= 1_024 ->
            context.getString(R.string.unit_speed_kb_s, String.format("%.0f", bytesPerSec / 1_024.0))
        else ->
            context.getString(R.string.unit_speed_b_s, bytesPerSec)
    }
}

private fun formatMB(context: android.content.Context, bytes: Long): String =
    context.getString(R.string.unit_size_mb, String.format("%.1f", bytes / 1_048_576.0))