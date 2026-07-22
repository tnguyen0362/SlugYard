package com.sluggyard.tv.ui.rewrite.debrid

import com.sluggyard.tv.ui.rewrite.RewriteNetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

data class RewriteTorboxFile(
    val id: Int,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

sealed interface RewriteTorboxResult<out T> {
    data class Success<T>(val value: T) : RewriteTorboxResult<T>
    data class HttpFailure(val statusCode: Int) : RewriteTorboxResult<Nothing>
    data object InvalidResponse : RewriteTorboxResult<Nothing>
    data class NetworkFailure(val cause: Throwable) : RewriteTorboxResult<Nothing>
}

/**
 * Rewrite-owned Torbox HTTP boundary. It has no dependency on old settings, resolvers, Retrofit,
 * or provider UI. File choice is intentionally left to the retained autofile picker boundary.
 */
class RewriteTorboxTransport(
    private val client: OkHttpClient = RewriteNetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun validateCredential(apiKey: String): RewriteTorboxResult<Unit> =
        get(apiKey, "v1/api/user/me").map { Unit }

    suspend fun checkCached(apiKey: String, hashes: Set<String>): RewriteTorboxResult<Set<String>> {
        val normalized = hashes.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return RewriteTorboxResult.Success(emptySet())
        val requestBody = normalized.joinToString(prefix = "{\"hashes\":[", postfix = "]}") { "\"$it\"" }
            .toRequestBody("application/json".toMediaType())
        return post(apiKey, "v1/api/torrents/checkcached?format=object", requestBody).flatMap { payload ->
            RewriteTorboxResponseDecoder.cachedHashes(payload)
        }
    }

    suspend fun createCachedTorrent(apiKey: String, infoHash: String): RewriteTorboxResult<Int> {
        val normalized = infoHash.trim()
        if (normalized.isBlank()) return RewriteTorboxResult.InvalidResponse
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("magnet", "magnet:?xt=urn:btih:$normalized")
            .addFormDataPart("add_only_if_cached", "true")
            .addFormDataPart("allow_zip", "false")
            .build()
        return post(apiKey, "v1/api/torrents/createtorrent", body).flatMap(RewriteTorboxResponseDecoder::torrentId)
    }

    suspend fun torrentFiles(apiKey: String, torrentId: Int): RewriteTorboxResult<List<RewriteTorboxFile>> =
        get(apiKey, "v1/api/torrents/mylist?id=$torrentId&bypass_cache=true").flatMap(RewriteTorboxResponseDecoder::files)

    suspend fun downloadUrl(apiKey: String, torrentId: Int, fileId: Int): RewriteTorboxResult<String> =
        get(apiKey, RewriteTorboxRequestPaths.downloadLink(torrentId, fileId))
            .flatMap(RewriteTorboxResponseDecoder::downloadUrl)

    private suspend fun get(apiKey: String, path: String): RewriteTorboxResult<JsonObject> = request(
        Request.Builder().url("https://api.torbox.app/$path").header("Authorization", "Bearer ${apiKey.trim()}").get().build(),
    )

    private suspend fun post(apiKey: String, path: String, body: RequestBody): RewriteTorboxResult<JsonObject> = request(
        Request.Builder().url("https://api.torbox.app/$path").header("Authorization", "Bearer ${apiKey.trim()}").post(body).build(),
    )

    private suspend fun request(request: Request): RewriteTorboxResult<JsonObject> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext RewriteTorboxResult.HttpFailure(response.code)
                val text = response.body?.string() ?: return@withContext RewriteTorboxResult.InvalidResponse
                val payload = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return@withContext RewriteTorboxResult.InvalidResponse
                RewriteTorboxResult.Success(payload)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RewriteTorboxResult.NetworkFailure(failure)
        }
    }
}

/** Request URLs are safe to retain in diagnostics: authentication remains in the Bearer header. */
internal object RewriteTorboxRequestPaths {
    fun downloadLink(torrentId: Int, fileId: Int): String =
        "v1/api/torrents/requestdl?torrent_id=$torrentId&file_id=$fileId&zip_link=false&redirect=false&append_name=false"
}

internal object RewriteTorboxResponseDecoder {
    fun cachedHashes(payload: JsonObject): RewriteTorboxResult<Set<String>> {
        val data = payload["data"] as? JsonObject ?: return RewriteTorboxResult.InvalidResponse
        return RewriteTorboxResult.Success(data.keys.map(String::lowercase).toSet())
    }

    fun torrentId(payload: JsonObject): RewriteTorboxResult<Int> {
        val data = payload["data"] as? JsonObject ?: return RewriteTorboxResult.InvalidResponse
        val id = (data["torrent_id"] as? JsonPrimitive)?.intOrNull
            ?: (data["id"] as? JsonPrimitive)?.intOrNull
            ?: return RewriteTorboxResult.InvalidResponse
        return RewriteTorboxResult.Success(id)
    }

    fun files(payload: JsonObject): RewriteTorboxResult<List<RewriteTorboxFile>> {
        val data = payload["data"] as? JsonObject ?: return RewriteTorboxResult.InvalidResponse
        val files = data["files"] as? JsonArray ?: return RewriteTorboxResult.InvalidResponse
        return RewriteTorboxResult.Success(files.mapNotNull { raw ->
            val file = raw as? JsonObject ?: return@mapNotNull null
            val id = (file["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val name = listOf("short_name", "name", "absolute_path")
                .firstNotNullOfOrNull { key -> (file[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                ?: return@mapNotNull null
            RewriteTorboxFile(
                id = id,
                name = name.substringAfterLast('/'),
                mimeType = (file["mimetype"] as? JsonPrimitive)?.contentOrNull,
                sizeBytes = (file["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            )
        })
    }

    fun downloadUrl(payload: JsonObject): RewriteTorboxResult<String> {
        val url = ((payload["data"] as? JsonPrimitive)?.contentOrNull ?: (payload["data"] as? JsonObject)?.get("url")?.let { it as? JsonPrimitive }?.contentOrNull)
            ?.takeIf(String::isNotBlank)
            ?: return RewriteTorboxResult.InvalidResponse
        return RewriteTorboxResult.Success(url)
    }
}

internal inline fun <T, R> RewriteTorboxResult<T>.map(transform: (T) -> R): RewriteTorboxResult<R> = when (this) {
    is RewriteTorboxResult.Success -> RewriteTorboxResult.Success(transform(value))
    is RewriteTorboxResult.HttpFailure -> RewriteTorboxResult.HttpFailure(statusCode)
    RewriteTorboxResult.InvalidResponse -> RewriteTorboxResult.InvalidResponse
    is RewriteTorboxResult.NetworkFailure -> RewriteTorboxResult.NetworkFailure(cause)
}

internal inline fun <T, R> RewriteTorboxResult<T>.flatMap(transform: (T) -> RewriteTorboxResult<R>): RewriteTorboxResult<R> = when (this) {
    is RewriteTorboxResult.Success -> transform(value)
    is RewriteTorboxResult.HttpFailure -> RewriteTorboxResult.HttpFailure(statusCode)
    RewriteTorboxResult.InvalidResponse -> RewriteTorboxResult.InvalidResponse
    is RewriteTorboxResult.NetworkFailure -> RewriteTorboxResult.NetworkFailure(cause)
}
