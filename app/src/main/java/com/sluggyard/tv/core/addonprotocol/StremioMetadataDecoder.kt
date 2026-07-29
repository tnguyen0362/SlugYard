package com.sluggyard.tv.core.addonprotocol

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class AddonEpisodeMetadata(
    val id: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val thumbnailUrl: String?,
    val released: String?,
    val description: String? = null,
)

data class AddonMetadata(
    val id: String,
    val type: String,
    val title: String,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String>,
    val cast: List<String>,
    val directors: List<String> = emptyList(),
    val language: String? = null,
    val country: String? = null,
    val episodes: List<AddonEpisodeMetadata>,
)

/** Converts the documented `meta` response object into a stable clean-room domain model. */
object StremioMetadataDecoder {
    fun decode(payload: kotlinx.serialization.json.JsonObject): AddonTransportResult<AddonMetadata> {
        val meta = (payload["meta"] as? kotlinx.serialization.json.JsonObject) ?: payload
        val id = meta.string("id")?.trim().orEmpty()
        val type = meta.string("type")?.trim().orEmpty()
        val title = meta.string("name")?.trim().orEmpty()
        if (id.isBlank() || type.isBlank() || title.isBlank()) {
            return AddonTransportResult.MalformedResponse("Meta response must contain id, type, and name")
        }
        val directors = meta.stringArray("director").ifEmpty {
            listOfNotNull(meta.string("director")?.takeIf(String::isNotBlank))
        }
        return AddonTransportResult.Success(
            AddonMetadata(
                id = id,
                type = type,
                title = title,
                posterUrl = meta.string("poster"),
                backgroundUrl = meta.string("background"),
                description = meta.string("description"),
                releaseInfo = meta.string("releaseInfo") ?: meta.string("year"),
                imdbRating = meta.string("imdbRating"),
                runtime = meta.string("runtime"),
                genres = meta.stringArray("genres"),
                cast = meta.stringArray("cast"),
                directors = directors,
                language = meta.string("language") ?: meta.string("lang"),
                country = meta.string("country"),
                episodes = meta.array("videos").orEmpty().mapIndexedNotNull(::episode),
            ),
        )
    }

    private fun episode(index: Int, element: kotlinx.serialization.json.JsonElement): AddonEpisodeMetadata? {
        val video = element as? kotlinx.serialization.json.JsonObject ?: return null
        val id = video.string("id")?.takeIf(String::isNotBlank) ?: return null
        val title = video.string("title")?.takeIf(String::isNotBlank) ?: "Episode ${index + 1}"
        return AddonEpisodeMetadata(
            id = id,
            season = video.int("season"),
            episode = video.int("episode"),
            title = title,
            thumbnailUrl = video.string("thumbnail"),
            released = video.string("released"),
            description = video.string("overview")
                ?: video.string("description")
                ?: video.string("summary"),
        )
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonObject.int(name: String): Int? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private fun kotlinx.serialization.json.JsonObject.array(name: String): kotlinx.serialization.json.JsonArray? =
        this[name] as? kotlinx.serialization.json.JsonArray

    private fun kotlinx.serialization.json.JsonObject.stringArray(name: String): List<String> =
        array(name).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
}
