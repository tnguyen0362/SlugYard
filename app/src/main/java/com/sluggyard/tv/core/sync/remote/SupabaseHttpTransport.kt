package com.sluggyard.tv.core.sync.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

data class SupabaseHttpResponse(
    val code: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = code in 200..299
}

class SupabaseHttpTransport(
    private val client: OkHttpClient,
    baseUrl: String,
    private val anonKey: String,
) {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    init {
        val parsed = normalizedBaseUrl.toHttpUrlOrNull()
        require(parsed?.scheme == "https" && !parsed.host.isNullOrBlank()) {
            "Supabase URL must be a valid HTTPS URL"
        }
        require(anonKey.isNotBlank()) { "Supabase anon key is not configured" }
    }

    suspend fun execute(
        path: String,
        method: String,
        body: String? = null,
        accessToken: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): SupabaseHttpResponse = withContext(Dispatchers.IO) {
        require(path.startsWith('/')) { "Supabase path must be absolute" }
        require(!path.contains("apikey", ignoreCase = true)) { "Credentials cannot be query parameters" }
        require(!path.contains("access_token", ignoreCase = true)) { "Credentials cannot be query parameters" }

        val request = Request.Builder()
            .url(normalizedBaseUrl + path)
            .header("apikey", anonKey)
            .header("Accept", "application/json")
            .apply {
                accessToken?.takeIf(String::isNotBlank)?.let { token ->
                    header("Authorization", "Bearer $token")
                }
                headers.forEach { (name, value) ->
                    require(name.equals("Prefer", ignoreCase = true)) {
                        "Only PostgREST Prefer header is permitted"
                    }
                    header(name, value)
                }
                val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: if (method.uppercase(Locale.ROOT) in BODY_REQUIRED_METHODS) {
                        "".toRequestBody(JSON_MEDIA_TYPE)
                    } else {
                        null
                    }
                requestBody?.let {
                    header("Content-Type", "application/json")
                }
                method(method, requestBody)
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body
                SupabaseHttpResponse(
                    code = response.code,
                    body = readBody(responseBody),
                    headers = response.headers.toMultimap()
                        .mapKeys { (name, _) -> name.lowercase(Locale.ROOT) }
                        .mapValues { (_, values) -> values.lastOrNull().orEmpty() },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SupabaseHttpResponse(code = 0, body = null)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun readBody(body: okhttp3.ResponseBody?): String? {
            if (body == null) return null
            if (body.contentLength() > MAX_RESPONSE_BYTES) return null
            val source = body.source()
            return if (source.request(MAX_RESPONSE_BYTES + 1)) null else source.readUtf8()
        }
    }
}
