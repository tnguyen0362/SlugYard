package androidx.media3.exoplayer

/**
 * Configuration for scrubbing (seek) optimization mode.
 * Fork addition for SlugYardEngineConfig performance mode.
 *
 * Controls behavior during seek operations: whether to disable audio/metadata
 * rendering for faster response, and whether to boost codec operating rate.
 */
data class ScrubbingModeParameters(
    /** Disable audio rendering during seeks for faster response. */
    val disableAudioDuringSeek: Boolean = false,
    /** Disable metadata rendering during seeks. */
    val disableMetadataDuringSeek: Boolean = false,
    /** Boost codec operating rate for faster seek frame delivery. */
    val boostCodecOperatingRate: Boolean = false,
    /** Codec operating rate value when boosted. */
    val codecOperatingRate: Float = Float.MAX_VALUE
)
