package com.sluggyard.tv.core.player

import com.sluggyard.tv.core.build.AppFeaturePolicy
import com.sluggyard.tv.data.local.StreamAutoPlayMode
import com.sluggyard.tv.data.local.StreamAutoPlaySource
import com.sluggyard.tv.domain.model.AddonStreams
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.domain.model.StreamDebridCacheState

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = streams.partition {
            it.streams.any { stream -> stream.isDirectDebrid() }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries
        val (addonEntries, pluginEntries) = remainingEntries.partition { it.addonName in addonRankByName }
        val orderedAddons = addonEntries.sortedBy { addonRankByName.getValue(it.addonName) }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    private fun isPlayable(stream: Stream): Boolean {
        // External URL streams (e.g. error pages, web links) are not playable.
        if (stream.isExternal()) return false
        when (stream.debridCacheStatus?.state) {
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED -> return false
            // Add-ons commonly emit their first result before the cache probe
            // completes. UNKNOWN is therefore a valid fallback, not a failure:
            // cached/direct links still win in selectBestScoredStream below,
            // while this lets a configured debrid resolver start immediately
            // instead of waiting for an update that some add-ons never send.
            StreamDebridCacheState.UNKNOWN,
            StreamDebridCacheState.CACHED,
            null -> Unit
        }
        return stream.getStreamUrl() != null || stream.isTorrent() || stream.isDirectDebrid()
    }



    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        contentContext: StreamScoringEngine.ContentContext? = null
    ): Stream? {
        if (streams.isEmpty()) return null

        val effectiveSource = if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY
        } else {
            source
        }

        val sourceScopedStreams = when (effectiveSource) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return null

        // Manual must always show the stream picker; a remembered binge group
        // may only refine an automatic choice.
        if (mode == StreamAutoPlayMode.MANUAL) return null

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = candidateStreams.firstOrNull { stream ->
                stream.behaviorHints?.bingeGroup == targetBingeGroup && isPlayable(stream)
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
            // When bingeGroupOnly is set (MANUAL mode with only binge-group
            // preference enabled), don't fall back to a non-matching stream —
            // return null so the caller shows the stream picker instead.
            if (bingeGroupOnly) return null
        }

        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> selectReadyFirst(candidateStreams)
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                if (userRegex == null) return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex("\\b(${exclusionWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = candidateStreams.filter { stream ->
                    if (!isPlayable(stream)) return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(stream.getStreamUrl().orEmpty())
                        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                if (matchingStreams.isEmpty()) return null
                selectReadyFirst(matchingStreams)
            }
            StreamAutoPlayMode.SCORED -> selectBestScoredStream(
                candidateStreams,
                contentContext ?: StreamScoringEngine.ContentContext(null, null, null, null, null, null)
            )
        }
    }

    /**
     * Retains add-on order while preferring sources that are ready immediately.
     * Unknown cache state stays a fallback because some providers never publish
     * a later cache-status update.
     */
    private fun selectReadyFirst(candidates: List<Stream>): Stream? {
        val playable = candidates.filter(::isPlayable)
        return playable.firstOrNull { stream ->
            stream.isDirectDebrid() || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED
        } ?: playable.firstOrNull()
    }

    /**
     * Select the best stream using multi-dimensional scoring.
     * Applies hard filters first, then scores remaining candidates.
     */
    private fun selectBestScoredStream(
        candidates: List<Stream>,
        ctx: StreamScoringEngine.ContentContext
    ): Stream? {
        val playableCandidates = candidates.filter { isPlayable(it) && !StreamScoringEngine.isHardDisqualified(it) }
        if (playableCandidates.isEmpty()) return null

        // Playback certainty comes first. A cached or already-resolved stream is
        // more valuable than a nominally higher-resolution torrent that can
        // still stall, fail, or wait for a debrid download.
        val instantCandidates = playableCandidates.filter { stream ->
            stream.isDirectDebrid() || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED
        }
        val selectionPool = instantCandidates.ifEmpty { playableCandidates }

        // Score all candidates and pick the highest
        return selectionPool
            .map { it to StreamScoringEngine.scoreStream(it, ctx) }
            .maxByOrNull { it.second.total }
            ?.first
    }
}
