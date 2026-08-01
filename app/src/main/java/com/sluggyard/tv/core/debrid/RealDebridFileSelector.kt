package com.sluggyard.tv.core.debrid

import com.sluggyard.tv.data.remote.dto.RealDebridTorrentFileDto
import com.sluggyard.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridFileSelector @Inject constructor() {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): RealDebridTorrentFileDto? {
        val playable = files.filter { it.isPlayableVideo() }
        if (playable.isEmpty()) return null

        val targetSeason = season ?: resolve.season
        val targetEpisode = episode ?: resolve.episode
        val hasExplicitEpisodeTarget = season != null && episode != null
        val episodePatterns = buildDebridEpisodePatterns(
            season = targetSeason,
            episode = targetEpisode,
        )
        val names = resolve.specificDebridFileNames(episodePatterns)
        if (names.isNotEmpty()) {
            playable.firstDebridNameMatch(names) { it.displayName() }?.let { return it }
        }

        if (episodePatterns.isNotEmpty()) {
            playable.bestDebridEpisodeMatch(targetSeason, targetEpisode) { it.displayName() }
                ?.let { return it }
            if (hasExplicitEpisodeTarget) return null
        }

        resolve.fileIdx?.let { fileIdx ->
            files.getOrNull(fileIdx)?.takeIf { it.isPlayableVideo() }?.let { return it }
            if (fileIdx > 0) {
                files.getOrNull(fileIdx - 1)?.takeIf { it.isPlayableVideo() }?.let { return it }
            }
            playable.firstOrNull { it.id == fileIdx }?.let { return it }
        }

        return playable.maxByOrNull { it.bytes ?: 0L }
    }

    private fun RealDebridTorrentFileDto.isPlayableVideo(): Boolean {
        val name = displayName().lowercase()
        return name.hasDebridVideoExtension()
    }
}
