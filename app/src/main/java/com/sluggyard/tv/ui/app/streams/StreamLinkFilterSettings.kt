package com.sluggyard.tv.ui.app.streams

/**
 * User link filters for Sources list + auto-engine Finding.
 * Always-junk (CAM/3D/VR) is enforced by [StreamLinkFilterPolicy] regardless of these prefs.
 */
data class StreamLinkFilterSettings(
    val maxResolution: StreamLinkMaxResolution = StreamLinkMaxResolution.UHD_4K,
    /** Null = Unlimited. */
    val maxSizeGb: Int? = 15,
    val allowRemux: Boolean = true,
    val allowDolbyVision: Boolean = true,
    val codec: StreamLinkCodecFilter = StreamLinkCodecFilter.ANY,
)

enum class StreamLinkMaxResolution(val label: String) {
    FHD_1080("1080p"),
    UHD_4K("4K"),
    UNLIMITED("No limit"),
}

enum class StreamLinkCodecFilter(val label: String) {
    ANY("Any"),
    H264("H.264"),
    HEVC("HEVC"),
    AV1("AV1"),
}

val StreamLinkSizeGbOptions: List<Int?> = listOf(null, 5, 10, 15, 20, 30, 50)

fun Int?.sizeGbLabel(): String = this?.let { "$it GB" } ?: "Unlimited"
