@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sluggyard.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Audio sink wrapper that forces PCM fallback for passthrough formats (AC-3,
 * E-AC-3, TrueHD, DTS, ...) whenever playback speed is not 1.0.
 *
 * Most passthrough decoders cannot apply speed changes, so once a non-unit
 * speed is observed for a format that requires PCM, the sink stays in PCM mode
 * for the rest of the session to avoid mid-playback glitches.
 */
internal class PlaybackSpeedAwareAudioSink(
    sink: AudioSink,
    initialForcePcm: Boolean = false
) : ForwardingAudioSink(sink) {

    @Volatile private var playbackSpeed: Float = 1f
    @Volatile private var pcmLockedForSession: Boolean = initialForcePcm
    @Volatile private var currentInputFormat: Format? = null
    @Volatile private var listener: AudioSink.Listener? = null

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        val shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
        super.setPlaybackParameters(playbackParameters)
        if (shouldNotify) listener?.onAudioCapabilitiesChanged()
    }

    fun notifyAudioProcessingRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
    }

    override fun getFormatSupport(format: Format): Int =
        if (shouldRejectDirectPlayback(format)) AudioSink.SINK_FORMAT_UNSUPPORTED
        else super.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        if (shouldRejectDirectPlayback(format)) AudioOffloadSupport.DEFAULT_UNSUPPORTED
        else super.getFormatOffloadSupport(format)

    fun shouldForcePcmForFormat(format: Format): Boolean = shouldRejectDirectPlayback(format)

    private fun shouldRejectDirectPlayback(format: Format): Boolean =
        requiresPcmForSpeed(format) && (pcmLockedForSession || playbackSpeed != 1f)

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || speed == 1f || !requiresPcmForSpeed(format)) return false
        val wasForcing = pcmLockedForSession
        pcmLockedForSession = true
        return !wasForcing
    }

    private fun normalizeSpeed(speed: Float): Float = speed.takeIf { it > 0f } ?: 1f

    private fun requiresPcmForSpeed(format: Format): Boolean {
        val mime = format.sampleMimeType
        if (mime != null && (mime in PASSTHROUGH_MIME_TYPES || mime.startsWith("audio/vnd.dts"))) return true
        val codecs = format.codecs
        if (codecs != null) {
            return PASSTHROUGH_CODEC_TOKENS.any { token -> codecs.contains(token, ignoreCase = true) }
        }
        return false
    }

    private companion object {
        val PASSTHROUGH_MIME_TYPES = setOf(
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS
        )

        val PASSTHROUGH_CODEC_TOKENS = listOf("ac-3", "ac-4", "ec-3", "dts", "truehd", "dtshd")
    }
}