package com.sluggyard.tv.data.repository

import android.content.Context
import android.util.Log
import com.sluggyard.tv.R
import com.sluggyard.tv.core.network.NetworkResult
import com.sluggyard.tv.core.network.safeApiCall
import com.sluggyard.tv.core.logging.urlForLog
import com.sluggyard.tv.core.debrid.DebridStreamPresentation
import com.sluggyard.tv.core.debrid.LocalDebridAvailabilityService
import com.sluggyard.tv.data.mapper.toDomain
import com.sluggyard.tv.data.remote.api.AddonApi
import com.sluggyard.tv.domain.model.Addon
import com.sluggyard.tv.domain.model.AddonStreams
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.domain.model.enabledAddons
import com.sluggyard.tv.domain.repository.AddonRepository
import com.sluggyard.tv.domain.repository.StreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

/**
 * Discovers playable streams for a given video by fanning out across every
 * installed addon that declares a "stream" resource. Results are streamed
 * incrementally as each addon responds, with a final debrid-availability pass
 * applied once discovery completes.
 */
class StreamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val debridStreamPresentation: DebridStreamPresentation,
    private val localDebridAvailabilityService: LocalDebridAvailabilityService
) : StreamRepository {

    private enum class FailureKind { MISSING, REQUEST_FAILED }

    private data class AttemptFailure(
        val addonName: String,
        val kind: FailureKind,
        val detail: String
    )

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)

        try {
            val addons = addonRepository.getInstalledAddons().first().enabledAddons()
            val streamAddons = addons.filter { it.supportsStreamResource(type, videoId) }

            val attemptedNames = streamAddons.map { it.displayName }
            val failures = java.util.Collections.synchronizedList(mutableListOf<AttemptFailure>())
            val accumulated = mutableListOf<AddonStreams>()

            coroutineScope {
                val channel = Channel<AddonStreams>(Channel.UNLIMITED)
                val totalJobs = streamAddons.size
                val completed = java.util.concurrent.atomic.AtomicInteger(0)

                streamAddons.forEach { addon ->
                    launch {
                        try {
                            runAddonJob(addon, type, videoId, channel, failures)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            failures += AttemptFailure(
                                addonName = addon.displayName,
                                kind = FailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(R.string.stream_error_detail_addon_request_failed)
                            )
                        } finally {
                            if (completed.incrementAndGet() >= totalJobs) channel.close()
                        }
                    }
                }
                if (totalJobs == 0) channel.close()

                for (result in channel) {
                    val checking = localDebridAvailabilityService.markChecking(listOf(result)).firstOrNull() ?: result
                    mergePresented(accumulated, checking)
                    emit(NetworkResult.Success(accumulated.toList()))
                    Log.d(TAG, "Emitted ${accumulated.size} addon(s), latest: ${checking.addonName} with ${checking.streams.size} streams")
                }
            }

            // One batched availability pass after discovery so cached-torrent
            // auto-selection and badges can be applied without serializing the
            // first-results path.
            if (accumulated.isNotEmpty()) {
                val checkedGroups = localDebridAvailabilityService.annotateCachedAvailability(accumulated)
                accumulated.clear()
                checkedGroups.forEach { mergePresented(accumulated, it) }
                emit(NetworkResult.Success(accumulated.toList()))
            }

            if (accumulated.isEmpty()) {
                val errorMessage = buildAggregateMessage(
                    type = type,
                    id = videoId,
                    attemptedNames = attemptedNames,
                    failures = failures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: context.getString(R.string.stream_error_fetch_failed)))
        }
    }

    private suspend fun runAddonJob(
        addon: Addon,
        type: String,
        videoId: String,
        channel: Channel<AddonStreams>,
        failures: MutableList<AttemptFailure>
    ) {
        val streamsResult = getStreamsFromAddon(
            baseUrl = addon.baseUrl,
            type = type,
            videoId = videoId,
            installedAddon = addon
        )

        if (streamsResult is NetworkResult.Error) {
            failures += buildAddonFailure(addon, streamsResult)
            return
        }
        if (streamsResult !is NetworkResult.Success) return

        if (streamsResult.data.isNotEmpty()) {
            val named = streamsResult.data.map { it.copy(addonName = addon.displayName, addonLogo = addon.logo) }
            channel.send(AddonStreams(addonName = addon.displayName, addonLogo = addon.logo, streams = named))
            return
        }

        // Stream endpoint returned empty — try inline streams from the meta response.
        val inline = fetchInlineStreamsFromMeta(addon, type, videoId)
        if (inline.isNotEmpty()) {
            channel.send(AddonStreams(addonName = addon.displayName, addonLogo = addon.logo, streams = inline))
        } else {
            failures += buildMissingFailure(addon)
        }
    }

    private suspend fun mergePresented(accumulated: MutableList<AddonStreams>, result: AddonStreams) {
        val index = accumulated.indexOfFirst { it.addonName == result.addonName }
        if (index >= 0) {
            val existing = accumulated[index]
            val merged = existing.copy(streams = StreamMergeUtils.mergeStreams(existing.streams, result.streams))
            accumulated[index] = present(merged)
        } else {
            accumulated.add(present(result))
        }
    }

    private suspend fun present(result: AddonStreams): AddonStreams =
        debridStreamPresentation.apply(groups = listOf(result), includeBadgeMatches = false).firstOrNull() ?: result

    override suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> = getStreamsFromAddon(
        baseUrl = baseUrl,
        type = type,
        videoId = videoId,
        installedAddon = null
    )

    private fun buildResourceUrl(baseUrl: String, resource: String, type: String, id: String): String {
        val (basePath, baseQuery) = splitAddonBaseUrl(baseUrl)
        return "$basePath/$resource/${encodePathSegment(type)}/${encodePathSegment(id)}.json$baseQuery"
    }

    private suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String,
        installedAddon: Addon?
    ): NetworkResult<List<Stream>> {
        val streamUrl = buildResourceUrl(baseUrl, "stream", type, videoId)
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=${streamUrl.urlForLog()}")

        // Installed addons already carry their manifest name/logo; refetching
        // the manifest made every source lookup two sequential HTTP calls.
        val addonResult = if (installedAddon == null) addonRepository.fetchAddon(baseUrl) else null
        val addonName = installedAddon?.displayName ?: when (addonResult) {
            is NetworkResult.Success -> addonResult.data.displayName
            else -> context.getString(R.string.stream_addon_unknown)
        }
        val addonLogo = installedAddon?.logo ?: when (addonResult) {
            is NetworkResult.Success -> addonResult.data.logo
            else -> null
        }

        return when (val result = safeApiCall(context) { api.getStreams(streamUrl) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { it.toDomain(addonName, addonLogo) } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=${streamUrl.urlForLog()}")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "Streams failed addon=$addonName code=${result.code} message=${result.message} url=${streamUrl.urlForLog()}")
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    private fun Addon.supportsStreamResource(type: String, videoId: String): Boolean =
        StreamMergeUtils.supportsStreamResource(this, type, videoId)

    /**
     * Fetches meta for the given content and extracts inline streams from the
     * matching video entry. Returns an empty list when the addon doesn't
     * support meta or the video has no inline streams.
     */
    private suspend fun fetchInlineStreamsFromMeta(addon: Addon, type: String, videoId: String): List<Stream> {
        val metaId = StreamMergeUtils.deriveInlineMetaId(videoId)
        val metaUrl = buildResourceUrl(addon.baseUrl, "meta", type, metaId)
        Log.d(TAG, "Fetching inline streams via meta type=$type metaId=$metaId videoId=$videoId url=${metaUrl.urlForLog()}")
        return try {
            when (val result = safeApiCall(context) { api.getMeta(metaUrl) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta ?: return emptyList()
                    val matchingVideo = metaDto.videos?.firstOrNull { it.id == videoId }
                    matchingVideo?.streams?.mapNotNull { it.toDomain(addon.displayName, addon.logo) } ?: emptyList()
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch inline streams from meta for ${addon.displayName}: ${e.message}")
            emptyList()
        }
    }

    private fun buildMissingFailure(addon: Addon): AttemptFailure = AttemptFailure(
        addonName = addon.displayName,
        kind = FailureKind.MISSING,
        detail = context.getString(R.string.stream_error_detail_no_streams_for_id)
    )

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): AttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingFailure(addon)
        }
        val reason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(R.string.stream_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(R.string.stream_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(R.string.stream_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(R.string.stream_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(R.string.stream_error_detail_addon_request_failed)
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
    ): String? {
        if (attemptedNames.isEmpty()) {
            return context.getString(R.string.error_stream_no_supported_addon, type)
        }

        val tried = attemptedNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == FailureKind.MISSING }
        if (failures.isEmpty() || missingOnly) {
            return context.getString(R.string.error_stream_tried_none, tried, id, type)
        }

        val issueSummary = failures
            .filter { it.kind == FailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("; ") { "${it.addonName}: ${it.detail}" }

        return if (issueSummary.isBlank()) {
            context.getString(R.string.error_stream_tried_generic, tried, id, type)
        } else {
            context.getString(R.string.error_stream_tried_issues, tried, id, type, issueSummary)
        }
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

internal fun normalizeTmdbPluginType(type: String): String = when (type.lowercase()) {
    "series", "tv", "show" -> "tv"
    else -> type.lowercase()
}

internal fun cleanKitsuPluginId(videoId: String): String {
    val parts = videoId.split(":")
    return if (parts.size > 2 && parts.last().toIntOrNull() != null) {
        parts.dropLast(1).joinToString(":")
    } else {
        videoId
    }
}

internal fun String.canRunLocalPlugins(): Boolean =
    startsWith("kitsu:", ignoreCase = true) ||
        startsWith("anilist:", ignoreCase = true) ||
        startsWith("mal:", ignoreCase = true)

internal fun parseQualityValue(quality: String?): Int {
    if (quality == null) return -1
    val lower = quality.lowercase()
    return when {
        lower.contains("4k") || lower.contains("2160") -> 2160
        lower.contains("1080") -> 1080
        lower.contains("800") -> 800
        lower.contains("720") -> 720
        lower.contains("480") -> 480
        lower.contains("360") -> 360
        else -> -1
    }
}