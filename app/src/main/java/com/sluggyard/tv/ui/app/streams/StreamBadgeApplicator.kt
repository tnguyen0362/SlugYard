package com.sluggyard.tv.ui.app.streams

import com.sluggyard.tv.core.streams.CompiledStreamBadgeFilter
import com.sluggyard.tv.core.streams.StreamBadgeMatcher
import com.sluggyard.tv.core.streams.StreamBadgeRules
import com.sluggyard.tv.domain.model.Stream
import com.sluggyard.tv.domain.model.StreamBehaviorHints

/** Applies Fusion-style badge matching to rewrite Sources rows. */
object StreamBadgeApplicator {
    fun apply(
        groups: List<StreamGroup>,
        rules: StreamBadgeRules,
    ): List<StreamGroup> {
        val filters = StreamBadgeMatcher.compile(rules)
        if (filters.isEmpty()) return groups
        return groups.map { group ->
            val content = group.state as? StreamGroupState.Content ?: return@map group
            group.copy(
                state = StreamGroupState.Content(
                    content.streams.map { candidate -> candidate.withMatchedBadges(filters) },
                ),
            )
        }
    }

    fun StreamCandidate.withMatchedBadges(
        filters: List<CompiledStreamBadgeFilter>,
    ): StreamCandidate {
        if (filters.isEmpty()) return this
        val matched = StreamBadgeMatcher.matchedBadges(toMatchStream(), filters)
        return if (matched.isEmpty()) this else copy(badges = matched)
    }

    private fun StreamCandidate.toMatchStream(): Stream = Stream(
        name = title,
        title = detailLabel ?: title,
        description = listOfNotNull(streamDescription, metadataText, filename).joinToString("\n"),
        url = directUrl,
        ytId = null,
        infoHash = infoHash,
        fileIdx = fileIndex,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null,
            videoHash = videoHash,
            videoSize = videoSizeBytes,
            filename = filename,
        ),
        addonName = sourceLabel,
        addonLogo = null,
    )
}
