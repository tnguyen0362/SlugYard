package com.sluggyard.tv.core.player

import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * Sanitized, engine-facing playback input. It contains launch identity and
 * playback metadata only; catalog and remote orchestration stay outside the
 * retained runtime.
 */
data class PlaybackLaunchRequest(
    val streamUrl: String,
    val title: String,
    val streamName: String?,
    val year: String?,
    val headersJson: String?,
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val initialSeason: Int?,
    val initialEpisode: Int?,
    val initialEpisodeTitle: String?,
    val startPositionMs: Long?,
    val bingeGroup: String?,
    val filename: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val startFromBeginning: Boolean,
    val addonName: String?,
    val addonLogo: String?,
    val streamDescription: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val sourcesJson: String?,
    val contentGenres: String?,
    val contentLanguage: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?,
    val launchStartedAtMs: Long?,
    /** Series/catalog id when [contentId] is an episode-shaped playback id (tt…:S:E). */
    val parentId: String? = null,
    val parentType: String? = null,
) {
    val torrentTrackers: List<String>
        get() {
            val raw = sourcesJson ?: return emptyList()
            return runCatching {
                Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                    element.jsonPrimitive.contentOrNull
                        ?.takeIf { it.startsWith(TRACKER_PREFIX) }
                        ?.removePrefix(TRACKER_PREFIX)
                }
            }.getOrDefault(emptyList())
        }

    companion object {
        private const val TRACKER_PREFIX = "tracker:"

        fun from(savedStateHandle: SavedStateHandle): PlaybackLaunchRequest {
            val decode: (String) -> String? = { key ->
                savedStateHandle.get<String>(key)
                    ?.takeIf(String::isNotEmpty)
                    ?.let { value -> runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value) }
            }
            val nonEmpty: (String) -> String? = { key ->
                savedStateHandle.get<String>(key)?.takeIf(String::isNotEmpty)
            }

            return PlaybackLaunchRequest(
                streamUrl = savedStateHandle.get<String>("streamUrl") ?: "",
                title = decode("title") ?: "",
                streamName = decode("streamName"),
                year = decode("year"),
                headersJson = decode("headers"),
                contentId = nonEmpty("contentId"),
                contentType = nonEmpty("contentType"),
                contentName = decode("contentName"),
                poster = decode("posterUrl") ?: decode("poster"),
                backdrop = decode("backdrop") ?: decode("posterUrl") ?: decode("poster"),
                logo = decode("logo"),
                videoId = nonEmpty("videoId"),
                initialSeason = savedStateHandle.get<String>("season")?.toIntOrNull(),
                initialEpisode = savedStateHandle.get<String>("episode")?.toIntOrNull(),
                initialEpisodeTitle = decode("episodeTitle"),
                startPositionMs = savedStateHandle.get<String>("startPositionMs")?.toLongOrNull(),
                bingeGroup = decode("bingeGroup"),
                filename = decode("filename"),
                videoHash = nonEmpty("videoHash"),
                videoSize = savedStateHandle.get<String>("videoSize")?.toLongOrNull(),
                startFromBeginning = savedStateHandle.get<String>("startFromBeginning")
                    ?.toBooleanStrictOrNull() == true,
                addonName = decode("addonName"),
                addonLogo = decode("addonLogo"),
                streamDescription = decode("streamDescription"),
                infoHash = nonEmpty("infoHash"),
                fileIdx = savedStateHandle.get<String>("fileIdx")?.toIntOrNull(),
                sourcesJson = decode("sources"),
                contentGenres = decode("contentGenres"),
                contentLanguage = decode("contentLanguage"),
                rememberedAudioLanguage = decode("rememberedAudioLanguage"),
                rememberedAudioName = decode("rememberedAudioName"),
                launchStartedAtMs = savedStateHandle.get<String>("launchStartedAtMs")?.toLongOrNull(),
                parentId = nonEmpty("parentId"),
                parentType = nonEmpty("parentType"),
            )
        }
    }
}
