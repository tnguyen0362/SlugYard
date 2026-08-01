package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streamresolution.StreamCacheState

/** Pure TV presentation helpers for rewrite stream rows. */
object StreamPresentation {
    fun detailLine(candidate: StreamCandidate): String {
        val size = candidate.videoSizeBytes?.takeIf { it > 0L }?.let(::formatFileSize)
        val seeds = candidate.seeders?.takeIf { it > 0 }?.let { "$it seeds" }
        val source = candidate.sourceLabel.takeIf { it.isNotBlank() }
        val language = languageHint(candidate)
        val detail = candidate.detailLabel
            ?.takeIf { it.isNotBlank() && it != candidate.title }
            ?.takeIf { language == null || !it.contains(language, ignoreCase = true) }
        return listOfNotNull(source, size, seeds, language, detail).joinToString(" · ")
    }

    /** Best-effort language / audio tag from title/detail for Sources rows. */
    fun languageHint(candidate: StreamCandidate): String? {
        val text = buildString {
            append(candidate.title)
            append(' ')
            append(candidate.detailLabel.orEmpty())
        }
        return when {
            Regex("\\bdual[- ]?audio\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Dual Audio"
            Regex("\\bmulti[- ]?(?:audio|sub)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Multi"
            Regex("\\beng(?:lish)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Eng"
            Regex("\\b(?:esp|spa|spanish|latino)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Esp"
            Regex("\\bjpn|japanese\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Jpn"
            else -> null
        }
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
