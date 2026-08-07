package com.sluggyard.tv.ui.screens.player

import android.util.Log
import com.sluggyard.tv.ui.app.streams.PlayerSubtitleObservation
import com.sluggyard.tv.ui.app.streams.observedReleaseTracksFromPlayer
import kotlinx.coroutines.launch

/**
 * Persist real aid/sid inventory keyed by torrent infoHash so later auto-pick can
 * rank dual/ASS without re-opening the file. Zero cost on next episode of same pack.
 */
internal fun PlayerRuntimeController.maybeRememberObservedReleaseTracks(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>,
) {
    val hash = currentInfoHash?.trim().orEmpty()
    if (hash.isEmpty()) return
    if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) return
    // Avoid thrashing DataStore on every track-list tick with the same snapshot.
    val fingerprint = buildString {
        append(hash)
        append('|')
        append(currentFileIdx ?: -1)
        append('|')
        append(audioTracks.joinToString(",") { "${it.language}/${it.codec}" })
        append('|')
        append(subtitleTracks.joinToString(",") { "${it.language}/${it.codec}/${it.sampleMimeType}" })
    }
    if (fingerprint == lastRememberedReleaseTracksFingerprint) return

    val observed = observedReleaseTracksFromPlayer(
        audioLanguages = audioTracks.map { it.language },
        subtitleTracks = subtitleTracks.map { track ->
            PlayerSubtitleObservation(
                language = track.language,
                isForced = track.isForced,
                isSignsAndSongs = track.isSignsAndSongs,
                looksAss = track.looksLikeAssSoftsub(),
                looksPgs = track.looksLikePgsSoftsub(),
                looksSrt = track.looksLikeSrtSoftsub(),
            )
        },
    )
    scope.launch {
        runCatching {
            releaseTrackMemoryDataStore.remember(
                infoHash = hash,
                fileIdx = currentFileIdx,
                tracks = observed,
            )
            // Only lock the fingerprint after a successful write so failed saves retry.
            lastRememberedReleaseTracksFingerprint = fingerprint
            Log.i(
                PlayerRuntimeController.TAG,
                "TRACK_MEMORY save hash=${hash.take(12)} fileIdx=$currentFileIdx " +
                    "dual=${observed.dualAudio} ass=${observed.hasAss} pgs=${observed.hasPgs} " +
                    "audio=${observed.audioLangBases} subs=${observed.subtitleLangBases}",
            )
        }.onFailure {
            Log.w(PlayerRuntimeController.TAG, "TRACK_MEMORY save failed: ${it.message}")
        }
    }
}

private fun TrackInfo.looksLikeAssSoftsub(): Boolean {
    val blob = listOfNotNull(codec, sampleMimeType, name).joinToString(" ").lowercase()
    // Token-ish: avoid matching words that merely contain "ass" as a substring where possible.
    return Regex("""\b(ass|ssa)\b""").containsMatchIn(blob) ||
        "x-ssa" in blob ||
        "application/x-ssa" in blob
}

private fun TrackInfo.looksLikePgsSoftsub(): Boolean {
    val blob = listOfNotNull(codec, sampleMimeType, name).joinToString(" ").lowercase()
    // Avoid bare "sup" substring (matches "support" / "super").
    return Regex("""\b(pgs|hdmv[ ._-]?pgs|presentation\s*graphic|bdpg)\b""")
        .containsMatchIn(blob) ||
        "hdmv_pgs" in blob ||
        "application/pgs" in blob
}

private fun TrackInfo.looksLikeSrtSoftsub(): Boolean {
    val blob = listOfNotNull(codec, sampleMimeType, name).joinToString(" ").lowercase()
    return Regex("""\b(srt|subrip|vtt|webvtt)\b""").containsMatchIn(blob)
}
