package com.sluggyard.tv.ui.app.debrid

import com.sluggyard.tv.ui.app.NetworkClient
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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class RealDebridFile(val id: Int, val path: String, val bytes: Long?)
data class RealDebridTorrent(val id: String, val status: String?, val files: List<RealDebridFile>, val links: List<String>)

/** Rewrite-owned Real-Debrid protocol adapter. Real-Debrid deliberately has no cache pre-check. */
class RealDebridTransport(
    private val client: OkHttpClient = NetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun validateCredential(key: String): TorboxResult<Unit> = get(key, "user").map { Unit }

    suspend fun addMagnet(key: String, magnet: String): TorboxResult<String> = request(key, "torrents/addMagnet", FormBody.Builder().add("magnet", magnet).build())
        .flatMap { payload ->
            (payload["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?.let { id -> TorboxResult.Success(id) } ?: TorboxResult.InvalidResponse
        }

    suspend fun torrentInfo(key: String, id: String): TorboxResult<RealDebridTorrent> = get(key, "torrents/info/$id").flatMap(RealDebridResponseDecoder::torrent)

    suspend fun selectFile(key: String, id: String, fileId: Int): TorboxResult<Unit> = callStatus(
        Request.Builder().url(base("torrents/selectFiles/$id")).header("Authorization", bearer(key))
            .post(FormBody.Builder().add("files", fileId.toString()).build()).build(),
    )

    suspend fun unrestrict(key: String, link: String): TorboxResult<String> = request(key, "unrestrict/link", FormBody.Builder().add("link", link).build()).flatMap {
        (it["download"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?.let { url -> TorboxResult.Success(url) } ?: TorboxResult.InvalidResponse
    }

    private suspend fun get(key: String, path: String): TorboxResult<JsonObject> = call(Request.Builder().url(base(path)).header("Authorization", bearer(key)).get().build())
    private suspend fun request(key: String, path: String, body: FormBody): TorboxResult<JsonObject> = call(Request.Builder().url(base(path)).header("Authorization", bearer(key)).post(body).build())
    private fun base(path: String) = "https://api.real-debrid.com/rest/1.0/$path"
    private fun bearer(key: String) = "Bearer ${key.trim()}"

    private suspend fun call(request: Request): TorboxResult<JsonObject> = retryProviderCall(
        isRetryable = { result ->
            result is TorboxResult.NetworkFailure ||
                result is TorboxResult.HttpFailure && (result.statusCode == 408 || result.statusCode == 429 || result.statusCode >= 500)
        },
    ) {
        executeOnce(request)
    }

    private suspend fun executeOnce(request: Request): TorboxResult<JsonObject> = withContext(Dispatchers.IO) {
        try { client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext TorboxResult.HttpFailure(response.code)
            val payload = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
                ?: return@withContext TorboxResult.InvalidResponse
            TorboxResult.Success(payload)
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            TorboxResult.NetworkFailure(failure)
        }
    }

    private suspend fun callStatus(request: Request): TorboxResult<Unit> = retryProviderCall(
        isRetryable = { result ->
            result is TorboxResult.NetworkFailure ||
                result is TorboxResult.HttpFailure && (result.statusCode == 408 || result.statusCode == 429 || result.statusCode >= 500)
        },
    ) {
        executeStatusOnce(request)
    }

    private suspend fun executeStatusOnce(request: Request): TorboxResult<Unit> = withContext(Dispatchers.IO) {
        try { client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 202) TorboxResult.Success(Unit)
            else TorboxResult.HttpFailure(response.code)
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            TorboxResult.NetworkFailure(failure)
        }
    }
}

internal object RealDebridResponseDecoder {
    fun torrent(payload: JsonObject): TorboxResult<RealDebridTorrent> {
        val id = (payload["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return TorboxResult.InvalidResponse
        val files = (payload["files"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val file = raw as? JsonObject ?: return@mapNotNull null
            val fileId = (file["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val path = (file["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            RealDebridFile(fileId, path, (file["bytes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull())
        }
        val links = (payload["links"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        return TorboxResult.Success(RealDebridTorrent(id, (payload["status"] as? JsonPrimitive)?.contentOrNull, files, links))
    }
}
