package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.ManualStreamSelection

internal fun StreamCandidate.toManualSelection(
    season: Int? = null,
    episode: Int? = null,
    forceDebridForTorrent: Boolean = false,
): ManualStreamSelection = ManualStreamSelection(
    id = id,
    // Auto-pick / next-episode: never follow addon "direct" URLs for torrents —
    // force the cache-only debrid resolve path (or torrent:// when debrid is off).
    directUrl = if (forceDebridForTorrent && !infoHash.isNullOrBlank()) null else directUrl,
    infoHash = infoHash,
    fileIndex = fileIndex,
    season = season,
    episode = episode,
    streamName = title,
    streamDescription = streamDescription ?: metadataText,
    filename = filename,
    videoHash = videoHash,
    videoSizeBytes = videoSizeBytes,
    bingeGroup = bingeGroup,
    requestHeaders = requestHeaders,
    addonName = sourceLabel,
    trackers = trackers,
)
