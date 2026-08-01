package com.sluggyard.tv.data.remote.dto.opensubtitles

import com.squareup.moshi.Json

data class OpenSubtitlesSearchResponse(
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "total_count") val totalCount: Int = 0,
    @Json(name = "data") val data: List<OpenSubtitlesSubtitleData> = emptyList()
)

data class OpenSubtitlesSubtitleData(
    @Json(name = "id") val id: String = "",
    @Json(name = "type") val type: String = "",
    @Json(name = "attributes") val attributes: OpenSubtitlesAttributes = OpenSubtitlesAttributes()
)

data class OpenSubtitlesAttributes(
    @Json(name = "subtitle_id") val subtitleId: String = "",
    @Json(name = "language") val language: String = "",
    @Json(name = "download_count") val downloadCount: Int = 0,
    @Json(name = "new_download_count") val newDownloadCount: Int = 0,
    @Json(name = "hearing_impaired") val hearingImpaired: Boolean = false,
    @Json(name = "hd") val hd: Boolean = false,
    @Json(name = "fps") val fps: Double? = null,
    @Json(name = "votes") val votes: Int = 0,
    @Json(name = "ratings") val ratings: Double = 0.0,
    @Json(name = "from_trusted") val fromTrusted: Boolean = false,
    @Json(name = "foreign_parts_only") val foreignPartsOnly: Boolean = false,
    @Json(name = "upload_date") val uploadDate: String = "",
    @Json(name = "file_id") val fileId: Int = 0,
    @Json(name = "files") val files: List<OpenSubtitlesFile> = emptyList(),
    @Json(name = "feature_details") val featureDetails: OpenSubtitlesFeatureDetails? = null,
    @Json(name = "release") val release: String? = null,
    @Json(name = "comments") val comments: String? = null,
    @Json(name = "machine_translated") val machineTranslated: Boolean = false,
    @Json(name = "ai_translated") val aiTranslated: Boolean = false,
    @Json(name = "trusted_sources") val trustedSources: List<String>? = null
)

data class OpenSubtitlesFile(
    @Json(name = "file_id") val fileId: Int = 0,
    @Json(name = "cd_number") val cdNumber: Int = 1,
    @Json(name = "file_name") val fileName: String = ""
)

data class OpenSubtitlesFeatureDetails(
    @Json(name = "feature_id") val featureId: Long? = null,
    @Json(name = "imdb_id") val imdbId: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "movie_name") val movieName: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "parent_imdb_id") val parentImdbId: Int? = null,
    @Json(name = "parent_title") val parentTitle: String? = null,
    @Json(name = "parent_feature_id") val parentFeatureId: Long? = null,
    @Json(name = "parent_tmdb_id") val parentTmdbId: Long? = null,
    @Json(name = "tmdb_id") val tmdbId: Long? = null
)

data class OpenSubtitlesDownloadRequest(
    @Json(name = "file_id") val fileId: Int,
    @Json(name = "sub_format") val subFormat: String = "srt"
)

data class OpenSubtitlesDownloadResponse(
    @Json(name = "link") val link: String = "",
    @Json(name = "file_name") val fileName: String = "",
    @Json(name = "requests") val requests: Int = 0,
    @Json(name = "reset_time") val resetTime: String = "",
    @Json(name = "reset_time_utc") val resetTimeUtc: String = "",
    @Json(name = "message") val message: String? = null,
    @Json(name = "remaining") val remaining: Int? = null
)
