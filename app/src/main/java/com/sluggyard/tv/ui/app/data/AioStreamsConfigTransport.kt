package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.app.NetworkClient
import com.sluggyard.tv.ui.app.debrid.AioStreamsSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Creates or updates an AIOStreams user from the credential-free SlugYard template, injecting the
 * active debrid key at runtime.
 *
 * Tokens are only POSTed when [baseUrl] is explicitly configured (self-hosted / opted-in host).
 * Empty [BuildConfig.AIOSTREAMS_BASE_URL] means AIOStreams stays out of the curated pack.
 */
class AioStreamsConfigTransport(
    private val templateJson: String,
    private val sessions: AioStreamsSessionStore,
    private val client: OkHttpClient = NetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: String? = BuildConfig.AIOSTREAMS_BASE_URL,
    private val configAccessKey: String? = BuildConfig.AIOSTREAMS_CONFIG_ACCESS_KEY,
) {
    suspend fun createManifestUrl(service: DebridService, apiKey: String): String = withContext(Dispatchers.IO) {
        val base = baseUrl?.trim()?.trimEnd('/').orEmpty()
        require(base.isNotBlank()) {
            "AIOStreams linking requires AIOSTREAMS_BASE_URL (self-hosted or opted-in host)"
        }
        val root = json.parseToJsonElement(templateJson).jsonObject
        val config = injectAioStreamsCredentials(
            aioStreamsConfigFromTemplate(root),
            service,
            apiKey,
            accessKey = configAccessKey,
        )
        val existing = sessions.load()?.takeIf { it.baseUrl.equals(base, ignoreCase = true) }
        if (existing != null) {
            val updated = tryUpdate(base, existing, config)
            if (updated) {
                return@withContext aioStreamsManifestUrl(base, existing.uuid, existing.encryptedPassword)
            }
            sessions.clear()
        }
        createUser(base, config)
    }

    private fun tryUpdate(
        base: String,
        session: AioStreamsSessionStore.Session,
        config: kotlinx.serialization.json.JsonObject,
    ): Boolean {
        val body = buildJsonObject { put("config", config) }
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/api/v1/user")
            .header("Authorization", Credentials.basic(session.uuid, session.password))
            .put(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun createUser(
        base: String,
        config: kotlinx.serialization.json.JsonObject,
    ): String {
        val password = UUID.randomUUID().toString().replace("-", "")
        val body = buildJsonObject {
            put("config", config)
            put("password", password)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/api/v1/user")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("AIOStreams rejected the user configuration")
                }
                val payload = response.body?.string()
                    ?.let { json.parseToJsonElement(it).jsonObject }
                    ?: throw IllegalStateException("AIOStreams returned an empty configuration")
                val (uuid, encryptedPassword) = decodeAioStreamsCreateResponse(payload)
                sessions.save(
                    AioStreamsSessionStore.Session(
                        uuid = uuid,
                        password = password,
                        encryptedPassword = encryptedPassword,
                        baseUrl = base,
                    ),
                )
                return aioStreamsManifestUrl(base, uuid, encryptedPassword)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (_: Exception) {
            throw IllegalStateException("AIOStreams could not configure this provider")
        }
    }
}
