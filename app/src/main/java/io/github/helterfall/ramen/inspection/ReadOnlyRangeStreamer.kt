package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.io.OutputStream

/**
 * Copies one validated positional range to a caller-owned output stream.
 *
 * The operation uses a fixed-size buffer and does not close or flush either
 * the source or the destination. The caller is responsible for choosing an
 * appropriate destination and for closing it when its lifecycle is complete.
 */
class ReadOnlyRangeStreamer(
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES
) {
    init {
        require(bufferSizeBytes in 1..MAX_BUFFER_SIZE_BYTES) {
            "bufferSizeBytes must be between 1 and $MAX_BUFFER_SIZE_BYTES."
        }
    }

    @Throws(IOException::class)
    fun copy(
        source: ReadOnlyRandomAccessSource,
        offset: Long,
        length: Long,
        destination: OutputStream
    ): Long {
        if (offset < 0L || length < 0L || offset > source.sizeBytes ||
            length > source.sizeBytes - offset
        ) {
            throw IOException("Requested resource range is outside the source.")
        }

        val buffer = ByteArray(bufferSizeBytes)
        var position = offset
        var remaining = length
        var copied = 0L
        while (remaining > 0L) {
            val chunkSize = minOf(bufferSizeBytes.toLong(), remaining).toInt()
            source.readFullyAt(position, buffer, targetOffset = 0, length = chunkSize)
            destination.write(buffer, 0, chunkSize)
            position += chunkSize
            remaining -= chunkSize
            copied += chunkSize
        }
        return copied
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024
        private const val MAX_BUFFER_SIZE_BYTES = 1024 * 1024
    }
}
