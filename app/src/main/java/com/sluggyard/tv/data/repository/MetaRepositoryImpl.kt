package com.sluggyard.tv.data.repository

import android.content.Context
import android.util.Log
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.core.network.safeApiCall
import com.sluggyard.tv.core.logging.urlForLog
import com.sluggyard.tv.data.mapper.toDomain
import com.sluggyard.tv.data.remote.api.AddonApi
import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.Meta
import com.sluggyard.tv.domain.model.enabledAddons
import com.sluggyard.tv.domain.repository.AddonRepository
import com.sluggyard.tv.domain.repository.MetaRepository
import com.sluggyard.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves [Meta] for a content id by querying Stremio addon "meta" resources.
 * Supports three lookup modes: a specific addon, the best addon across the
 * installed set, and a fast primary-addon shortcut. Results are cached in
 * memory and deduplicated in-flight to avoid duplicate network requests.
 */
@Singleton
class MetaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository
) : MetaRepository {
    companion object {
        private const val TAG = "MetaRepository"
    }

    private sealed class LookupResult {
        data class Found(val meta: Meta) : LookupResult()
        data object NotFound : LookupResult()
        /** The first viable candidate is the same addon that served the
         *  catalog, so the item already has its meta — no request needed. */
        data object SourceSufficient : LookupResult()
    }

    private enum class FailureKind { MISSING, REQUEST_FAILED }

    private data class AttemptFailure(
        val addonName: String,
        val kind: FailureKind,
        val detail: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // "addonBaseUrl|type:id" -> Meta. Keyed per addon so two addons serving
    // the same content id never overwrite each other.
    private val perAddonCache = ConcurrentHashMap<String, Meta>()
    private val allAddonCache = ConcurrentHashMap<String, Meta>()
    private val primaryAddonCache = ConcurrentHashMap<String, Meta>()

    private val inFlightPerAddon = ConcurrentHashMap<String, Deferred<Meta?>>()
    private val inFlightAllAddon = ConcurrentHashMap<String, Deferred<LookupResult>>()
    private val inFlightPrimary = ConcurrentHashMap<String, Deferred<Meta?>>()

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = addonMetaCacheKey(addonBaseUrl, type, id)
        perAddonCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val url = buildMetaUrl(addonBaseUrl, type, id)
        val meta = fetchAndCache(inFlightPerAddon, cacheKey, url) { perAddonCache[cacheKey] = it }
        emit(meta?.let { NetworkResult.Success(it) } ?: NetworkResult.Error(context.getString(R.string.error_meta_not_found)))
    }

    private suspend fun fetchAndCache(
        inFlightMap: ConcurrentHashMap<String, Deferred<Meta?>>,
        dedupeKey: String,
        url: String,
        onFetched: (Meta) -> Unit
    ): Meta? {
        val deferred = inFlightMap.getOrPut(dedupeKey) {
            scope.async {
                try {
                    val result = safeApiCall(context) { api.getMeta(url) }
                    val metaDto = (result as? NetworkResult.Success)?.data?.meta ?: return@async null
                    metaDto.toDomain(context.getString(R.string.episodes_episode)).also(onFetched)
                } finally {
                    inFlightMap.remove(dedupeKey)
                }
            }
        }
        return deferred.await()
    }

    override fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String?
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = "$type:$id"
        allAddonCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        val requestedType = type.trim()
        val inferredType = MetaCandidateSelector.inferCanonicalType(requestedType, id)
        val failures = mutableListOf<AttemptFailure>()
        val attemptedNames = linkedSetOf<String>()

        val prioritized = MetaCandidateSelector.selectPrioritizedCandidates(
            addons = addons,
            requestedType = requestedType,
            inferredType = inferredType,
            id = id
        )

        if (prioritized.isEmpty()) {
            // Last resort: try addons that declare the raw type (legacy behavior).
            val fallbackAddons = addons.filter { addon ->
                addon.rawTypes.any { it.equals(requestedType, ignoreCase = true) } &&
                    addon.resources.any { it.name == "meta" }
            }

            for (addon in fallbackAddons) {
                attemptedNames += addon.displayName
                val url = buildMetaUrl(addon.baseUrl, requestedType, id)
                when (val result = safeApiCall(context) { api.getMeta(url) }) {
                    is NetworkResult.Success -> {
                        val metaDto = result.data.meta
                        if (metaDto != null) {
                            val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                            allAddonCache[cacheKey] = meta
                            perAddonCache[addonMetaCacheKey(addon.baseUrl, requestedType, id)] = meta
                            emit(NetworkResult.Success(meta))
                            return@flow
                        } else {
                            failures += buildMissingFailure(addon)
                        }
                    }
                    is NetworkResult.Error -> failures += buildAddonFailure(addon, result)
                    NetworkResult.Loading -> { /* try next */ }
                }
            }

            val message = if (fallbackAddons.isEmpty()) {
                context.getString(R.string.error_meta_no_supported_addon, requestedType)
            } else {
                buildAggregateMessage(requestedType, id, attemptedNames.toList(), failures)
            }
            emit(NetworkResult.Error(message))
            return@flow
        }

        val deferred = inFlightAllAddon.getOrPut(cacheKey) {
            scope.async {
                try {
                    val normalizedSourceUrl = sourceAddonBaseUrl
                        ?.let { splitAddonBaseUrl(it).let { (p, q) -> "$p$q" } }

                    for ((addon, candidateType) in prioritized) {
                        // If this candidate is the same addon that provided the
                        // catalog data for this item, the item already carries
                        // its meta — return immediately without a request.
                        if (normalizedSourceUrl != null) {
                            val normalizedCandidateUrl = splitAddonBaseUrl(addon.baseUrl).let { (p, q) -> "$p$q" }
                            if (normalizedCandidateUrl == normalizedSourceUrl) {
                                Log.d(TAG, "Source addon matched, catalog meta is sufficient addon=${addon.name} type=$candidateType id=$id")
                                return@async LookupResult.SourceSufficient
                            }
                        }

                        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
                        Log.d(TAG, "Trying meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=${url.urlForLog()}")
                        when (val result = safeApiCall(context) { api.getMeta(url) }) {
                            is NetworkResult.Success -> {
                                val metaDto = result.data.meta
                                if (metaDto != null) {
                                    val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                                    allAddonCache[cacheKey] = meta
                                    perAddonCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)] = meta
                                    Log.d(TAG, "Meta fetch success addonId=${addon.id} type=$candidateType id=$id")
                                    return@async LookupResult.Found(meta)
                                }
                                Log.d(TAG, "Meta response was null addonId=${addon.id} type=$candidateType id=$id")
                            }
                            is NetworkResult.Error -> { /* try next */ }
                            NetworkResult.Loading -> { /* try next */ }
                        }
                    }
                    LookupResult.NotFound
                } finally {
                    inFlightAllAddon.remove(cacheKey)
                }
            }
        }

        when (val lookupResult = deferred.await()) {
            is LookupResult.Found -> emit(NetworkResult.Success(lookupResult.meta))
            is LookupResult.SourceSufficient -> emit(NetworkResult.Error("Source addon sufficient", NetworkResult.SOURCE_SUFFICIENT_CODE))
            is LookupResult.NotFound -> emit(
                NetworkResult.Error(buildAggregateMessage(requestedType, id, attemptedNames.toList(), failures))
            )
        }
    }

    override fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>> = flow {
        val cacheKey = "$type:$id"
        primaryAddonCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        val requestedType = type.trim()
        val inferredType = MetaCandidateSelector.inferCanonicalType(requestedType, id)
        val candidate = MetaCandidateSelector.selectPrimaryCandidate(
            addons = addons,
            requestedType = requestedType,
            inferredType = inferredType
        )

        if (candidate == null) {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_no_supported_addon, requestedType)))
            return@flow
        }

        val (addon, candidateType) = candidate
        val url = buildMetaUrl(addon.baseUrl, candidateType, id)
        Log.d(TAG, "Trying primary meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=${url.urlForLog()}")

        val meta = fetchAndCache(inFlightPrimary, cacheKey, url) {
            primaryAddonCache[cacheKey] = it
            perAddonCache[addonMetaCacheKey(addon.baseUrl, candidateType, id)] = it
        }
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(NetworkResult.Error(buildAggregateMessage(
                type = requestedType,
                id = id,
                attemptedNames = listOf(addon.displayName),
                failures = listOf(buildMissingFailure(addon))
            )))
        }
    }

    private fun buildMissingFailure(addon: Addon): AttemptFailure = AttemptFailure(
        addonName = addon.displayName,
        kind = FailureKind.MISSING,
        detail = context.getString(R.string.meta_error_detail_no_metadata_for_id)
    )

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): AttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingFailure(addon)
        }
        val reason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(R.string.meta_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(R.string.meta_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(R.string.meta_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(R.string.meta_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(R.string.meta_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return AttemptFailure(
            addonName = addon.displayName,
            kind = FailureKind.REQUEST_FAILED,
            detail = "$reason$httpSuffix"
        )
    }

    private fun buildAggregateMessage(
        type: String,
        id: String,
        attemptedNames: List<String>,
        failures: List<AttemptFailure>
    ): String {
        if (attemptedNames.isEmpty()) {
            return context.getString(R.string.error_meta_no_addon_for_id, id, type)
        }

        val tried = attemptedNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == FailureKind.MISSING }

        return if (missingOnly) {
            context.getString(R.string.error_meta_tried_none, tried, id, type)
        } else {
            val issueSummary = failures
                .filter { it.kind == FailureKind.REQUEST_FAILED }
                .distinctBy { it.addonName to it.detail }
                .take(3)
                .joinToString("; ") { "${it.addonName}: ${it.detail}" }
            if (issueSummary.isBlank()) {
                context.getString(R.string.error_meta_tried_generic, tried, id, type)
            } else {
                context.getString(R.string.error_meta_tried_issues, tried, id, type, issueSummary)
            }
        }
    }

    override fun clearCache() {
        perAddonCache.clear()
        allAddonCache.clear()
        primaryAddonCache.clear()
        inFlightPerAddon.clear()
        inFlightAllAddon.clear()
        inFlightPrimary.clear()
    }
}

/**
 * Splits an addon base URL into its path (trailing slashes trimmed) and query
 * portions. Shared by URL construction and cache keying so the two always
 * normalize equivalent base URLs identically.
 */
internal fun splitAddonBaseUrl(baseUrl: String): Pair<String, String> {
    val cleanBaseUrl = baseUrl.trimEnd('/')
    val queryStart = cleanBaseUrl.indexOf('?')
    return if (queryStart >= 0) {
        cleanBaseUrl.substring(0, queryStart).trimEnd('/') to cleanBaseUrl.substring(queryStart)
    } else {
        cleanBaseUrl to ""
    }
}

internal fun addonMetaCacheKey(addonBaseUrl: String, type: String, id: String): String {
    val (basePath, baseQuery) = splitAddonBaseUrl(addonBaseUrl)
    return "$basePath$baseQuery|$type:$id"
}

internal fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
    val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
    return "$basePath/meta/${encodePathSegment(type)}/${encodePathSegment(id)}.json$baseQuery"
}

internal fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")