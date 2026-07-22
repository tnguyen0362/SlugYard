package com.sluggyard.tv.ui.rewrite.debrid

import com.sluggyard.tv.ui.rewrite.RewriteNetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class RewritePremiumizeFile(val path: String, val url: String, val sizeBytes: Long?)

/** Rewrite-owned Premiumize protocol adapter; it never depends on Retrofit or legacy settings. */
class RewritePremiumizeTransport(
    private val client: OkHttpClient = RewriteNetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun validateCredential(key: String): RewriteTorboxResult<Unit> =
        get(key, "api/account/info").flatMap { payload ->
            if ((payload["status"] as? JsonPrimitive)?.contentOrNull.equals("success", true)) RewriteTorboxResult.Success(Unit)
            else RewriteTorboxResult.InvalidResponse
        }

    suspend fun checkCached(key: String, hashes: Set<String>): RewriteTorboxResult<Set<String>> {
        val normalized = hashes.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return RewriteTorboxResult.Success(emptySet())
        val body = FormBody.Builder().apply { normalized.forEach { add("items[]", it) } }.build()
        return request(Request.Builder().url("https://www.premiumize.me/api/cache/check").header("Authorization", bearer(key)).post(body).build())
            .flatMap { payload ->
                val response = payload["response"] as? JsonArray ?: return@flatMap RewriteTorboxResult.InvalidResponse
                if (response.size != normalized.size) return@flatMap RewriteTorboxResult.InvalidResponse
                RewriteTorboxResult.Success(normalized.filterIndexed { index, _ -> (response[index] as? JsonPrimitive)?.booleanOrNull == true }.map(String::lowercase).toSet())
            }
    }

    suspend fun directDownload(key: String, magnet: String): RewriteTorboxResult<List<RewritePremiumizeFile>> {
        if (magnet.isBlank()) return RewriteTorboxResult.InvalidResponse
        val body = FormBody.Builder().add("src", magnet).build()
        return request(Request.Builder().url("https://www.premiumize.me/api/transfer/directdl").header("Authorization", bearer(key)).post(body).build())
            .flatMap(RewritePremiumizeResponseDecoder::files)
    }

    private suspend fun get(key: String, path: String): RewriteTorboxResult<JsonObject> = request(
        Request.Builder().url("https://www.premiumize.me/$path").header("Authorization", bearer(key)).get().build(),
    )

    private fun bearer(key: String) = "Bearer ${key.trim()}"

    private suspend fun request(request: Request): RewriteTorboxResult<JsonObject> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext RewriteTorboxResult.HttpFailure(response.code)
                val payload = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
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

internal object RewritePremiumizeResponseDecoder {
    fun files(payload: JsonObject): RewriteTorboxResult<List<RewritePremiumizeFile>> {
        if ((payload["status"] as? JsonPrimitive)?.contentOrNull.equals("error", true)) return RewriteTorboxResult.InvalidResponse
        val content = payload["content"] as? JsonArray ?: return RewriteTorboxResult.InvalidResponse
        return RewriteTorboxResult.Success(content.mapNotNull { raw ->
            val file = raw as? JsonObject ?: return@mapNotNull null
            val path = (file["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = (file["link"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            RewritePremiumizeFile(path, url, (file["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull())
        })
    }
}
