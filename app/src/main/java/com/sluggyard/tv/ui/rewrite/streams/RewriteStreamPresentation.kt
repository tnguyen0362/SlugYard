package com.sluggyard.tv.ui.rewrite.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState

/** Pure TV presentation helpers for rewrite stream rows. */
object RewriteStreamPresentation {
    fun detailLine(candidate: RewriteStreamCandidate): String {
        val size = candidate.videoSizeBytes?.takeIf { it > 0L }?.let(::formatFileSize)
        val seeds = candidate.seeders?.takeIf { it > 0 }?.let { "$it seeds" }
        val source = candidate.sourceLabel.takeIf { it.isNotBlank() }
        val detail = candidate.detailLabel?.takeIf { it.isNotBlank() && it != candidate.title }
        return listOfNotNull(source, size, seeds, detail).joinToString(" · ")
    }

    fun cacheLabel(state: StreamCacheState): String? = when (state) {
        StreamCacheState.CACHED -> "Instant"
        StreamCacheState.NOT_CACHED -> "Download"
        StreamCacheState.CHECKING -> "Checking"
        StreamCacheState.UNKNOWN -> "Cache unknown"
        StreamCacheState.NOT_APPLICABLE -> null
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
