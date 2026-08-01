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
        raw?.parsed?.rawTitle?.takeIf { it.looksSpecificForDebridSelection(episodePatterns, season, episode) },
        torrentName?.takeIf { it.looksSpecificForDebridSelection(episodePatterns, season, episode) },
        title?.takeIf { it.looksSpecificForDebridSelection(episodePatterns, season, episode) },
    )
        .map { it.normalizedDebridFileName() }
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun String.looksSpecificForDebridSelection(
    episodePatterns: List<String>,
    season: Int? = null,
    episode: Int? = null,
): Boolean {
    val lower = lowercase()
    if (lower.hasDebridVideoExtension()) return true
    if (episodePatterns.any { pattern ->
            Regex("(?:^|[\\s.\\-_])${Regex.escape(pattern)}(?:[\\s.\\-_]|$)")
                .containsMatchIn(lower)
        }
    ) {
        return true
    }
    // Absolute pack titles ("Show - 004 - Name") without a video extension still identify a file.
    return debridEpisodeMatchScore(season, episode) >= 80
}

/** Matches common episode filenames such as "- 01" while excluding specials and OP/ED assets. */
internal fun String.matchesDebridEpisode(season: Int?, episode: Int?): Boolean =
    debridEpisodeMatchScore(season, episode) >= 0

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

/**
 * Matches the common "- 01" / "S01E01" pack naming style without treating specials or
 * creditless assets as episodes.
 *
 * Important: never match a bare single-digit episode token. That selected
 * "…028 - Hidden Inventory 4" for S01E04 (title ends in "4") instead of "…004…".
 */
internal fun String.matchesDebridEpisodeFile(season: Int?, episode: Int?): Boolean =
    debridEpisodeMatchScore(season, episode) >= 0

/**
 * Higher is better. Returns -1 when the name must not be treated as the target episode.
 *
 * Absolute padded numbers ("004" / "04") are only accepted for season 1. Multi-season
 * absolute packs use series-wide indices (JJK S2E4 ≈ 028, not 004), so season>1 requires
 * an explicit SxxEyy / NxNN marker.
 */
internal fun String.debridEpisodeMatchScore(season: Int?, episode: Int?): Int {
    if (season == null || episode == null) return -1
    val normalized = normalizedDebridFileName()
    if (normalized.isDebridSpecialAsset()) return -1

    val seasonTwo = season.toString().padStart(2, '0')
    val episodeTwo = episode.toString().padStart(2, '0')
    val standardPatterns = listOf(
        "s${seasonTwo}e$episodeTwo",
        "${season}x$episodeTwo",
        "${season}x$episode",
    )
    if (standardPatterns.any { normalized.containsToken(it) }) return 100

    // Absolute pack numbers only for season 1 — never bare "4" / "1".
    if (season == 1) {
        val absoluteTokens = listOf(
            episode.toString().padStart(3, '0'),
            episodeTwo,
        ).distinct()
        if (absoluteTokens.any { normalized.containsToken(it) }) return 80
    }
    return -1
}

internal fun <T> List<T>.bestDebridEpisodeMatch(
    season: Int?,
    episode: Int?,
    displayName: (T) -> String,
): T? =
    mapNotNull { item ->
        val score = displayName(item).debridEpisodeMatchScore(season, episode)
        if (score < 0) null else item to score
    }.maxByOrNull { it.second }?.first

internal fun String.hasDebridVideoExtension(): Boolean =
    debridVideoExtensions.any { endsWith(it) }

private fun String.isDebridSpecialAsset(): Boolean =
    Regex("\\b(?:special|especial|ova|oad|creditless|opening|ending|op|ed)\\b")
        .containsMatchIn(this)

private fun String.containsToken(token: String): Boolean =
    Regex("(?:^|\\s)${Regex.escape(token)}(?:\\s|$)").containsMatchIn(this)

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
