package com.sluggyard.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.type.AssRenderType
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * libass-aware Matroska extractor.
 *
 * Extends the stock [MatroskaExtractor] to:
 *  - intercept MKV attachments and feed embedded fonts to [AssHandler];
 *  - forward video dimensions to [AssHandler] for correct ASS layout;
 *  - intercept ASS/SSA subtitle samples and forward dialogue events to
 *    [AssHandler] for native libass rendering (when render type != CUES).
 *
 * Internal helper classes [SlugYardAssSubtitleExtractorOutput] and
 * [SlugYardAssTrackOutput] delegate to the underlying extractor output / track
 * output so the rest of the pipeline is unaffected.
 */
@OptIn(UnstableApi::class)
internal class AssMatroskaExtractor(
    subtitleParserFactory: SubtitleParser.Factory,
    private val assHandler: AssHandler,
    flags: Int = 0
) : MatroskaExtractor(subtitleParserFactory, flags) {

    private var pendingAttachmentName: String? = null
    private var pendingAttachmentMime: String? = null

    /** Exposes the stock extractor's private subtitle sample buffer via reflection. */
    internal val subtitleSample: ParsableByteArray =
        subtitleSampleField.get(this) as ParsableByteArray

    override fun getElementType(id: Int): Int = when (id) {
        ID_ATTACHMENTS -> EbmlProcessor.ELEMENT_TYPE_MASTER
        ID_ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
        ID_FILE_NAME -> EbmlProcessor.ELEMENT_TYPE_STRING
        ID_FILE_MIME_TYPE -> EbmlProcessor.ELEMENT_TYPE_STRING
        ID_FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
        else -> super.getElementType(id)
    }

    override fun isLevel1Element(id: Int): Boolean =
        super.isLevel1Element(id) || id == ID_ATTACHMENTS

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        when (id) {
            ID_EBML -> installAssExtractorOutputIfNeeded(contentPosition, contentSize)
            ID_ATTACHED_FILE -> clearPendingAttachment()
            else -> super.startMasterElement(id, contentPosition, contentSize)
        }
    }

    override fun endMasterElement(id: Int) {
        when (id) {
            ID_VIDEO -> {
                val track = getCurrentTrack(ID_VIDEO)
                assHandler.setVideoSize(track.width, track.height)
                super.endMasterElement(ID_VIDEO)
            }
            ID_ATTACHED_FILE -> clearPendingAttachment()
            else -> super.endMasterElement(id)
        }
    }

    override fun stringElement(id: Int, value: String) {
        when (id) {
            ID_FILE_NAME -> pendingAttachmentName = value
            ID_FILE_MIME_TYPE -> pendingAttachmentMime = value
            else -> super.stringElement(id, value)
        }
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        when (id) {
            ID_FILE_DATA -> handleAttachmentData(contentSize, input)
            else -> super.binaryElement(id, contentSize, input)
        }
    }

    private fun installAssExtractorOutputIfNeeded(contentPosition: Long, contentSize: Long) {
        if (assHandler.renderType != AssRenderType.CUES) {
            val current = extractorOutputField.get(this) as ExtractorOutput
            if (current !is SlugYardAssSubtitleExtractorOutput) {
                extractorOutputField.set(
                    this,
                    SlugYardAssSubtitleExtractorOutput(current, assHandler, this)
                )
            }
        }
        super.startMasterElement(ID_EBML, contentPosition, contentSize)
    }

    private fun handleAttachmentData(contentSize: Int, input: ExtractorInput) {
        val name = requireNotNull(pendingAttachmentName)
        val mime = requireNotNull(pendingAttachmentMime)
        if (mime in FONT_MIME_TYPES) {
            val data = ByteArray(contentSize)
            input.readFully(data, 0, contentSize)
            assHandler.addFont(name, data)
        } else {
            input.skipFully(contentSize)
        }
    }

    private fun clearPendingAttachment() {
        pendingAttachmentName = null
        pendingAttachmentMime = null
    }

    private companion object {
        const val ID_EBML = 0x1A45DFA3
        const val ID_VIDEO = 0xE0
        const val ID_ATTACHMENTS = 0x1941A469
        const val ID_ATTACHED_FILE = 0x61A7
        const val ID_FILE_NAME = 0x466E
        const val ID_FILE_MIME_TYPE = 0x4660
        const val ID_FILE_DATA = 0x465C

        val FONT_MIME_TYPES = listOf(
            "font/ttf", "font/otf", "font/sfnt", "font/woff", "font/woff2",
            "application/font-sfnt", "application/font-woff",
            "application/x-truetype-font", "application/vnd.ms-opentype",
            "application/x-font-ttf"
        )

        // Reflection handles into the stock MatroskaExtractor's private fields.
        val extractorOutputField = MatroskaExtractor::class.java
            .getDeclaredField("extractorOutput")
            .apply { isAccessible = true }

        val subtitleSampleField = MatroskaExtractor::class.java
            .getDeclaredField("subtitleSample")
            .apply { isAccessible = true }
    }
}

@OptIn(UnstableApi::class)
private class SlugYardAssSubtitleExtractorOutput(
    private val delegate: ExtractorOutput,
    private val assHandler: AssHandler,
    private val extractor: AssMatroskaExtractor
) : ExtractorOutput by delegate {
    override fun track(id: Int, type: Int): TrackOutput =
        if (type == C.TRACK_TYPE_TEXT) {
            SlugYardAssTrackOutput(delegate.track(id, type), assHandler, extractor)
        } else {
            delegate.track(id, type)
        }
}

@OptIn(UnstableApi::class)
private class SlugYardAssTrackOutput(
    private val delegate: TrackOutput,
    private val assHandler: AssHandler,
    private val extractor: AssMatroskaExtractor
) : TrackOutput by delegate {

    private var isAssTrack = false
    private var trackId: String? = null

    override fun format(format: Format) {
        if (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA) {
            isAssTrack = true
            trackId = format.id
        }
        delegate.format(format)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAssTrack && timeUs != C.TIME_UNSET) {
            val sample = extractor.subtitleSample
            val sampleLimit = sample.limit()
            val endTokenIndex = findCommaToken(sample.data, tokenNumber = 1, limit = sampleLimit)
            val lineTokenIndex = findCommaToken(sample.data, tokenNumber = 2, limit = sampleLimit)
            if (endTokenIndex > 0 && lineTokenIndex > endTokenIndex) {
                val rawDuration = sample.data.decodeToString(endTokenIndex, lineTokenIndex - 1)
                val durationUs = parseTimecodeUs(rawDuration)
                if (durationUs != C.TIME_UNSET) {
                    val dialogue = sample.data.dialoguePayload(offset = lineTokenIndex, limit = sampleLimit)
                    assHandler.readTrackDialogue(
                        trackId = trackId,
                        start = timeUs / 1000,
                        duration = durationUs / 1000,
                        data = dialogue,
                        offset = 0,
                        length = dialogue.size
                    )
                }
            }
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun parseTimecodeUs(timeString: String): Long {
        val matcher = SSA_TIMECODE_PATTERN.matcher(timeString.trim { it <= ' ' })
        if (!matcher.matches()) return C.TIME_UNSET
        var us = Util.castNonNull(matcher.group(1)).toLong() * 60 * 60 * C.MICROS_PER_SECOND
        us += Util.castNonNull(matcher.group(2)).toLong() * 60 * C.MICROS_PER_SECOND
        us += Util.castNonNull(matcher.group(3)).toLong() * C.MICROS_PER_SECOND
        us += Util.castNonNull(matcher.group(4)).toLong() * 10_000
        return us
    }

    private fun findCommaToken(array: ByteArray, tokenNumber: Int, limit: Int): Int {
        if (tokenNumber == 0) return 0
        var tokensFound = 0
        for (i in 0 until limit) {
            if (array[i] == COMMA && ++tokensFound == tokenNumber) return i + 1
        }
        return 0
    }

    private fun ByteArray.dialoguePayload(offset: Int, limit: Int): ByteArray {
        if (offset >= limit) return EMPTY_BYTE_ARRAY
        if (looksLikeZlib(offset, limit)) {
            maybeInflate(offset, size - offset)?.let { return it }
        }
        val boundedLimit = limit.coerceIn(offset, size)
        return copyOfRange(offset, boundedLimit)
    }

    private fun ByteArray.looksLikeZlib(offset: Int, limit: Int): Boolean {
        if (limit - offset < 2) return false
        val cmf = this[offset].toInt() and 0xFF
        val flg = this[offset + 1].toInt() and 0xFF
        return cmf and 0x0F == 8 && ((cmf shl 8) + flg) % 31 == 0
    }

    private fun ByteArray.maybeInflate(offset: Int, length: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(this, offset, length)
            val output = ByteArrayOutputStream(length * 4)
            val buffer = ByteArray(INFLATE_BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                } else {
                    break
                }
            }
            val inflated = output.toByteArray()
            if (inflater.finished() && inflated.isNotEmpty()) inflated else null
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private companion object {
        val SSA_TIMECODE_PATTERN: Pattern = Pattern.compile("""(?:(\d+):)?(\d+):(\d+)[:.](\d+)""")
        const val COMMA: Byte = 44
        const val INFLATE_BUFFER_SIZE = 4096
        val EMPTY_BYTE_ARRAY = ByteArray(0)
    }
}