package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.io.InputStream

/**
 * InputStream view over one bounded range of a caller-owned positional source.
 *
 * Closing this stream closes only the view, never the underlying source. The source
 * must remain open for the complete lifetime of this stream.
 */
class ReadOnlySourceRangeInputStream(
    private val source: ReadOnlyRandomAccessSource,
    offset: Long,
    length: Long
) : InputStream() {
    private val startOffset: Long = offset
    private val rangeLength: Long = length
    private var position: Long = 0L
    private var closed = false
    private val oneByte = ByteArray(1)

    init {
        if (offset < 0L || length < 0L || offset > source.sizeBytes ||
            length > source.sizeBytes - offset
        ) {
            throw IOException("Requested input range is outside the source.")
        }
    }

    override fun read(): Int {
        ensureOpen()
        if (position >= rangeLength) return -1
        source.readFullyAt(startOffset + position, oneByte)
        position += 1L
        return oneByte[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensureOpen()
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException("Invalid destination range.")
        }
        if (length == 0) return 0
        if (position >= rangeLength) return -1

        val count = minOf(length.toLong(), rangeLength - position).toInt()
        source.readFullyAt(
            position = startOffset + position,
            target = buffer,
            targetOffset = offset,
            length = count
        )
        position += count
        return count
    }

    override fun skip(byteCount: Long): Long {
        ensureOpen()
        if (byteCount <= 0L) return 0L
        val skipped = minOf(byteCount, rangeLength - position)
        position += skipped
        return skipped
    }

    override fun available(): Int {
        ensureOpen()
        return minOf(rangeLength - position, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        closed = true
    }

    private fun ensureOpen() {
        if (closed) throw IOException("The range stream is closed.")
    }
}
