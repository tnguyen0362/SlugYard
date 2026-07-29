package com.sluggyard.tv.ui.rewrite

/** Maps Stremio content types to short TV-friendly shelf/search labels. */
internal fun rewriteContentTypeLabel(rawType: String?): String? = when (rawType.orEmpty().trim().lowercase()) {
    "movie", "movies", "film", "films" -> "Movie"
    "series", "show", "shows", "tv", "tvshow", "tvshows" -> "TV"
    else -> rawType?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase() }
}

internal fun rewriteIsMovieType(rawType: String?): Boolean =
    rawType.orEmpty().trim().lowercase() in setOf(
        "movie", "movies", "film", "films",
    )

internal fun rewriteIsSeriesType(rawType: String?): Boolean =
    rawType.orEmpty().trim().lowercase() in setOf(
        "series", "show", "shows", "tv", "tvshow", "tvshows",
    )

internal fun rewriteEpisodeLabel(season: Int?, episode: Int?): String? {
    if (season == null || episode == null) return null
    return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}

internal fun rewritePosterDedupeKey(contentType: String?, id: String): String =
    "${contentType.orEmpty().trim().lowercase()}:$id"

/**
 * Resolves the catalog/meta id for Details from a Continue Watching (or similar) tile.
 * Episode checkpoints often store the episode stream id; Details needs the series parent.
 */
internal fun rewriteDetailsTarget(
    contentId: String,
    contentType: String?,
    parentId: String?,
    parentType: String?,
    season: Int?,
    episode: Int?,
): Pair<String, String> {
    val type = parentType?.takeIf { it.isNotBlank() } ?: contentType?.takeIf { it.isNotBlank() } ?: "movie"
    parentId?.takeIf { it.isNotBlank() }?.let { return it to type }
    inferSeriesIdFromEpisodeContentId(contentId, season, episode)?.let { seriesId ->
        return seriesId to (if (rewriteIsSeriesType(type)) type else "series")
    }
    return contentId to type
}

/** Stremio-style episode ids are usually `{seriesId}:{season}:{episode}`. */
internal fun inferSeriesIdFromEpisodeContentId(
    contentId: String,
    season: Int?,
    episode: Int?,
): String? {
    if (season == null || episode == null) return null
    val suffix = ":$season:$episode"
    if (!contentId.endsWith(suffix) || contentId.length <= suffix.length) return null
    return contentId.removeSuffix(suffix).takeIf { it.isNotBlank() }
}
