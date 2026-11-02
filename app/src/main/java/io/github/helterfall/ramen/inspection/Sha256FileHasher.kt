package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class Sha256Fingerprint(
    val fileName: String,
    val sizeBytes: Long,
    val sha256Hex: String
)

/**
 * Computes SHA-256 incrementally using a fixed-size buffer.
 * The inspected file is never loaded entirely into memory or modified.
 */
class Sha256FileHasher(
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES
) {
    init {
        require(bufferSizeBytes in 1..MAX_BUFFER_SIZE_BYTES) {
            "bufferSizeBytes must be between 1 and $MAX_BUFFER_SIZE_BYTES"
        }
    }

    @Throws(IOException::class)
    fun fingerprint(file: File): Sha256Fingerprint {
        FileRandomAccessSource(file).use { source ->
            return Sha256Fingerprint(
                fileName = file.name,
                sizeBytes = source.sizeBytes,
                sha256Hex = hash(source)
            )
        }
    }

    @Throws(IOException::class)
    fun hash(source: ReadOnlyRandomAccessSource): String {
        val initialSize = source.sizeBytes
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        val buffer = ByteArray(bufferSizeBytes)
        var position = 0L

        while (position < initialSize) {
            val chunkLength = minOf(buffer.size.toLong(), initialSize - position).toInt()
            source.readFullyAt(
                position = position,
                target = buffer,
                targetOffset = 0,
                length = chunkLength
            )
            digest.update(buffer, 0, chunkLength)
            position += chunkLength
        }

        // Detect a source that changed size during the hash operation.
        if (source.sizeBytes != initialSize) {
            throw IOException("Source size changed while computing SHA-256.")
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    companion object {
        private const val SHA_256_ALGORITHM = "SHA-256"
        private const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024
        private const val MAX_BUFFER_SIZE_BYTES = 1024 * 1024
    }
}
