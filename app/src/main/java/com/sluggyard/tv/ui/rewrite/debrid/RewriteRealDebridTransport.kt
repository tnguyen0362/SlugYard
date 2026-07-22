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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class RewriteRealDebridFile(val id: Int, val path: String, val bytes: Long?)
data class RewriteRealDebridTorrent(val id: String, val status: String?, val files: List<RewriteRealDebridFile>, val links: List<String>)

/** Rewrite-owned Real-Debrid protocol adapter. Real-Debrid deliberately has no cache pre-check. */
class RewriteRealDebridTransport(
    private val client: OkHttpClient = RewriteNetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun validateCredential(key: String): RewriteTorboxResult<Unit> = get(key, "user").map { Unit }

    suspend fun addMagnet(key: String, magnet: String): RewriteTorboxResult<String> = request(key, "torrents/addMagnet", FormBody.Builder().add("magnet", magnet).build())
        .flatMap { payload ->
            (payload["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?.let { id -> RewriteTorboxResult.Success(id) } ?: RewriteTorboxResult.InvalidResponse
        }

    suspend fun torrentInfo(key: String, id: String): RewriteTorboxResult<RewriteRealDebridTorrent> = get(key, "torrents/info/$id").flatMap(RewriteRealDebridResponseDecoder::torrent)

    suspend fun selectFile(key: String, id: String, fileId: Int): RewriteTorboxResult<Unit> = callStatus(
        Request.Builder().url(base("torrents/selectFiles/$id")).header("Authorization", bearer(key))
            .post(FormBody.Builder().add("files", fileId.toString()).build()).build(),
    )

    suspend fun unrestrict(key: String, link: String): RewriteTorboxResult<String> = request(key, "unrestrict/link", FormBody.Builder().add("link", link).build()).flatMap {
        (it["download"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?.let { url -> RewriteTorboxResult.Success(url) } ?: RewriteTorboxResult.InvalidResponse
    }

    private suspend fun get(key: String, path: String): RewriteTorboxResult<JsonObject> = call(Request.Builder().url(base(path)).header("Authorization", bearer(key)).get().build())
    private suspend fun request(key: String, path: String, body: FormBody): RewriteTorboxResult<JsonObject> = call(Request.Builder().url(base(path)).header("Authorization", bearer(key)).post(body).build())
    private fun base(path: String) = "https://api.real-debrid.com/rest/1.0/$path"
    private fun bearer(key: String) = "Bearer ${key.trim()}"

    private suspend fun call(request: Request): RewriteTorboxResult<JsonObject> = withContext(Dispatchers.IO) {
        try { client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext RewriteTorboxResult.HttpFailure(response.code)
            val payload = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
                ?: return@withContext RewriteTorboxResult.InvalidResponse
            RewriteTorboxResult.Success(payload)
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RewriteTorboxResult.NetworkFailure(failure)
        }
    }

    private suspend fun callStatus(request: Request): RewriteTorboxResult<Unit> = withContext(Dispatchers.IO) {
        try { client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 202) RewriteTorboxResult.Success(Unit)
            else RewriteTorboxResult.HttpFailure(response.code)
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RewriteTorboxResult.NetworkFailure(failure)
        }
    }
}

internal object RewriteRealDebridResponseDecoder {
    fun torrent(payload: JsonObject): RewriteTorboxResult<RewriteRealDebridTorrent> {
        val id = (payload["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return RewriteTorboxResult.InvalidResponse
        val files = (payload["files"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val file = raw as? JsonObject ?: return@mapNotNull null
            val fileId = (file["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val path = (file["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            RewriteRealDebridFile(fileId, path, (file["bytes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull())
        }
        val links = (payload["links"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        return RewriteTorboxResult.Success(RewriteRealDebridTorrent(id, (payload["status"] as? JsonPrimitive)?.contentOrNull, files, links))
    }
}
