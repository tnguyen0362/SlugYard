package com.sluggyard.tv.core.player

import org.json.JSONObject

/**
 * Snapshot of the most recent playback's diagnostic data.
 *
 * Populated during initializePlayer and onRenderedFirstFrame / onPlayerError,
 * then persisted to DataStore so users can take a photo of Settings without
 * ADB to report which DV decision branch was taken on their device.
 *
 * All fields are nullable / have safe defaults so partial data is okay
 * (e.g. if playback errored before first frame, conversion counts will be 0).
 */
data class LastPlaybackDiagnostics(
    val timestampMs: Long = 0L,
    val host: String = "",
    val streamUrl: String? = null,
    val headersJson: String? = null,
    val videoBitrate: Int = -1,
    val durationMs: Long = 0L,
    // Display capabilities (from DolbyVisionBaseLayerPolicy.Result)
    val hdrCapsKnown: Boolean = false,
    val displayDv: Boolean = false,
    val displayHdr10: Boolean = false,
    val displayHdr10Plus: Boolean = false,
    // Codec capabilities
    val codecDv7Supported: Boolean = false,
    val dv81DecoderName: String? = null,
    // libdovi bridge
    val bridgeReady: Boolean = false,
    val bridgeVersion: String? = null,
    val bridgeReason: String? = null,
    // DV decision
    val dv7ModeRequested: String = "",
    val dv7ModeEffective: String = "",
    val dv7AutoDecision: String? = null,
    // Buffer/Network toggles
    val bufferEngineEnabled: Boolean = false,
    val parallelNetworkEnabled: Boolean = false,
    // Outcome
    val firstFrameMs: Long = -1L,
    val dv7DoviCalls: Int = 0,
    val dv7DoviSuccess: Int = 0,
    val dv7DoviSignalRewrites: Int = 0,
    val dvSourceProfile: String? = null,
    // Video output (captured at first frame from the played video Format)
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val videoHdrType: String? = null,
    // Buffer telemetry (rebuffers counted after first frame; re-persisted at playback end)
    val rebufferCount: Int = 0,
    val rebufferTotalMs: Long = 0L,
    val result: String = "Pending"
) {
    /** Serialize to a JSON string for DataStore persistence. */
    fun toJson(): String = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("host", host)
        putOpt("streamUrl", streamUrl)
        putOpt("headersJson", headersJson)
        put("videoBitrate", videoBitrate)
        put("durationMs", durationMs)
        put("hdrCapsKnown", hdrCapsKnown)
        put("displayDv", displayDv)
        put("displayHdr10", displayHdr10)
        put("displayHdr10Plus", displayHdr10Plus)
        put("codecDv7Supported", codecDv7Supported)
        putOpt("dv81DecoderName", dv81DecoderName)
        put("bridgeReady", bridgeReady)
        putOpt("bridgeVersion", bridgeVersion)
        putOpt("bridgeReason", bridgeReason)
        put("dv7ModeRequested", dv7ModeRequested)
        put("dv7ModeEffective", dv7ModeEffective)
        putOpt("dv7AutoDecision", dv7AutoDecision)
        put("bufferEngineEnabled", bufferEngineEnabled)
        put("parallelNetworkEnabled", parallelNetworkEnabled)
        put("firstFrameMs", firstFrameMs)
        put("dv7DoviCalls", dv7DoviCalls)
        put("dv7DoviSuccess", dv7DoviSuccess)
        put("dv7DoviSignalRewrites", dv7DoviSignalRewrites)
        putOpt("dvSourceProfile", dvSourceProfile)
        putOpt("videoResolution", videoResolution)
        putOpt("videoCodec", videoCodec)
        putOpt("videoHdrType", videoHdrType)
        put("rebufferCount", rebufferCount)
        put("rebufferTotalMs", rebufferTotalMs)
        put("result", result)
    }.toString()

    companion object {
        val EMPTY = LastPlaybackDiagnostics()

        /** Parse from a JSON string previously produced by [toJson]. Returns [EMPTY] on any failure. */
        fun fromJson(json: String): LastPlaybackDiagnostics = try {
            val o = JSONObject(json)
            LastPlaybackDiagnostics(
                timestampMs = o.optLong("timestampMs", 0L),
                host = o.optString("host", ""),
                streamUrl = o.optStringOrNull("streamUrl"),
                headersJson = o.optStringOrNull("headersJson"),
                videoBitrate = o.optInt("videoBitrate", -1),
                durationMs = o.optLong("durationMs", 0L),
                hdrCapsKnown = o.optBoolean("hdrCapsKnown", false),
                displayDv = o.optBoolean("displayDv", false),
                displayHdr10 = o.optBoolean("displayHdr10", false),
                displayHdr10Plus = o.optBoolean("displayHdr10Plus", false),
                codecDv7Supported = o.optBoolean("codecDv7Supported", false),
                dv81DecoderName = o.optStringOrNull("dv81DecoderName"),
                bridgeReady = o.optBoolean("bridgeReady", false),
                bridgeVersion = o.optStringOrNull("bridgeVersion"),
                bridgeReason = o.optStringOrNull("bridgeReason"),
                dv7ModeRequested = o.optString("dv7ModeRequested", ""),
                dv7ModeEffective = o.optString("dv7ModeEffective", ""),
                dv7AutoDecision = o.optStringOrNull("dv7AutoDecision"),
                bufferEngineEnabled = o.optBoolean("bufferEngineEnabled", false),
                parallelNetworkEnabled = o.optBoolean("parallelNetworkEnabled", false),
                firstFrameMs = o.optLong("firstFrameMs", -1L),
                dv7DoviCalls = o.optInt("dv7DoviCalls", 0),
                dv7DoviSuccess = o.optInt("dv7DoviSuccess", 0),
                dv7DoviSignalRewrites = o.optInt("dv7DoviSignalRewrites", 0),
                dvSourceProfile = o.optStringOrNull("dvSourceProfile"),
                videoResolution = o.optStringOrNull("videoResolution"),
                videoCodec = o.optStringOrNull("videoCodec"),
                videoHdrType = o.optStringOrNull("videoHdrType"),
                rebufferCount = o.optInt("rebufferCount", 0),
                rebufferTotalMs = o.optLong("rebufferTotalMs", 0L),
                result = o.optString("result", "Pending")
            )
        } catch (_: Exception) {
            EMPTY
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }
}