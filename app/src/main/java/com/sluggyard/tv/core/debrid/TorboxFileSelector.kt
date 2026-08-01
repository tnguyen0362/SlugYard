package com.sluggyard.tv.core.debrid

import android.util.Log
import com.sluggyard.tv.data.remote.dto.TorboxTorrentFileDto
import com.sluggyard.tv.domain.model.StreamClientResolve
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TorboxFiles"

@Singleton
class TorboxFileSelector @Inject constructor() {
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?
    ): TorboxTorrentFileDto? {
        val playable = files.filter { it.isPlayableVideo() }
        Log.d(
            TAG,
            "select season=$season episode=$episode requestedIndex=${resolve.fileIdx} " +
                "files=${files.size} playable=${playable.size}",
        )
        // Cap candidate dumps — logging 50+ torrent files per resolve janks leanback boxes.
        playable.take(8).forEach { file ->
            Log.d(
                TAG,
                "candidate id=${file.id} size=${file.size ?: -1} name='${file.displayName().take(180)}'",
            )
        }
        if (playable.size > 8) {
            Log.d(TAG, "candidate … +${playable.size - 8} more")
        }
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
            playable.firstDebridNameMatch(names) { it.displayName() }?.let {
                Log.d(TAG, "selected method=specific-name patterns=$episodePatterns name='${it.displayName().take(180)}'")
                return it
            }
        }

        if (episodePatterns.isNotEmpty()) {
            playable.bestDebridEpisodeMatch(targetSeason, targetEpisode) { it.displayName() }?.let {
                Log.d(
                    TAG,
                    "selected method=episode-file patterns=$episodePatterns " +
                        "score=${it.displayName().debridEpisodeMatchScore(targetSeason, targetEpisode)} " +
                        "name='${it.displayName().take(180)}'",
                )
                return it
            }
            if (hasExplicitEpisodeTarget) {
                Log.d(TAG, "no episode file match patterns=$episodePatterns; refusing index/size fallback")
                return null
            }
        }

        if (season != null && episode != null) {
            playable.bestDebridEpisodeMatch(season, episode) { it.displayName() }?.let {
                Log.d(TAG, "selected method=episode-filename season=$season episode=$episode name='${it.displayName().take(180)}'")
                return it
            }
            Log.d(TAG, "no episode filename match for season=$season episode=$episode; refusing index/size fallback")
            return null
        }

        resolve.fileIdx?.let { fileIdx ->
            files.getOrNull(fileIdx)?.takeIf { it.isPlayableVideo() }?.let {
                Log.d(TAG, "selected method=requested-index index=$fileIdx name='${it.displayName().take(180)}'")
                return it
            }
            if (fileIdx > 0) {
                files.getOrNull(fileIdx - 1)?.takeIf { it.isPlayableVideo() }?.let {
                    Log.d(TAG, "selected method=requested-index-minus-one index=${fileIdx - 1} name='${it.displayName().take(180)}'")
                    return it
                }
            }
            playable.firstOrNull { it.id == fileIdx }?.let {
                Log.d(TAG, "selected method=requested-file-id id=$fileIdx name='${it.displayName().take(180)}'")
                return it
            }
        }

        return playable.maxByOrNull { it.size ?: 0L }?.also {
            Log.d(TAG, "selected method=largest-fallback name='${it.displayName().take(180)}'")
        }
    }

    private fun TorboxTorrentFileDto.isPlayableVideo(): Boolean {
        val mime = mimeType.orEmpty().lowercase()
        if (mime.startsWith("video/")) return true
        val name = displayName().lowercase()
        return name.hasDebridVideoExtension()
    }
}
