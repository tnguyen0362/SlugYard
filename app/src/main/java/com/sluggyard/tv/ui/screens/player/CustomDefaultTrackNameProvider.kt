package com.sluggyard.tv.ui.screens.player

import android.content.res.Resources
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTrackNameProvider

/**
 * Track name provider that augments the default label with codec info
 * (TrueHD, DTS-HD, etc.) and the format's own label.
 *
 * Ported from Just (Video) Player's CustomDefaultTrackNameProvider.
 */
@UnstableApi
class CustomDefaultTrackNameProvider(resources: Resources) : DefaultTrackNameProvider(resources) {

    override fun getTrackName(format: Format): String {
        var name = super.getTrackName(format)

        val mime = format.sampleMimeType
        if (mime != null) {
            val codecLabel = formatNameFromMime(mime)
                ?: formatNameFromMime(format.codecs)
                ?: mime
            name += " ($codecLabel)"
        }

        val label = format.label
        if (label != null && !name.startsWith(label)) {
            name += " - $label"
        }
        return name
    }

    companion object {
        fun formatNameFromMime(mimeType: String?): String? {
            if (mimeType == null) return null
            return when (mimeType) {
                // Audio codecs
                MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
                MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_AC3 -> "AC-3"
                MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
                MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3-JOC"
                MimeTypes.AUDIO_AC4 -> "AC-4"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_MPEG_L2 -> "MP2"
                MimeTypes.AUDIO_VORBIS -> "Vorbis"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                MimeTypes.AUDIO_ALAC -> "ALAC"
                MimeTypes.AUDIO_WAV -> "WAV"
                MimeTypes.AUDIO_AMR -> "AMR"
                MimeTypes.AUDIO_AMR_NB -> "AMR-NB"
                MimeTypes.AUDIO_AMR_WB -> "AMR-WB"
                MimeTypes.AUDIO_IAMF -> "IAMF"
                MimeTypes.AUDIO_MPEGH_MHA1 -> "MPEG-H"
                MimeTypes.AUDIO_MPEGH_MHM1 -> "MPEG-H"
                // Video codecs
                MimeTypes.VIDEO_H264 -> "AVC"
                MimeTypes.VIDEO_H265 -> "HEVC"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP8 -> "VP8"
                MimeTypes.VIDEO_VP9 -> "VP9"
                MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                // Subtitle codecs
                "application/pgs", "application/x-pgs", "image/x-pgs" -> "PGS"
                MimeTypes.APPLICATION_SUBRIP, "application/x-subrip", "text/x-srt" -> "SRT"
                MimeTypes.TEXT_SSA, "text/x-ass", "text/x-ssa", "application/x-ass",
                "s_text/ass", "s_text/ssa", "ass", "ssa" -> "ASS"
                MimeTypes.TEXT_VTT, "text/vtt" -> "VTT"
                MimeTypes.APPLICATION_TTML -> "TTML"
                MimeTypes.APPLICATION_TX3G -> "TX3G"
                MimeTypes.APPLICATION_DVBSUBS -> "DVB"
                else -> {
                    val lower = mimeType.lowercase()
                    when {
                        "pgs" in lower || "hdmv" in lower -> "PGS"
                        "ass" in lower || "ssa" in lower -> "ASS"
                        "subrip" in lower || lower.endsWith("srt") -> "SRT"
                        "vtt" in lower -> "VTT"
                        "ttml" in lower -> "TTML"
                        "dvb" in lower -> "DVB"
                        else -> null
                    }
                }
            }
        }

        /** Human-readable channel layout description. */
        fun getChannelLayoutName(channelCount: Int): String? = when (channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> if (channelCount > 0) "${channelCount}ch" else null
        }
    }
}