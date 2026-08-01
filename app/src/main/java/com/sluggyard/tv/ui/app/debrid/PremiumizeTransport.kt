package com.sluggyard.tv.ui.app.debrid

import com.sluggyard.tv.ui.app.NetworkClient
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

data class PremiumizeFile(val path: String, val url: String, val sizeBytes: Long?)

/** Rewrite-owned Premiumize protocol adapter; it never depends on Retrofit or legacy settings. */
class PremiumizeTransport(
    private val client: OkHttpClient = NetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun validateCredential(key: String): TorboxResult<Unit> =
        get(key, "api/account/info").flatMap { payload ->
            if ((payload["status"] as? JsonPrimitive)?.contentOrNull.equals("success", true)) TorboxResult.Success(Unit)
            else TorboxResult.InvalidResponse
        }

    suspend fun checkCached(key: String, hashes: Set<String>): TorboxResult<Set<String>> {
        val normalized = hashes.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return TorboxResult.Success(emptySet())
        val body = FormBody.Builder().apply { normalized.forEach { add("items[]", it) } }.build()
        return request(Request.Builder().url("https://www.premiumize.me/api/cache/check").header("Authorization", bearer(key)).post(body).build())
            .flatMap { payload ->
                val response = payload["response"] as? JsonArray ?: return@flatMap TorboxResult.InvalidResponse
                if (response.size != normalized.size) return@flatMap TorboxResult.InvalidResponse
                TorboxResult.Success(normalized.filterIndexed { index, _ -> (response[index] as? JsonPrimitive)?.booleanOrNull == true }.map(String::lowercase).toSet())
            }
    }

    suspend fun directDownload(key: String, magnet: String): TorboxResult<List<PremiumizeFile>> {
        if (magnet.isBlank()) return TorboxResult.InvalidResponse
        val body = FormBody.Builder().add("src", magnet).build()
        return request(Request.Builder().url("https://www.premiumize.me/api/transfer/directdl").header("Authorization", bearer(key)).post(body).build())
            .flatMap(PremiumizeResponseDecoder::files)
    }

    private suspend fun get(key: String, path: String): TorboxResult<JsonObject> = request(
        Request.Builder().url("https://www.premiumize.me/$path").header("Authorization", bearer(key)).get().build(),
    )

    private fun bearer(key: String) = "Bearer ${key.trim()}"

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
                val payload = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
                    ?: return@withContext TorboxResult.InvalidResponse
                TorboxResult.Success(payload)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            TorboxResult.NetworkFailure(failure)
        }
    }
}

internal object PremiumizeResponseDecoder {
    fun files(payload: JsonObject): TorboxResult<List<PremiumizeFile>> {
        if ((payload["status"] as? JsonPrimitive)?.contentOrNull.equals("error", true)) return TorboxResult.InvalidResponse
        val content = payload["content"] as? JsonArray ?: return TorboxResult.InvalidResponse
        return TorboxResult.Success(content.mapNotNull { raw ->
            val file = raw as? JsonObject ?: return@mapNotNull null
            val path = (file["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = (file["link"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            PremiumizeFile(path, url, (file["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull())
        })
    }
}
