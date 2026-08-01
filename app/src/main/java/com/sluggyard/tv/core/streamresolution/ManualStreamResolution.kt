package com.sluggyard.tv.core.streamresolution

import kotlinx.coroutines.CancellationException

data class ManualStreamSelection(
    val id: String,
    val directUrl: String?,
    val infoHash: String?,
    val fileIndex: Int?,
    val season: Int? = null,
    val episode: Int? = null,
    val streamName: String? = null,
    val streamDescription: String? = null,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSizeBytes: Long? = null,
    val bingeGroup: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val addonName: String? = null,
    val trackers: List<String> = emptyList(),
)

data class ResolvedPlaybackSource(
    val url: String,
    val sourceId: String,
    val streamName: String? = null,
    val streamDescription: String? = null,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSizeBytes: Long? = null,
    val bingeGroup: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val addonName: String? = null,
    val infoHash: String? = null,
    val fileIndex: Int? = null,
    val trackers: List<String> = emptyList(),
)

data class PlaybackHandoff(
    val source: ResolvedPlaybackSource,
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val addonId: String? = null,
    val parentId: String? = null,
    val parentType: String? = null,
    val resumePositionMs: Long = 0L,
)

sealed interface ManualResolutionResult {
    data class Ready(val source: ResolvedPlaybackSource) : ManualResolutionResult
    data class Unavailable(val message: String) : ManualResolutionResult
    data class Failed(val message: String, val cause: Throwable? = null) : ManualResolutionResult
}

/** Provider adapter supplied by Real-Debrid, Torbox, or Premiumize integrations. */
interface DebridManualResolver {
    val service: DebridService
    suspend fun resolve(infoHash: String, fileIndex: Int?): ResolvedPlaybackSource
    suspend fun resolve(infoHash: String, fileIndex: Int?, season: Int?, episode: Int?): ResolvedPlaybackSource =
        resolve(infoHash, fileIndex)
}

/**
 * Prepares a user-selected stream for the retained playback engine.
 *
 * Direct HTTP streams are immediately playable. Torrents prefer the configured debrid
 * provider; when debrid is unavailable they hand off a `torrent://` sentinel so the
 * retained player can start TorrServer — matching the legacy StreamScreen path.
 */
class ManualStreamResolutionCoordinator(
    private val resolvers: Set<DebridManualResolver>,
) {
    suspend fun prepare(
        selection: ManualStreamSelection,
        configuredService: DebridService?,
    ): ManualResolutionResult {
        selection.directUrl?.takeIf(String::isNotBlank)?.let { directUrl ->
            val lower = directUrl.trim().lowercase()
            val looksLikeTorrentProxy = lower.startsWith("magnet:") ||
                lower.startsWith("torrent:") ||
                "infohash=" in lower ||
                "/resolve/" in lower ||
                "torrentio.strem.fun" in lower ||
                "/createtorrent" in lower
            if (!looksLikeTorrentProxy) {
                return ManualResolutionResult.Ready(selection.toResolved(directUrl))
            }
            // Fall through to hash resolve when the "direct" URL is actually a debrid proxy.
        }
        val infoHash = selection.infoHash?.takeIf(String::isNotBlank)
            ?: return ManualResolutionResult.Unavailable("This stream has no playable URL or torrent hash")
        val service = configuredService
        val resolver = service?.let { svc -> resolvers.firstOrNull { it.service == svc } }
        if (resolver == null) {
            // No debrid: hand the torrent sentinel to the player (legacy behavior).
            return ManualResolutionResult.Ready(
                selection.toResolved(
                    url = "torrent://$infoHash",
                    infoHash = infoHash,
                    fileIndex = selection.fileIndex,
                ),
            )
        }
        return try {
            ManualResolutionResult.Ready(
                resolver.resolve(infoHash, selection.fileIndex, selection.season, selection.episode)
                    .copy(
                        sourceId = selection.id,
                        streamName = selection.streamName,
                        streamDescription = selection.streamDescription,
                        filename = selection.filename,
                        videoHash = selection.videoHash,
                        videoSizeBytes = selection.videoSizeBytes,
                        bingeGroup = selection.bingeGroup,
                        requestHeaders = selection.requestHeaders,
                        addonName = selection.addonName,
                        trackers = selection.trackers,
                    ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ManualResolutionResult.Failed(
                failure.message?.takeIf { it.isNotBlank() }
                    ?: "The selected stream could not be resolved",
                failure,
            )
        }
    }
}

private fun ManualStreamSelection.toResolved(
    url: String,
    infoHash: String? = null,
    fileIndex: Int? = null,
): ResolvedPlaybackSource = ResolvedPlaybackSource(
    url = url,
    sourceId = id,
    streamName = streamName,
    streamDescription = streamDescription,
    filename = filename,
    videoHash = videoHash,
    videoSizeBytes = videoSizeBytes,
    bingeGroup = bingeGroup,
    requestHeaders = requestHeaders,
    addonName = addonName,
    infoHash = infoHash,
    fileIndex = fileIndex,
    trackers = trackers,
)
