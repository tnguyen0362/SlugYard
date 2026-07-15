package androidx.media3.common

/**
 * Global toggle for Nuvio performance mode.
 * Must be set BEFORE building any ExoPlayer instance.
 *
 * When NUVIO mode: native off-heap allocation, zero-copy ByteBuffer pipeline, 64KB scratch buffers.
 * When STOCK mode: heap allocation, standard byte[] pipeline, 4KB scratch buffers.
 */
object NuvioEngineConfig {
    private var mode: Mode = Mode.STOCK

    fun set(newMode: Mode) {
        mode = newMode
    }

    fun isNuvioMode(): Boolean = mode == Mode.NUVIO
    fun isStockMode(): Boolean = mode == Mode.STOCK

    fun nuvioMode(): Mode = Mode.NUVIO
    fun stockMode(): Mode = Mode.STOCK

    enum class Mode {
        STOCK, NUVIO
    }
}
