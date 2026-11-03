package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.io.OutputStream

/**
 * Copies a validated byte range from a positional read-only source without
 * taking ownership of either the source or destination.
 */
class ReadOnlyRangeStreamer(
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES
) {
    init {
        require(bufferSizeBytes > 0) { "bufferSizeBytes must be positive." }
    }

    @Throws(IOException::class)
    fun copy(
        source: ReadOnlyRandomAccessSource,
        offset: Long,
        length: Long,
        destination: OutputStream
    ): Long {
        if (offset < 0L || length < 0L ||
            offset > source.sizeBytes ||
            length > source.sizeBytes - offset
        ) {
            throw IOException("Requested copy range extends beyond the source.")
        }

        val buffer = ByteArray(
            minOf(bufferSizeBytes.toLong(), length.coerceAtLeast(1L)).toInt()
        )
        var cursor = offset
        var remaining = length
        var copied = 0L

        while (remaining > 0L) {
            val chunkSize = minOf(buffer.size.toLong(), remaining).toInt()
            source.readFullyAt(cursor, buffer, 0, chunkSize)
            destination.write(buffer, 0, chunkSize)
            cursor += chunkSize.toLong()
            remaining -= chunkSize.toLong()
            copied += chunkSize.toLong()
        }

        return copied
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024
    }
}
