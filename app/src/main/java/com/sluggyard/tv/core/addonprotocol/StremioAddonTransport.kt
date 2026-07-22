package com.sluggyard.tv.core.addonprotocol

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AddonTransportResult<out T> {
    data class Success<T>(val value: T) : AddonTransportResult<T>
    data class HttpFailure(val statusCode: Int, val message: String?) : AddonTransportResult<Nothing>
    data class NetworkFailure(val cause: Throwable) : AddonTransportResult<Nothing>
    data class MalformedResponse(val message: String) : AddonTransportResult<Nothing>
}

/**
 * Small independent HTTP client for the Stremio-compatible endpoints used by the rewrite.
 *
 * It intentionally returns response categories instead of throwing through UI state. A caller can
 * distinguish a valid empty result from a timeout, HTTP error, or malformed response while the
 * fan-out coordinator keeps sibling addons alive.
 */
interface StremioAddonGateway {
    suspend fun fetchManifest(manifestUrl: String): AddonTransportResult<AddonManifestContract>
    suspend fun fetchCatalog(
        manifestUrl: String,
        type: String,
        catalogId: String,
        extras: Map<String, String> = emptyMap(),
    ): AddonTransportResult<JsonObject>
    suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject>
    suspend fun fetchStreams(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject>
    suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject>
}

class StremioAddonTransport(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : StremioAddonGateway {
    override suspend fun fetchManifest(manifestUrl: String): AddonTransportResult<AddonManifestContract> {
        val payload = getJson(manifestUrl)
        return when (payload) {
            is AddonTransportResult.Success -> StremioManifestDecoder.decode(payload.value)
            is AddonTransportResult.HttpFailure -> payload
            is AddonTransportResult.NetworkFailure -> payload
            is AddonTransportResult.MalformedResponse -> payload
        }
    }

    override suspend fun fetchCatalog(
        manifestUrl: String,
        type: String,
        catalogId: String,
        extras: Map<String, String>,
    ): AddonTransportResult<JsonObject> = getJson(
        StremioEndpointBuilder.catalog(manifestUrl, type, catalogId, extras),
    )

    override suspend fun fetchMeta(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> =
        getJson(StremioEndpointBuilder.meta(manifestUrl, type, id))

    override suspend fun fetchStreams(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> =
        getJson(StremioEndpointBuilder.streams(manifestUrl, type, id))

    override suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String): AddonTransportResult<JsonObject> =
        getJson(StremioEndpointBuilder.subtitles(manifestUrl, type, id))

    private suspend fun getJson(url: String): AddonTransportResult<JsonObject> = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AddonTransportResult.HttpFailure(response.code, response.message)
                }
                val body = response.body?.string()
                    ?: return@withContext AddonTransportResult.MalformedResponse("Response body was empty")
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                    return@withContext AddonTransportResult.MalformedResponse("Response was not a JSON object")
                }
                AddonTransportResult.Success(parsed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AddonTransportResult.NetworkFailure(failure)
        }
    }
}

object StremioEndpointBuilder {
    fun catalog(
        manifestUrl: String,
        type: String,
        catalogId: String,
        extras: Map<String, String> = emptyMap(),
    ): String = endpoint(manifestUrl, buildList {
        add("catalog")
        add(type)
        add(catalogId)
        extras.toSortedMap().takeIf { it.isNotEmpty() }?.let { values ->
            add(values.entries.joinToString(",") { (key, value) -> "$key=$value" })
        }
    })

    fun meta(manifestUrl: String, type: String, id: String): String = endpoint(manifestUrl, listOf("meta", type, id))

    fun streams(manifestUrl: String, type: String, id: String): String = endpoint(manifestUrl, listOf("stream", type, id))

    fun subtitles(manifestUrl: String, type: String, id: String): String = endpoint(manifestUrl, listOf("subtitles", type, id))

    private fun endpoint(manifestUrl: String, segments: List<String>): String {
        val manifest = URI(manifestUrl)
        require(manifest.scheme in setOf("http", "https") && !manifest.host.isNullOrBlank()) {
            "Manifest URL must be an absolute HTTP(S) URL"
        }
        val parent = manifest.resolve(".")
        val encodedSegments = segments.joinToString("/") { segment ->
            segment.encodePathSegment()
        }
        val endpoint = parent.resolve("$encodedSegments.json")
        // Stremio configurable manifests commonly carry their configuration in the manifest
        // query. Preserve it on every derived resource request; dropping it silently turns a
        // valid configured addon into an unconfigured one.
        return buildString {
            append(endpoint)
            manifest.rawQuery?.takeIf(String::isNotBlank)?.let { query ->
                append('?')
                append(query)
            }
        }
    }

    private fun String.encodePathSegment(): String =
        buildString(length) {
            toByteArray(Charsets.UTF_8).forEach { byte ->
                val value = byte.toInt() and 0xFF
                val character = value.toChar()
                if ((value in 0x30..0x39) || (value in 0x41..0x5A) ||
                    (value in 0x61..0x7A) || character in "-_.=,") {
                    append(character)
                } else {
                    append("%")
                    append(value.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
}

object StremioManifestDecoder {
    fun decode(payload: JsonObject): AddonTransportResult<AddonManifestContract> {
        val id = payload.string("id")?.trim().orEmpty()
        val name = payload.string("name")?.trim().orEmpty()
        if (id.isBlank() || name.isBlank()) {
            return AddonTransportResult.MalformedResponse("Manifest must contain a non-empty id and name")
        }
        val catalogs = payload.array("catalogs")
            ?.mapNotNull(::decodeCatalog)
            .orEmpty()
        return AddonTransportResult.Success(
            AddonManifestContract(
                id = id,
                name = name,
                version = payload.string("version"),
                description = payload.string("description"),
                logoUrl = payload.string("logo"),
                backgroundUrl = payload.string("background"),
                resources = payload.array("resources").orEmpty().mapNotNull(::decodeResource).toSet(),
                types = payload.array("types").orEmpty().mapNotNull { it.stringValue()?.takeIf(String::isNotBlank) }.toSet(),
                catalogs = catalogs,
                behaviorHints = decodeBehaviorHints(payload.objectValue("behaviorHints")),
            ),
        )
    }

    private fun decodeCatalog(element: JsonElement): AddonCatalogDeclaration? {
        val objectValue = element as? JsonObject ?: return null
        val id = objectValue.string("id")?.trim().orEmpty()
        val type = objectValue.string("type")?.trim().orEmpty()
        if (id.isBlank() || type.isBlank()) return null
        return AddonCatalogDeclaration(
            id = id,
            type = type,
            displayName = objectValue.string("name")?.trim().takeUnless(String?::isNullOrBlank) ?: id,
            extras = objectValue.array("extra").orEmpty().mapNotNull { extra ->
                val extraObject = extra as? JsonObject ?: return@mapNotNull null
                extraObject.string("name")?.takeIf(String::isNotBlank)?.let { name ->
                    AddonCatalogExtra(name, extraObject.boolean("isRequired") ?: false)
                }
            },
        )
    }

    private fun decodeResource(element: JsonElement): AddonResource? {
        val raw = element.stringValue() ?: (element as? JsonObject)?.string("name")
        return raw?.uppercase()?.let { value -> AddonResource.entries.firstOrNull { it.name == value } }
    }

    private fun decodeBehaviorHints(objectValue: JsonObject?): AddonBehaviorHints = AddonBehaviorHints(
        configurable = objectValue?.boolean("configurable") ?: false,
        adultContent = objectValue?.boolean("adult") ?: false,
    )

    private fun JsonObject.string(name: String): String? = this[name]?.stringValue()
    private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray
    private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull
}
