package com.sluggyard.tv.ui.screens.player

/**
 * Extracts subtitle cues (start time + plain text) from SRT/VTT payloads so the
 * player can sync external sidecar subtitles against the playback clock.
 *
 * Output is a list of [SubtitleSyncCue] with start times in milliseconds and
 * HTML/entities stripped down to plain text.
 */
internal object PlayerSubtitleCueParser {
    private val timestampRegex = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([.,](\d{1,3}))?""")

    fun parseFromText(rawText: String, sourceUrl: String): List<SubtitleSyncCue> {
        val normalized = rawText
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        return if (looksLikeVtt(normalized, sourceUrl)) parseVtt(normalized) else parseSrt(normalized)
    }

    private fun looksLikeVtt(text: String, sourceUrl: String): Boolean {
        val path = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".vtt") || path.endsWith(".webvtt") || text.trimStart().startsWith("WEBVTT")
    }

    private fun parseSrt(text: String): List<SubtitleSyncCue> {
        val cues = mutableListOf<SubtitleSyncCue>()
        for (block in text.split(Regex("""\n{2,}"""))) {
            val lines = block.lines().map(String::trim).filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var lineIndex = 0
            // Optional numeric cue index.
            if (lines[lineIndex].all(Char::isDigit) && lineIndex + 1 < lines.size) lineIndex++
            val timing = lines.getOrNull(lineIndex) ?: continue
            if (!timing.contains("-->")) continue
            val startMs = parseStartTimeMs(timing) ?: continue
            val cueText = normalizeCueText(lines.drop(lineIndex + 1).joinToString(" "))
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startMs, text = cueText)
        }
        return cues
    }

    private fun parseVtt(text: String): List<SubtitleSyncCue> {
        val lines = text.lines().map(String::trimEnd)
        val cues = mutableListOf<SubtitleSyncCue>()
        var cursor = 0

        while (cursor < lines.size) {
            val current = lines[cursor].trim()
            if (current.isBlank()) { cursor++; continue }
            if (current.startsWith("WEBVTT") || current.startsWith("NOTE")) { cursor++; continue }

            var timingLine = current
            var textStart = cursor + 1
            if (!timingLine.contains("-->")) {
                timingLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                textStart = cursor + 2
            }
            if (!timingLine.contains("-->")) { cursor++; continue }

            val startMs = parseStartTimeMs(timingLine) ?: run { cursor++; continue }

            val textParts = mutableListOf<String>()
            var i = textStart
            while (i < lines.size && lines[i].isNotBlank()) {
                textParts += lines[i].trim()
                i++
            }
            val cueText = normalizeCueText(textParts.joinToString(" "))
            if (cueText.isNotBlank()) cues += SubtitleSyncCue(startTimeMs = startMs, text = cueText)
            cursor = i + 1
        }
        return cues
    }

    private fun parseStartTimeMs(timingLine: String): Long? {
        val startToken = timingLine.substringBefore("-->").trim().substringBefore(' ')
        return parseTimestampMs(startToken)
    }

    private fun parseTimestampMs(raw: String): Long? {
        val match = timestampRegex.matchEntire(raw.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millisRaw = match.groupValues[5]
        val millis = when (millisRaw.length) {
            0 -> 0L
            1 -> "${millisRaw}00".toLong()
            2 -> "${millisRaw}0".toLong()
            else -> millisRaw.take(3).toLongOrNull() ?: 0L
        }
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
    }

    private fun normalizeCueText(text: String): String = text
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("""\s+"""), " ")
        .trim()
}