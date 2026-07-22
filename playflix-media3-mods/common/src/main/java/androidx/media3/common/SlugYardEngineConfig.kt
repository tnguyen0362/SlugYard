package androidx.media3.common

/**
 * Global toggle for SlugYard performance mode.
 * Must be set BEFORE building any ExoPlayer instance.
 *
 * When SLUGYARD mode: native off-heap allocation, zero-copy ByteBuffer pipeline, 64KB scratch buffers.
 * When STOCK mode: heap allocation, standard byte[] pipeline, 4KB scratch buffers.
 */
object SlugYardEngineConfig {
    private var mode: Mode = Mode.STOCK

    fun set(newMode: Mode) {
        mode = newMode
    }

    fun isSlugyardMode(): Boolean = mode == Mode.SLUGYARD
    fun isStockMode(): Boolean = mode == Mode.STOCK

    fun slugyardMode(): Mode = Mode.SLUGYARD
    fun stockMode(): Mode = Mode.STOCK

    enum class Mode {
        STOCK, SLUGYARD
    }
}
