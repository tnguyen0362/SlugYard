package com.sluggyard.tv.core.addonprotocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class AddonCatalogItem(
    val id: String,
    val type: String,
    val title: String,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
)

data class AddonStreamItem(
    val id: String,
    val title: String,
    val sourceName: String?,
    val directUrl: String?,
    val infoHash: String?,
    val fileIndex: Int?,
    val description: String? = null,
    val filename: String? = null,
    val videoSizeBytes: Long? = null,
    val seeders: Int? = null,
    val bingeGroup: String? = null,
    val videoHash: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val trackers: List<String> = emptyList(),
    /** Browser / deep-link target (WatchHub "where to watch"). Not a playable in-app stream. */
    val externalUrl: String? = null,
)

data class AddonSubtitleTrack(
    val id: String,
    val language: String?,
    val url: String,
    val format: String? = null,
)

/** Decodes only documented response fields and ignores addon-specific extra data safely. */
object StremioResponseDecoder {
    fun catalogItems(payload: JsonObject): List<AddonCatalogItem> = payload.array("metas")
        .orEmpty()
        .mapNotNull(::catalogItem)

    fun streamItems(payload: JsonObject): List<AddonStreamItem> = payload.array("streams")
        .orEmpty()
        .mapIndexedNotNull { index, element -> streamItem(index, element) }

    fun subtitles(payload: JsonObject): List<AddonSubtitleTrack> = payload.array("subtitles")
        .orEmpty()
        .mapIndexedNotNull { index, element -> subtitle(index, element) }

    private fun catalogItem(element: JsonElement): AddonCatalogItem? {
        val objectValue = element as? JsonObject ?: return null
        val id = objectValue.string("id")?.trim().orEmpty()
        val type = objectValue.string("type")?.trim().orEmpty()
        val title = objectValue.string("name")?.trim().orEmpty()
        if (id.isBlank() || type.isBlank() || title.isBlank()) return null
        return AddonCatalogItem(
            id = id,
            type = type,
            title = title,
            posterUrl = objectValue.string("poster"),
            backgroundUrl = objectValue.string("background"),
            description = objectValue.string("description"),
            releaseInfo = objectValue.string("releaseInfo"),
            imdbRating = objectValue.string("imdbRating")?.trim()?.takeIf { it.isNotBlank() },
            genres = objectValue.stringArray("genres"),
        )
    }

    private fun streamItem(index: Int, element: JsonElement): AddonStreamItem? {
        val objectValue = element as? JsonObject ?: return null
        val directUrl = objectValue.string("url")?.takeIf(String::isNotBlank)
        val infoHash = objectValue.string("infoHash")?.takeIf(String::isNotBlank)
        val externalUrl = objectValue.string("externalUrl")?.takeIf(String::isNotBlank)
        // WatchHub (and similar) only supply externalUrl — still a valid stream object for
        // "where to watch" surfaces, even though it is not playable in-app.
        if (directUrl == null && infoHash == null && externalUrl == null) return null
        val name = objectValue.string("name")?.takeIf(String::isNotBlank)
        val title = objectValue.string("title")?.takeIf(String::isNotBlank) ?: name ?: "Stream ${index + 1}"
        val behaviorHints = objectValue.objectValue("behaviorHints")
        val proxyRequest = behaviorHints
            ?.objectValue("proxyHeaders")
            ?.objectValue("request")
            ?.stringMap()
            .orEmpty()
        val sources = objectValue.array("sources").orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }
        val trackers = sources.mapNotNull { source ->
            source.removePrefix("tracker:").takeIf { it != source && it.isNotBlank() }
        }
        return AddonStreamItem(
            id = objectValue.string("id")?.takeIf(String::isNotBlank) ?: "$index:$title",
            title = title,
            sourceName = name,
            directUrl = directUrl,
            infoHash = infoHash,
            fileIndex = (objectValue["fileIdx"] as? JsonPrimitive)?.intOrNull,
            description = objectValue.string("description"),
            filename = behaviorHints?.string("filename"),
            videoSizeBytes = behaviorHints?.long("videoSize"),
            seeders = objectValue.int("seeders")
                ?: objectValue.int("seedCount")
                ?: objectValue.int("peers"),
            bingeGroup = behaviorHints?.string("bingeGroup"),
            videoHash = behaviorHints?.string("videoHash"),
            requestHeaders = proxyRequest,
            trackers = trackers,
            externalUrl = externalUrl,
        )
    }

    private fun subtitle(index: Int, element: JsonElement): AddonSubtitleTrack? {
        val objectValue = element as? JsonObject ?: return null
        val url = objectValue.string("url")?.takeIf(String::isNotBlank) ?: return null
        return AddonSubtitleTrack(
            id = objectValue.string("id")?.takeIf(String::isNotBlank) ?: "$index:$url",
            language = objectValue.string("lang") ?: objectValue.string("language"),
            url = url,
            format = objectValue.string("format")
                ?: url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
                    .takeIf { it.isNotBlank() },
        )
    }

    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.stringArray(name: String): List<String> =
        array(name).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
    private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.stringMap(): Map<String, String> = entries.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { key to it }
    }.toMap()
}
