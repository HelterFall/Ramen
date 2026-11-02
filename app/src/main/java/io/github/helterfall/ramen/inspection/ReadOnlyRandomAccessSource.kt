package io.github.helterfall.ramen.inspection

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Read-only positional access to a byte source.
 *
 * Implementations must not mutate the source and must not share a mutable cursor between reads.
 * A caller owns the source lifetime and must close it when finished.
 */
interface ReadOnlyRandomAccessSource : Closeable {
    val sizeBytes: Long

    /**
     * Reads up to [length] bytes from [position] into [target].
     * Returns -1 at EOF, or 0 when [length] is zero.
     */
    @Throws(IOException::class)
    fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int = 0,
        length: Int = target.size
    ): Int

    /**
     * Reads exactly [length] bytes or throws EOFException.
     */
    @Throws(IOException::class)
    fun readFullyAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int = 0,
        length: Int = target.size
    ) {
        require(position >= 0) { "position must not be negative" }
        require(targetOffset >= 0 && targetOffset <= target.size) {
            "targetOffset is outside the destination buffer"
        }
        require(length >= 0 && length <= target.size - targetOffset) {
            "length is outside the destination buffer"
        }
        if (length == 0) return
        if (position > sizeBytes || length.toLong() > sizeBytes - position) {
            throw EOFException("Requested byte range extends beyond the source.")
        }

        var totalRead = 0
        var consecutiveEmptyReads = 0
        while (totalRead < length) {
            val read = readAt(
                position = position + totalRead,
                target = target,
                targetOffset = targetOffset + totalRead,
                length = length - totalRead
            )
            when {
                read < 0 -> throw EOFException("Unexpected end of source.")
                read == 0 -> {
                    consecutiveEmptyReads += 1
                    if (consecutiveEmptyReads >= MAX_EMPTY_READS) {
                        throw IOException("The source made no progress while reading.")
                    }
                }
                else -> {
                    totalRead += read
                    consecutiveEmptyReads = 0
                }
            }
        }
    }

    companion object {
        private const val MAX_EMPTY_READS = 8
    }
}

/**
 * File-backed implementation using positional FileChannel reads.
 * Positional reads are safe for independent parsers because they do not modify a shared cursor.
 */
class FileRandomAccessSource(file: File) : ReadOnlyRandomAccessSource {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    override val sizeBytes: Long
        get() = channel.size()

    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        require(position >= 0) { "position must not be negative" }
        require(targetOffset >= 0 && targetOffset <= target.size) {
            "targetOffset is outside the destination buffer"
        }
        require(length >= 0 && length <= target.size - targetOffset) {
            "length is outside the destination buffer"
        }
        if (length == 0) return 0
        if (position >= sizeBytes) return -1

        val buffer = ByteBuffer.wrap(target, targetOffset, length)
        return channel.read(buffer, position)
    }

    override fun close() {
        channel.close()
    }
}
