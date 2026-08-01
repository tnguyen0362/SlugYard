package com.sluggyard.tv.core.streams

import com.sluggyard.tv.data.local.StreamBadgeSettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Default Fusion-style badge pack (Xperience white icons: resolution, quality, HDR, audio, codecs). */
const val DEFAULT_STREAM_BADGE_PACK_URL =
    "https://xperience-app.com/badges/215b4983-7eb0-48d2-a8ed-ef17425dcaf9.json"

@Singleton
class StreamBadgeImporter @Inject constructor(
    private val dataStore: StreamBadgeSettingsDataStore,
) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun importUrl(sourceUrl: String, activate: Boolean = true): StreamBadgeImportResult {
        val normalized = sourceUrl.trim()
        if (normalized.isBlank()) {
            return StreamBadgeImportResult.Error("Enter a badge JSON URL.")
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return StreamBadgeImportResult.Error("Badge URL must start with http:// or https://.")
        }
        return try {
            val payload = fetchText(normalized)
            val imported = StreamBadgeRulesParser.parse(normalized, payload)
            val current = dataStore.settings.first()
            val nextRules = current.rules.upsert(imported, activate = activate)
            dataStore.setSettings(current.copy(rules = nextRules))
            StreamBadgeImportResult.Success(nextRules)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StreamBadgeImportResult.Error(error.message?.takeIf { it.isNotBlank() } ?: "Badge import failed.")
        }
    }

    /**
     * Installs [DEFAULT_STREAM_BADGE_PACK_URL] when no badge pack is present yet.
     * Returns true when an import ran (success or failure still means we attempted).
     */
    suspend fun ensureDefaultPackInstalled(): StreamBadgeImportResult? {
        val current = dataStore.settings.first()
        if (current.rules.hasImport) return null
        return importUrl(DEFAULT_STREAM_BADGE_PACK_URL, activate = true)
    }

    suspend fun refreshActiveOrDefault(): StreamBadgeImportResult {
        val current = dataStore.settings.first()
        val url = current.rules.activeImport?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: DEFAULT_STREAM_BADGE_PACK_URL
        return importUrl(url, activate = true)
    }

    suspend fun clearAll() {
        val current = dataStore.settings.first()
        dataStore.setSettings(current.copy(rules = StreamBadgeRules()))
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Badge URL returned HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Badge URL returned an empty body.")
        }
    }
}
