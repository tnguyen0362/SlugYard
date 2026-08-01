package com.sluggyard.tv.data.repository

import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.Stream

/**
 * Pure stream fan-out helpers split out of [StreamRepositoryImpl]: dedup-key
 * derivation/merging, addon manifest matching, and the inline-meta video-id
 * derivation. Kept free of Android Context/network dependencies so the exact
 * matching/merge rules can be unit tested directly.
 */
object StreamMergeUtils {

    fun dedupKey(stream: Stream): String {
        stream.infoHash?.lowercase()?.let { hash ->
            return "$hash:${stream.fileIdx ?: ""}"
        }
        stream.clientResolve?.infoHash?.lowercase()?.let { hash ->
            return "$hash:${stream.clientResolve.fileIdx}"
        }
        return stream.url
            ?: stream.externalUrl
            ?: stream.ytId
            ?: "${stream.addonName}:${stream.name}:${stream.title}"
    }

    /**
     * Merges two stream lists by dedup key, with [incoming] entries overwriting
     * [existing] entries that share a key. Only dedupes within this call's
     * combined input — a caller merging emissions one at a time must fold them
     * in sequentially to dedupe across all of them.
     */
    fun mergeStreams(existing: List<Stream>, incoming: List<Stream>): List<Stream> {
        val byKey = LinkedHashMap<String, Stream>()
        existing.forEach { byKey[dedupKey(it)] = it }
        incoming.forEach { byKey[dedupKey(it)] = it }
        return byKey.values.toList()
    }

    /**
     * Honors the resource-level idPrefixes declared in the addon manifest,
     * falling back to the top-level addon idPrefixes when the resource does
     * not declare its own.
     */
    fun supportsStreamResource(addon: Addon, type: String, videoId: String): Boolean =
        addon.resources.any { resource ->
            resource.name == "stream" &&
                (resource.types.isEmpty() || resource.types.contains(type)) &&
                run {
                    val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                        ?: addon.idPrefixes.takeIf { it.isNotEmpty() }
                    prefixes == null || prefixes.any { videoId.startsWith(it) }
                }
        }

    /**
     * Derives the content-level meta id used to look up inline streams from a
     * video-specific id.
     *
     * Video ID formats:
     *   tt1234567:1:5      -> metaId = tt1234567
     *   mal:63375:1:5      -> metaId = mal:63375
     *   kitsu:12345:2      -> metaId = kitsu:12345
     *
     * Strategy: drop up to 2 trailing numeric segments (season, episode) but
     * never reduce below 2 segments for prefixed IDs (mal:X, kitsu:X), or
     * below 1 segment for IMDB-style/numeric IDs.
     */
    fun deriveInlineMetaId(videoId: String): String {
        val parts = videoId.split(":")
        if (parts.size <= 1) return videoId
        val trailingNumeric = parts.reversed().takeWhile { it.toIntOrNull() != null }.size
        val firstSegment = parts.first()
        val minSegments = if (firstSegment.startsWith("tt") || firstSegment.toIntOrNull() != null) 1 else 2
        val dropCount = trailingNumeric.coerceAtMost((parts.size - minSegments).coerceAtLeast(0))
        return if (dropCount > 0) parts.dropLast(dropCount).joinToString(":") else videoId
    }
}