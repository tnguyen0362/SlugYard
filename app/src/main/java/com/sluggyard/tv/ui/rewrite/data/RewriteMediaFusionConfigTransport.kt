package com.sluggyard.tv.ui.rewrite.data

import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.rewrite.RewriteNetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RewriteMediaFusionConfigTransport(
    private val client: OkHttpClient = RewriteNetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun createManifestUrl(service: DebridService, apiKey: String): String = withContext(Dispatchers.IO) {
        val provider = mediaFusionProviderPayload(service, apiKey)
        val body = buildJsonObject {
            put("streaming_provider", provider)
            put("streaming_providers", kotlinx.serialization.json.JsonArray(listOf(provider)))
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$MediaFusionManifestHost/encrypt-user-data")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("MediaFusion rejected the provider configuration")
                }
                val payload = response.body?.string()
                    ?.let { json.parseToJsonElement(it).jsonObject }
                    ?: throw IllegalStateException("MediaFusion returned an empty configuration")
                mediaFusionManifestUrl(decodeMediaFusionEncryptedPath(payload))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (_: Exception) {
            throw IllegalStateException("MediaFusion could not configure this provider")
        }
    }
}

class RewriteProviderAddonConfigurator(
    private val mediaFusion: RewriteMediaFusionConfigTransport = RewriteMediaFusionConfigTransport(),
) {
    suspend fun configure(service: DebridService, apiKey: String): RewriteConfiguredAddonUrls =
        RewriteConfiguredAddonUrls(
            torrentioManifestUrl = buildTorrentioManifestUrl(service, apiKey),
            mediaFusionManifestUrl = try {
                mediaFusion.createManifestUrl(service, apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            },
        )
}
