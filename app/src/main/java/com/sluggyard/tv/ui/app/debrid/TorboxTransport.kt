package com.sluggyard.tv.ui.app.debrid

import com.sluggyard.tv.ui.app.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class TorboxFile(
    val id: Int,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

data class TorboxCloudItem(
    val id: Int,
    val name: String,
    val sizeBytes: Long?,
    val downloadState: String?,
)

data class TorboxTorrentSnapshot(
    val id: Int,
    val files: List<TorboxFile>,
    val downloadState: String?,
    val progress: Double?,
) {
    val isReady: Boolean
        get() {
            if (files.isEmpty()) return false
            val state = downloadState?.lowercase().orEmpty()
            if (state in TERMINAL_FAIL_STATES) return false
            if (state.isBlank()) return true
            return state in READY_STATES
        }

    val isFailed: Boolean
        get() = downloadState?.lowercase().orEmpty() in TERMINAL_FAIL_STATES

    companion object {
        private val READY_STATES = setOf(
            "cached",
            "completed",
            "complete",
            "downloaded",
            "finished",
            "ready",
            "seeding",
            "uploading",
        )
        private val TERMINAL_FAIL_STATES = setOf(
            "error",
            "failed",
            "dead",
            "magnet_error",
            "virus",
            "stalled (no seeds)",
            "stalled",
        )
    }
}

sealed interface TorboxResult<out T> {
    data class Success<T>(val value: T) : TorboxResult<T>
    data class HttpFailure(val statusCode: Int) : TorboxResult<Nothing>
    data object InvalidResponse : TorboxResult<Nothing>
    data class NetworkFailure(val cause: Throwable) : TorboxResult<Nothing>
}

/**
 * Rewrite-owned Torbox HTTP boundary. It has no dependency on old settings, resolvers, Retrofit,
 * or provider UI. File choice is intentionally left to the retained autofile picker boundary.
 */
class TorboxTransport(
    private val client: OkHttpClient = NetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun validateCredential(apiKey: String): TorboxResult<Unit> =
        get(apiKey, "v1/api/user/me").map { Unit }

    suspend fun checkCached(apiKey: String, hashes: Set<String>): TorboxResult<Set<String>> {
        val normalized = hashes.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return TorboxResult.Success(emptySet())
        val requestBody = normalized.joinToString(prefix = "{\"hashes\":[", postfix = "]}") { "\"$it\"" }
            .toRequestBody("application/json".toMediaType())
        return post(apiKey, "v1/api/torrents/checkcached?format=object", requestBody).flatMap { payload ->
            TorboxResponseDecoder.cachedHashes(payload)
        }
    }

    suspend fun createCachedTorrent(apiKey: String, infoHash: String): TorboxResult<Int> =
        createTorrent(apiKey, infoHash, onlyIfCached = true)

    /**
     * Queues a magnet on TorBox. When [onlyIfCached] is false, TorBox will start a real download
     * for uncached hashes (needed for Sources / auto-play fallback of Download rows).
     */
    suspend fun createTorrent(
        apiKey: String,
        infoHash: String,
        onlyIfCached: Boolean,
    ): TorboxResult<Int> {
        val normalized = infoHash.trim()
        if (normalized.isBlank()) return TorboxResult.InvalidResponse
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("magnet", "magnet:?xt=urn:btih:$normalized")
            .addFormDataPart("add_only_if_cached", onlyIfCached.toString())
            .addFormDataPart("allow_zip", "false")
            .build()
        return post(apiKey, "v1/api/torrents/createtorrent", body).flatMap(TorboxResponseDecoder::torrentId)
    }

    suspend fun torrentFiles(apiKey: String, torrentId: Int): TorboxResult<List<TorboxFile>> =
        torrentSnapshot(apiKey, torrentId).map { it.files }

    suspend fun torrentSnapshot(apiKey: String, torrentId: Int): TorboxResult<TorboxTorrentSnapshot> =
        get(apiKey, "v1/api/torrents/mylist?id=$torrentId&bypass_cache=true")
            .flatMap { payload -> TorboxResponseDecoder.torrentSnapshot(payload, torrentId) }

    suspend fun listCloudTorrents(apiKey: String): TorboxResult<List<TorboxCloudItem>> =
        get(apiKey, "v1/api/torrents/mylist").flatMap(TorboxResponseDecoder::cloudItems)

    suspend fun downloadUrl(apiKey: String, torrentId: Int, fileId: Int): TorboxResult<String> =
        get(apiKey, TorboxRequestPaths.downloadLink(apiKey, torrentId, fileId))
            .flatMap(TorboxResponseDecoder::downloadUrl)

    private suspend fun get(apiKey: String, path: String): TorboxResult<JsonObject> = request(
        Request.Builder().url("https://api.torbox.app/$path").header("Authorization", "Bearer ${apiKey.trim()}").get().build(),
    )

    private suspend fun post(apiKey: String, path: String, body: RequestBody): TorboxResult<JsonObject> = request(
        Request.Builder().url("https://api.torbox.app/$path").header("Authorization", "Bearer ${apiKey.trim()}").post(body).build(),
    )

    private suspend fun request(request: Request): TorboxResult<JsonObject> = retryProviderCall(
        isRetryable = { result ->
            result is TorboxResult.NetworkFailure ||
                result is TorboxResult.HttpFailure && (result.statusCode == 408 || result.statusCode == 429 || result.statusCode >= 500)
        },
    ) {
        executeOnce(request)
    }

    private suspend fun executeOnce(request: Request): TorboxResult<JsonObject> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext TorboxResult.HttpFailure(response.code)
                val text = response.body?.string() ?: return@withContext TorboxResult.InvalidResponse
                val payload = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return@withContext TorboxResult.InvalidResponse
                TorboxResult.Success(payload)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.e("SlugYardTorbox", "provider request failed", failure)
            TorboxResult.NetworkFailure(failure)
        }
    }
}

internal suspend fun <T> retryProviderCall(
    isRetryable: (T) -> Boolean,
    operation: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        val result = operation()
        if (!isRetryable(result) || attempt >= 2) return result
        delay(250L shl attempt)
        attempt++
    }
}

/** Request URLs are safe to retain in diagnostics: authentication remains in the Bearer header. */
internal object TorboxRequestPaths {
    /**
     * TorBox documents that [requestdl] requires the API key as query `token=` (in addition to
     * Bearer). Omitting it returns success:false / empty data — surface as "no download URL".
     */
    fun downloadLink(apiKey: String, torrentId: Int, fileId: Int): String {
        val token = java.net.URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        return "v1/api/torrents/requestdl?token=$token&torrent_id=$torrentId&file_id=$fileId&zip_link=false&redirect=false&append_name=false"
    }
}

internal object TorboxResponseDecoder {
    fun cachedHashes(payload: JsonObject): TorboxResult<Set<String>> {
        val data = payload["data"] as? JsonObject ?: return TorboxResult.InvalidResponse
        return TorboxResult.Success(data.keys.map(String::lowercase).toSet())
    }

    fun torrentId(payload: JsonObject): TorboxResult<Int> {
        if ((payload["success"] as? JsonPrimitive)?.contentOrNull == "false") {
            return TorboxResult.InvalidResponse
        }
        val data = payload["data"] as? JsonObject ?: return TorboxResult.InvalidResponse
        val id = (data["torrent_id"] as? JsonPrimitive)?.intOrNull
            ?: (data["id"] as? JsonPrimitive)?.intOrNull
            ?: return TorboxResult.InvalidResponse
        return TorboxResult.Success(id)
    }

    fun torrentSnapshot(payload: JsonObject, fallbackId: Int): TorboxResult<TorboxTorrentSnapshot> {
        val data = when (val raw = payload["data"]) {
            is JsonObject -> raw
            is JsonArray -> raw.firstOrNull() as? JsonObject
            else -> null
        } ?: return TorboxResult.InvalidResponse
        val id = (data["id"] as? JsonPrimitive)?.intOrNull ?: fallbackId
        val filesArray = data["files"] as? JsonArray ?: JsonArray(emptyList())
        val files = filesArray.mapNotNull { raw ->
            val file = raw as? JsonObject ?: return@mapNotNull null
            val fileId = (file["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val name = listOf("short_name", "name", "absolute_path")
                .firstNotNullOfOrNull { key -> (file[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                ?: return@mapNotNull null
            TorboxFile(
                id = fileId,
                name = name.substringAfterLast('/'),
                mimeType = (file["mimetype"] as? JsonPrimitive)?.contentOrNull
                    ?: (file["mime_type"] as? JsonPrimitive)?.contentOrNull,
                sizeBytes = (file["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            )
        }
        val state = listOf("download_state", "state", "status")
            .firstNotNullOfOrNull { key -> (data[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        val progress = listOf("progress", "download_progress")
            .firstNotNullOfOrNull { key ->
                (data[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            }
        return TorboxResult.Success(
            TorboxTorrentSnapshot(
                id = id,
                files = files,
                downloadState = state,
                progress = progress,
            ),
        )
    }

    fun files(payload: JsonObject): TorboxResult<List<TorboxFile>> =
        torrentSnapshot(payload, fallbackId = -1).map { it.files }

    fun cloudItems(payload: JsonObject): TorboxResult<List<TorboxCloudItem>> {
        val data = payload["data"] as? JsonArray ?: return TorboxResult.InvalidResponse
        return TorboxResult.Success(data.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val id = (item["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            TorboxCloudItem(
                id = id,
                name = name,
                sizeBytes = (item["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                downloadState = (item["download_state"] as? JsonPrimitive)?.contentOrNull,
            )
        })
    }

    fun downloadUrl(payload: JsonObject): TorboxResult<String> {
        if ((payload["success"] as? JsonPrimitive)?.contentOrNull == "false") {
            return TorboxResult.InvalidResponse
        }
        val data = payload["data"]
        val url = when (data) {
            is JsonPrimitive -> data.contentOrNull
            is JsonObject -> listOf("url", "download", "link")
                .firstNotNullOfOrNull { key -> (data[key] as? JsonPrimitive)?.contentOrNull }
            else -> null
        }?.takeIf { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
            ?: return TorboxResult.InvalidResponse
        return TorboxResult.Success(url)
    }
}

internal inline fun <T, R> TorboxResult<T>.map(transform: (T) -> R): TorboxResult<R> = when (this) {
    is TorboxResult.Success -> TorboxResult.Success(transform(value))
    is TorboxResult.HttpFailure -> TorboxResult.HttpFailure(statusCode)
    TorboxResult.InvalidResponse -> TorboxResult.InvalidResponse
    is TorboxResult.NetworkFailure -> TorboxResult.NetworkFailure(cause)
}

internal inline fun <T, R> TorboxResult<T>.flatMap(transform: (T) -> TorboxResult<R>): TorboxResult<R> = when (this) {
    is TorboxResult.Success -> transform(value)
    is TorboxResult.HttpFailure -> TorboxResult.HttpFailure(statusCode)
    TorboxResult.InvalidResponse -> TorboxResult.InvalidResponse
    is TorboxResult.NetworkFailure -> TorboxResult.NetworkFailure(cause)
}
