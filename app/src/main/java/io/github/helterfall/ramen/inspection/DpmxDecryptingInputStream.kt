package io.github.helterfall.ramen.inspection

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Decrypting, bounded stream for one DPMX entry. Closing this stream also
 * closes the positional source supplied to it; callers should pass a source
 * opened specifically for this stream.
 */
class DpmxDecryptingInputStream(
    private val source: ReadOnlyRandomAccessSource,
    entry: DpmxFileEntry,
    private val key: DpmxCipherKey,
    bufferSize: Int = DpmxDecryptor.DEFAULT_BUFFER_SIZE
) : InputStream() {
    private val encryptedStream = DpmxEntryInputStream(source, entry.absoluteOffset, entry.sizeBytes)
    private val encrypted = ByteArray(bufferSize.also { require(it > 0) })
    private val decoded = ByteArray(bufferSize)
    private var decodedOffset = 0
    private var decodedLength = 0
    private var dl = key.initialDl and 0xFF
    private var closed = false

    override fun read(): Int {
        ensureOpen()
        if (!fillBuffer()) return -1
        return decoded[decodedOffset++].toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        ensureOpen()
        if (offset < 0 || length < 0 || length > target.size - offset) {
            throw IndexOutOfBoundsException("Invalid destination range.")
        }
        if (length == 0) return 0

        var copied = 0
        while (copied < length) {
            if (decodedOffset >= decodedLength && !fillBuffer()) break
            val count = minOf(length - copied, decodedLength - decodedOffset)
            decoded.copyInto(target, offset + copied, decodedOffset, decodedOffset + count)
            decodedOffset += count
            copied += count
        }
        return if (copied == 0) -1 else copied
    }

    override fun available(): Int = if (closed) 0 else encryptedStream.available()

    private fun fillBuffer(): Boolean {
        if (decodedOffset < decodedLength) return true
        while (true) {
            val count = encryptedStream.read(encrypted)
            if (count < 0) {
                decodedOffset = 0
                decodedLength = 0
                return false
            }
            if (count == 0) continue

            for (index in 0 until count) {
                val enc = encrypted[index].toInt() and 0xFF
                val bl = ((enc - key.al) and 0xFF) xor key.cl
                dl = (dl + bl) and 0xFF
                decoded[index] = dl.toByte()
            }
            decodedOffset = 0
            decodedLength = count
            return true
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("The DPMX decrypted stream is closed.")
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            encryptedStream.close()
        } finally {
            source.close()
        }
    }
}
