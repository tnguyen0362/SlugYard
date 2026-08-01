package com.sluggyard.tv.core.trakt

import com.sluggyard.tv.core.addonprotocol.AddonTransportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TRAKT_API_BASE = "https://api.trakt.tv"

/** Credentials are supplied by the rewrite's OAuth device-flow owner; transport never persists them. */
data class TraktCredentials(val clientId: String, val accessToken: String) {
    val isUsable: Boolean get() = clientId.isNotBlank() && accessToken.isNotBlank()
}

data class TraktScrobbleRequest(
    val action: String,
    val progressPercent: Double,
    val traktId: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
)

interface TraktGateway {
    suspend fun lastActivities(credentials: TraktCredentials): AddonTransportResult<JsonObject>
    suspend fun moviePlayback(credentials: TraktCredentials): AddonTransportResult<kotlinx.serialization.json.JsonArray>
    suspend fun episodePlayback(credentials: TraktCredentials): AddonTransportResult<kotlinx.serialization.json.JsonArray>
    suspend fun scrobble(credentials: TraktCredentials, request: TraktScrobbleRequest): AddonTransportResult<JsonObject>
}

/**
 * Minimal, owner-independent Trakt HTTP client. All requests run off the main thread and classify
 * failures for the refresh/scrobble policy rather than throwing into Compose.
 */
class TraktTransport(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TraktGateway {
    override suspend fun lastActivities(credentials: TraktCredentials): AddonTransportResult<JsonObject> =
        getObject(credentials, "/sync/last_activities")

    override suspend fun moviePlayback(credentials: TraktCredentials): AddonTransportResult<kotlinx.serialization.json.JsonArray> =
        getArray(credentials, "/sync/playback/movies")

    override suspend fun episodePlayback(credentials: TraktCredentials): AddonTransportResult<kotlinx.serialization.json.JsonArray> =
        getArray(credentials, "/sync/playback/episodes")

    override suspend fun scrobble(credentials: TraktCredentials, request: TraktScrobbleRequest): AddonTransportResult<JsonObject> {
        if (!credentials.isUsable) return AddonTransportResult.MalformedResponse("Trakt credentials are unavailable")
        val payload = TraktPayloadEncoder.scrobble(request)
        return execute(credentials, "/scrobble/${request.action}", payload.toString()) { element ->
            (element as? JsonObject)?.let { AddonTransportResult.Success(it) }
                ?: AddonTransportResult.MalformedResponse("Trakt scrobble response was not an object")
        }
    }

    private suspend fun getObject(credentials: TraktCredentials, path: String): AddonTransportResult<JsonObject> =
        execute(credentials, path, null) { element ->
            (element as? JsonObject)?.let { AddonTransportResult.Success(it) }
                ?: AddonTransportResult.MalformedResponse("Trakt response was not an object")
        }

    private suspend fun getArray(credentials: TraktCredentials, path: String): AddonTransportResult<kotlinx.serialization.json.JsonArray> =
        execute(credentials, path, null) { element ->
            (element as? kotlinx.serialization.json.JsonArray)?.let { AddonTransportResult.Success(it) }
                ?: AddonTransportResult.MalformedResponse("Trakt response was not an array")
        }

    private suspend fun <T> execute(
        credentials: TraktCredentials,
        path: String,
        body: String?,
        decode: (kotlinx.serialization.json.JsonElement) -> AddonTransportResult<T>,
    ): AddonTransportResult<T> = withContext(Dispatchers.IO) {
        if (!credentials.isUsable) return@withContext AddonTransportResult.MalformedResponse("Trakt credentials are unavailable")
        try {
            val request = TraktRequestFactory.request(credentials, path, body)
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext AddonTransportResult.HttpFailure(response.code, response.message)
                val raw = response.body?.string() ?: return@withContext AddonTransportResult.MalformedResponse("Trakt response body was empty")
                val element = runCatching { json.parseToJsonElement(raw) }.getOrElse {
                    return@withContext AddonTransportResult.MalformedResponse("Trakt response was not JSON")
                }
                decode(element)
            }
        } catch (failure: Throwable) {
            AddonTransportResult.NetworkFailure(failure)
        }
    }
}

object TraktRequestFactory {
    fun request(credentials: TraktCredentials, path: String, body: String? = null): Request {
        require(credentials.isUsable) { "Trakt credentials are required" }
        require(path.startsWith('/')) { "Trakt paths must start with /" }
        return Request.Builder()
            .url(TRAKT_API_BASE + path)
            .header("trakt-api-version", "2")
            .header("trakt-api-key", credentials.clientId)
            .header("Authorization", "Bearer ${credentials.accessToken}")
            .header("Content-Type", "application/json")
            .apply { if (body == null) get() else post(body.toRequestBody("application/json".toMediaType())) }
            .build()
    }
}

object TraktPayloadEncoder {
    fun scrobble(request: TraktScrobbleRequest): JsonObject = buildJsonObject {
        put("progress", request.progressPercent.coerceIn(0.0, 100.0))
        val ids = buildJsonObject {
            request.traktId?.let { put("trakt", it) }
            request.imdbId?.takeIf(String::isNotBlank)?.let { put("imdb", it) }
            request.tmdbId?.let { put("tmdb", it) }
        }
        if (request.mediaType == "episode") {
            put("show", buildJsonObject { put("ids", ids) })
            put("episode", buildJsonObject {
                request.season?.let { put("season", it) }
                request.episode?.let { put("number", it) }
            })
        } else {
            put("movie", buildJsonObject { put("ids", ids) })
        }
    }
}
