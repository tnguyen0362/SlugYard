package com.sluggyard.tv.ui.app.data

import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.core.streamresolution.DebridService
import com.sluggyard.tv.ui.app.NetworkClient
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

/**
 * Configures a MediaFusion manifest that embeds debrid credentials.
 *
 * Tokens are only POSTed to [encryptBaseUrl] when that URL is explicitly configured
 * (self-hosted / first-party MediaFusion). The shared community host is never used
 * for encrypt-user-data, so plaintext debrid keys do not transit a third-party box.
 */
class MediaFusionConfigTransport(
    private val client: OkHttpClient = NetworkClient.create(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val encryptBaseUrl: String? = BuildConfig.MEDIAFUSION_ENCRYPT_BASE_URL,
) {
    suspend fun createManifestUrl(service: DebridService, apiKey: String): String = withContext(Dispatchers.IO) {
        val base = encryptBaseUrl?.trim()?.trimEnd('/').orEmpty()
        require(base.isNotBlank()) {
            "MediaFusion debrid linking requires MEDIAFUSION_ENCRYPT_BASE_URL (self-hosted MediaFusion)"
        }
        val provider = mediaFusionProviderPayload(service, apiKey)
        val body = buildJsonObject {
            put("streaming_provider", provider)
            put("streaming_providers", kotlinx.serialization.json.JsonArray(listOf(provider)))
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/encrypt-user-data")
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
                mediaFusionManifestUrl(base, decodeMediaFusionEncryptedPath(payload))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (_: Exception) {
            throw IllegalStateException("MediaFusion could not configure this provider")
        }
    }
}

class ProviderAddonConfigurator(
    private val mediaFusion: MediaFusionConfigTransport = MediaFusionConfigTransport(),
    private val aioStreams: AioStreamsConfigTransport? = null,
) {
    suspend fun configure(service: DebridService, apiKey: String): ConfiguredAddonUrls =
        ConfiguredAddonUrls(
            torrentioManifestUrl = buildTorrentioManifestUrl(service, apiKey),
            mediaFusionManifestUrl = try {
                mediaFusion.createManifestUrl(service, apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Fall back to keyless bootstrap; do not send tokens to a third-party encrypt host.
                null
            },
            cometManifestUrl = buildCometManifestUrl(service, apiKey),
            meteorManifestUrl = buildMeteorManifestUrl(service, apiKey),
            aioStreamsManifestUrl = try {
                aioStreams?.createManifestUrl(service, apiKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional host; skip when AIOSTREAMS_BASE_URL is empty or create/update fails.
                null
            },
        )
}
