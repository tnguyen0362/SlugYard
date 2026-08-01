package androidx.media3.datasource;

import java.nio.ByteBuffer;

/**
 * Interface for zero-copy data reading via direct ByteBuffers.
 * Fork addition for SlugYardEngineConfig performance mode.
 */
public interface ByteBufferDataReader {
    /**
     * Read data into a direct ByteBuffer.
     *
     * @param target the ByteBuffer to read into. The buffer's position is advanced
     *     by the number of bytes read.
     * @return the number of bytes read, or -1 if the end of the data was reached.
     */
    int read(ByteBuffer target);
}
