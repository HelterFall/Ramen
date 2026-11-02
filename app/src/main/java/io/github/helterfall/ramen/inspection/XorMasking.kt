package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.io.InputStream

/**
 * Stateless byte-wise XOR view. The mask is applied to every byte read but never
 * changes byte positions, lengths, or the underlying source.
 *
 * This is a format adapter for the observed fixed-key stream mask, not encryption
 * or cryptographic protection.
 */
class XorMaskingInputStream(
    private val source: InputStream,
    key: Int
) : InputStream() {
    private val mask = key.also {
        require(it in 0..0xFF) { "XOR key must be an unsigned byte." }
    }

    override fun read(): Int {
        val value = source.read()
        return if (value < 0) -1 else value xor mask
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        val count = source.read(target, offset, length)
        if (count > 0 && mask != 0) {
            for (index in offset until offset + count) {
                target[index] = (target[index].toInt() xor mask).toByte()
            }
        }
        return count
    }

    override fun skip(count: Long): Long = source.skip(count)

    override fun available(): Int = source.available()

    override fun close() = source.close()
}

/**
 * Positional read-only XOR view used for indexed archive payloads.
 *
 * The source size and every offset remain unchanged, so offsets read from the
 * separate, unmasked GTB/VFS index remain valid.
 */
class XorMaskingRandomAccessSource(
    private val source: ReadOnlyRandomAccessSource,
    key: Int
) : ReadOnlyRandomAccessSource {
    private val mask = key.also {
        require(it in 0..0xFF) { "XOR key must be an unsigned byte." }
    }

    override val sizeBytes: Long
        get() = source.sizeBytes

    @Throws(IOException::class)
    override fun readAt(
        position: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int
    ): Int {
        val count = source.readAt(position, target, targetOffset, length)
        if (count > 0 && mask != 0) {
            for (index in targetOffset until targetOffset + count) {
                target[index] = (target[index].toInt() xor mask).toByte()
            }
        }
        return count
    }

    override fun close() = source.close()
}
