package com.sluggyard.tv.core.streamresolution

import kotlinx.coroutines.CancellationException

data class ManualStreamSelection(
    val id: String,
    val directUrl: String?,
    val infoHash: String?,
    val fileIndex: Int?,
    val season: Int? = null,
    val episode: Int? = null,
)

data class ResolvedPlaybackSource(
    val url: String,
    val sourceId: String,
)

data class RewritePlaybackHandoff(
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
 * This coordinator deliberately knows nothing about Media3, decoder settings, or autoplay. It
 * only owns the spec-required manual resolution rule: direct streams are immediately playable;
 * torrent streams are resolved by the selected provider before any player handoff occurs.
 */
class ManualStreamResolutionCoordinator(
    private val resolvers: Set<DebridManualResolver>,
) {
    suspend fun prepare(
        selection: ManualStreamSelection,
        configuredService: DebridService?,
    ): ManualResolutionResult {
        selection.directUrl?.takeIf(String::isNotBlank)?.let { directUrl ->
            return ManualResolutionResult.Ready(ResolvedPlaybackSource(directUrl, selection.id))
        }
        val infoHash = selection.infoHash?.takeIf(String::isNotBlank)
            ?: return ManualResolutionResult.Unavailable("This stream has no playable URL or torrent hash")
        val service = configuredService
            ?: return ManualResolutionResult.Unavailable("A debrid provider is required for this torrent")
        val resolver = resolvers.firstOrNull { it.service == service }
            ?: return ManualResolutionResult.Unavailable("The configured debrid provider is unavailable")
        return try {
            ManualResolutionResult.Ready(
                resolver.resolve(infoHash, selection.fileIndex, selection.season, selection.episode)
                    .copy(sourceId = selection.id),
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
