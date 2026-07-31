package com.sluggyard.tv.core.debrid

import com.sluggyard.tv.domain.model.StreamClientResolve

internal fun String.normalizedDebridFileName(): String =
    substringAfterLast('/')
        .substringBeforeLast('.')
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

internal fun StreamClientResolve.specificDebridFileNames(episodePatterns: List<String>): List<String> {
    val raw = stream?.raw
    return listOfNotNull(
        filename,
        raw?.filename,
        raw?.parsed?.rawTitle?.takeIf { it.looksSpecificForDebridSelection(episodePatterns) },
        torrentName?.takeIf { it.looksSpecificForDebridSelection(episodePatterns) }
    )
        .map { it.normalizedDebridFileName() }
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun String.looksSpecificForDebridSelection(episodePatterns: List<String>): Boolean {
    val lower = lowercase()
    return lower.hasDebridVideoExtension() || episodePatterns.any { pattern -> lower.contains(pattern) }
}

/** Matches common episode filenames such as "- 01" while excluding specials and OP/ED assets. */
internal fun String.matchesDebridEpisode(season: Int?, episode: Int?): Boolean {
    if (season == null || episode == null) return false
    val normalized = normalizedDebridFileName()
    if (Regex("\\b(?:special|especial|ova|oad|creditless|opening|ending|op|ed)\\b")
            .containsMatchIn(normalized)
    ) {
        return false
    }
    val seasonTwo = season.toString().padStart(2, '0')
    val episodeTwo = episode.toString().padStart(2, '0')
    val tokens = listOf(
        "s${seasonTwo}e$episodeTwo",
        "${season}x$episodeTwo",
        "${season}x$episode",
        episodeTwo,
        episode.toString(),
    ).distinct()
    return tokens.any { token ->
        Regex("(?:^|\\s)${Regex.escape(token)}(?:\\s|$)").containsMatchIn(normalized)
    }
}

internal fun <T> List<T>.firstDebridNameMatch(
    names: List<String>,
    displayName: (T) -> String
): T? =
    firstOrNull { item ->
        val fileName = displayName(item).normalizedDebridFileName()
        names.any { name -> fileName.contains(name) || name.contains(fileName) }
    }

internal fun buildDebridEpisodePatterns(season: Int?, episode: Int?): List<String> {
    if (season == null || episode == null) return emptyList()
    val seasonTwo = season.toString().padStart(2, '0')
    val episodeTwo = episode.toString().padStart(2, '0')
    return listOf(
        "s${seasonTwo}e$episodeTwo",
        "${season}x$episodeTwo",
        "${season}x$episode"
    )
}

/** Matches the common "- 01" pack naming style without treating specials or creditless assets as episodes. */
internal fun String.matchesDebridEpisodeFile(season: Int?, episode: Int?): Boolean {
    if (season == null || episode == null) return false
    val normalized = normalizedDebridFileName()
    if (Regex("\\b(?:special|especial|ova|oad|creditless|opening|ending|op|ed)\\b").containsMatchIn(normalized)) {
        return false
    }
    val seasonTwo = season.toString().padStart(2, '0')
    val episodeTwo = episode.toString().padStart(2, '0')
    val standardPatterns = listOf(
        "s${seasonTwo}e$episodeTwo",
        "${season}x$episodeTwo",
        "${season}x$episode",
    )
    if (standardPatterns.any { normalized.contains(it) }) return true
    return listOf(episodeTwo, episode.toString()).distinct().any { token ->
        Regex("(?:^|\\s)${Regex.escape(token)}(?:\\s|$)").containsMatchIn(normalized)
    }
}

internal fun String.hasDebridVideoExtension(): Boolean =
    debridVideoExtensions.any { endsWith(it) }

private val debridVideoExtensions = setOf(
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".m4v",
    ".ts",
    ".m2ts",
    ".wmv",
    ".flv"
)
