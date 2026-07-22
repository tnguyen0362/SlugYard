package com.sluggyard.tv.data.repository

import android.content.Context
import android.util.Log
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.core.network.safeApiCall
import com.sluggyard.tv.data.remote.api.AddonApi
import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.Subtitle
import com.sluggyard.tv.domain.model.enabledAddons
import com.sluggyard.tv.domain.repository.SubtitleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Fan-out subtitle discovery across every installed addon that declares a
 * "subtitles" resource. Each addon is queried in parallel with a per-addon
 * timeout so a single slow addon can't stall the whole search.
 */
class SubtitleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepositoryImpl
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
        private const val PER_ADDON_TIMEOUT_MS = 20_000L
    }

    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalType(type)
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$id, videoId=$videoId")

        val addons = try {
            addonRepository.getInstalledAddons().first().enabledAddons()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed addons", e)
            return@withContext emptyList()
        }

        val subtitleAddons = addons.filter { addon ->
            addon.resources.any { resource ->
                isSubtitleResource(resource.name) && supportsType(resource, requestType, id)
            }
        }
        Log.d(TAG, "Found ${subtitleAddons.size} subtitle addons: ${subtitleAddons.map { it.name }}")
        if (subtitleAddons.isEmpty()) return@withContext emptyList()

        val total = subtitleAddons.size
        val completedCount = AtomicInteger(0)
        onProgress?.invoke(0, total, null)

        val result = coroutineScope {
            subtitleAddons.map { addon ->
                async {
                    val addonStartMs = System.currentTimeMillis()
                    val subtitles = withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                        fetchFromAddon(addon, type, id, videoId, videoHash, videoSize, filename)
                    }
                    onProgress?.invoke(completedCount.incrementAndGet(), total, addon.displayName)
                    if (subtitles == null) {
                        Log.w(TAG, "Subtitle fetch timed out for addon=${addon.name} after ${PER_ADDON_TIMEOUT_MS}ms")
                        emptyList()
                    } else {
                        Log.d(TAG, "Subtitle fetch done for addon=${addon.name} count=${subtitles.size} in ${System.currentTimeMillis() - addonStartMs}ms")
                        subtitles
                    }
                }
            }.awaitAll().flatten()
        }
        Log.d(TAG, "Subtitle fetch completed total=${result.size} fromAddons=${subtitleAddons.size} in ${System.currentTimeMillis() - startedAtMs}ms")
        result
    }

    private fun canonicalType(type: String): String =
        if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

    private fun supportsType(resource: com.sluggyard.tv.domain.model.AddonResource, type: String, id: String): Boolean {
        if (resource.types.isNotEmpty() && resource.types.none { it.equals(type, ignoreCase = true) }) {
            return false
        }
        val prefixes = resource.idPrefixes
        if (prefixes != null && prefixes.isNotEmpty()) {
            return prefixes.any { id.startsWith(it) }
        }
        return true
    }

    private fun isSubtitleResource(name: String): Boolean =
        name.equals("subtitles", ignoreCase = true) || name.equals("subtitle", ignoreCase = true)

    private suspend fun fetchFromAddon(
        addon: Addon,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> {
        val normalizedType = canonicalType(type)
        val actualId = if (normalizedType == "series" && videoId != null) videoId else id

        val (basePath, baseQuery) = splitBase(addon.baseUrl.trimEnd('/'))
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            "$basePath/subtitles/$normalizedType/$actualId/$extraParams.json$baseQuery"
        } else {
            "$basePath/subtitles/$normalizedType/$actualId.json$baseQuery"
        }
        Log.d(TAG, "Fetching subtitles from ${addon.name}: $subtitleUrl")

        return try {
            when (val result = safeApiCall(context) { api.getSubtitles(subtitleUrl) }) {
                is NetworkResult.Success -> result.data.subtitles?.mapNotNull { dto ->
                    Subtitle(
                        id = dto.id ?: "${dto.lang}-${dto.url.hashCode()}",
                        url = dto.url,
                        lang = dto.lang,
                        addonName = addon.displayName,
                        addonLogo = addon.logo
                    )
                } ?: emptyList()
                is NetworkResult.Error -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}: code=${result.code} message=${result.message}")
                    emptyList()
                }
                NetworkResult.Loading -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
            emptyList()
        }
    }

    private fun splitBase(baseUrl: String): Pair<String, String> {
        val queryStart = baseUrl.indexOf('?')
        return if (queryStart >= 0) {
            baseUrl.substring(0, queryStart).trimEnd('/') to baseUrl.substring(queryStart)
        } else {
            baseUrl to ""
        }
    }

    private fun buildExtraParams(videoHash: String?, videoSize: Long?, filename: String?): String {
        val params = mutableListOf<String>()
        videoHash?.let { params.add("videoHash=$it") }
        videoSize?.let { params.add("videoSize=$it") }
        filename?.let { params.add("filename=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        return if (params.isEmpty()) "" else params.joinToString("&")
    }
}