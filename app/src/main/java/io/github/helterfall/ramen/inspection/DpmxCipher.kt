package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class DpmxCipherKey(
    val al: Int,
    val cl: Int,
    val initialDl: Int = 0
) {
    init {
        require(al in 0..0xFF)
        require(cl in 0..0xFF)
        require(initialDl in 0..0xFF)
    }
}

/** Streaming DPMX feedback decryptor; state is carried across input chunks. */
object DpmxDecryptor {
    const val DEFAULT_BUFFER_SIZE = 64 * 1024

    @Throws(IOException::class)
    fun decrypt(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        key: DpmxCipherKey,
        output: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE
    ) {
        require(bufferSize > 0) { "bufferSize must be positive." }
        val input = DpmxEntryInputStream(source, entry.absoluteOffset, entry.sizeBytes)
        val encrypted = ByteArray(bufferSize)
        val decoded = ByteArray(bufferSize)
        var dl = key.initialDl and 0xFF

        while (true) {
            val count = input.read(encrypted)
            if (count < 0) break
            if (count == 0) continue
            for (index in 0 until count) {
                val enc = encrypted[index].toInt() and 0xFF
                val bl = ((enc - key.al) and 0xFF) xor key.cl
                dl = (dl + bl) and 0xFF
                decoded[index] = dl.toByte()
            }
            output.write(decoded, 0, count)
        }
    }

    @Throws(IOException::class)
    fun decryptPrefix(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        key: DpmxCipherKey,
        length: Int
    ): ByteArray {
        require(length >= 0)
        val count = minOf(length.toLong(), entry.sizeBytes).toInt()
        val prefixEntry = entry.copy(sizeBytes = count.toLong())
        val output = ByteArrayOutputStream(count)
        decrypt(source, prefixEntry, key, output, bufferSize = minOf(DEFAULT_BUFFER_SIZE, maxOf(1, count)))
        return output.toByteArray()
    }

    fun decryptBytes(encrypted: ByteArray, key: DpmxCipherKey): ByteArray {
        val result = ByteArray(encrypted.size)
        var dl = key.initialDl and 0xFF
        for (index in encrypted.indices) {
            val enc = encrypted[index].toInt() and 0xFF
            val bl = ((enc - key.al) and 0xFF) xor key.cl
            dl = (dl + bl) and 0xFF
            result[index] = dl.toByte()
        }
        return result
    }
}

/**
 * Optional game-specific PE/.data key schedule. The caller-provided strategy
 * can implement the recovered arithmetic once its exact formula and vectors
 * are available. The resolver does not invent that missing transformation.
 */
fun interface DpmxDataKeyDeriver {
    fun derive(fileKey: Long, keyConstants: ByteArray): DpmxCipherKey?
}

data class DpmxKeyResolution(
    val key: DpmxCipherKey,
    val method: Method
) {
    enum class Method {
        PE_DATA_DERIVER,
        VERIFIED_SAMPLE_VECTOR,
        SIGNATURE_AUTODETECT
    }
}

/**
 * Resolves keys in this order: injected .data schedule, the two supplied
 * known-good sample vectors, then an exhaustive 256x256 signature search.
 * Fallback candidates are accepted only when their plaintext prefixes are identical.
 */
class DpmxKeyResolver(
    private val dataKeyDeriver: DpmxDataKeyDeriver? = null
) {
    @Throws(IOException::class)
    fun resolve(
        source: ReadOnlyRandomAccessSource,
        container: DpmxContainer,
        entry: DpmxFileEntry
    ): DpmxKeyResolution? {
        require(entry in container.entries) { "Entry does not belong to this DPMX container." }

        val constants = container.readVirtualAddress(
            source,
            GAME_KEY_CONSTANTS_VA,
            GAME_KEY_CONSTANTS_LENGTH
        )
        if (constants != null) {
            val derived = dataKeyDeriver?.derive(entry.fileKey, constants)
            if (derived != null && validates(source, entry, derived)) {
                return DpmxKeyResolution(derived, DpmxKeyResolution.Method.PE_DATA_DERIVER)
            }
        }

        val known = KNOWN_SAMPLE_KEYS[entry.fileKey]
        if (known != null && validates(source, entry, known)) {
            return DpmxKeyResolution(known, DpmxKeyResolution.Method.VERIFIED_SAMPLE_VECTOR)
        }

        val detected = autoDetect(source, entry) ?: return null
        return DpmxKeyResolution(detected, DpmxKeyResolution.Method.SIGNATURE_AUTODETECT)
    }

    private fun validates(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry,
        key: DpmxCipherKey
    ): Boolean {
        val prefixLength = minOf(prefixLengthFor(entry), entry.sizeBytes.toIntSafe())
        if (prefixLength == 0) return false
        val prefix = DpmxDecryptor.decryptPrefix(source, entry, key, prefixLength)
        return isPlausible(entry, prefix)
    }

    private fun autoDetect(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry
    ): DpmxCipherKey? {
        val sampleLength = minOf(prefixLengthFor(entry), entry.sizeBytes.toIntSafe())
        if (sampleLength < MIN_SAMPLE_LENGTH) return null
        val encrypted = ByteArray(sampleLength)
        source.readFullyAt(entry.absoluteOffset, encrypted)

        var candidate: DpmxCipherKey? = null
        var candidatePlaintext: ByteArray? = null
        for (al in 0..0xFF) {
            for (cl in 0..0xFF) {
                val testedKey = DpmxCipherKey(al, cl)
                val plaintext = DpmxDecryptor.decryptBytes(encrypted, testedKey)
                if (!isPlausible(entry, plaintext)) continue

                val canonical = canonicalizeSymmetricKey(testedKey)
                if (candidate == null) {
                    candidate = canonical
                    candidatePlaintext = plaintext
                } else if (!candidatePlaintext!!.contentEquals(plaintext)) {
                    // More than one distinct plausible plaintext means the signature
                    // is insufficient to identify a key safely.
                    return null
                } else if (compareKeys(canonical, candidate) < 0) {
                    candidate = canonical
                }
            }
        }
        return candidate
    }

    private fun prefixLengthFor(entry: DpmxFileEntry): Int =
        when (entry.name.substringAfterLast('.', "").lowercase()) {
            "ax" -> HSPHED_SIZE_BYTES
            "bmp" -> BMP_PREFIX_LENGTH
            "txt", "ini", "as", "hsp", "cfg" -> TEXT_PREFIX_LENGTH
            else -> MIN_SAMPLE_LENGTH
        }

    private fun isPlausible(entry: DpmxFileEntry, bytes: ByteArray): Boolean {
        return when (entry.name.substringAfterLast('.', "").lowercase()) {
            "ax" -> isValidHspHeader(entry, bytes)
            "bmp" -> bytes.size >= BMP_PREFIX_LENGTH &&
                bytes[0] == 'B'.code.toByte() &&
                bytes[1] == 'M'.code.toByte() &&
                readUInt32(bytes, 2) == entry.sizeBytes
            "txt", "ini", "as", "hsp", "cfg" -> isPrintableShiftJis(bytes)
            else -> false
        }
    }

    private fun isValidHspHeader(entry: DpmxFileEntry, bytes: ByteArray): Boolean {
        if (bytes.size < HSPHED_SIZE_BYTES ||
            bytes[0] != 'H'.code.toByte() ||
            bytes[1] != 'S'.code.toByte() ||
            bytes[2] != 'P'.code.toByte() ||
            bytes[3] != '3'.code.toByte()
        ) return false

        val version = readUInt32(bytes, 4)
        if (version !in HSP3_VERSION_MIN..HSP3_VERSION_MAX) return false

        val allSize = readUInt32(bytes, 12)
        if (allSize < HSPHED_SIZE_BYTES.toLong() || allSize > entry.sizeBytes) return false

        val code = SegmentRange(readUInt32(bytes, 16), readUInt32(bytes, 20))
        val data = SegmentRange(readUInt32(bytes, 24), readUInt32(bytes, 28))
        val objectTemp = SegmentRange(readUInt32(bytes, 32), readUInt32(bytes, 36))
        val debug = SegmentRange(readUInt32(bytes, 40), readUInt32(bytes, 44))
        if (code.size == 0L) return false

        val ranges = listOf(code, data, objectTemp, debug)
        if (ranges.any { segment ->
                segment.size < 0L ||
                    (segment.size == 0L && segment.offset > allSize) ||
                    (segment.size > 0L &&
                        (segment.offset < HSPHED_SIZE_BYTES.toLong() ||
                            segment.offset > allSize ||
                            segment.size > allSize - segment.offset))
            }
        ) return false

        // Segment tables in HSPHED describe disjoint byte ranges. Overlap can
        // make a random decryption candidate look superficially plausible.
        for (leftIndex in ranges.indices) {
            val left = ranges[leftIndex]
            if (left.size == 0L) continue
            for (rightIndex in leftIndex + 1 until ranges.size) {
                val right = ranges[rightIndex]
                if (right.size == 0L) continue
                if (left.offset < right.offset + right.size &&
                    right.offset < left.offset + left.size
                ) return false
            }
        }
        return true
    }

    private data class SegmentRange(val offset: Long, val size: Long)

    /** The cipher has a 128-step key symmetry; always expose the lower-al form. */
    private fun canonicalizeSymmetricKey(key: DpmxCipherKey): DpmxCipherKey =
        if (key.al >= 0x80) {
            DpmxCipherKey(key.al xor 0x80, key.cl xor 0x80, key.initialDl)
        } else {
            key
        }

    private fun compareKeys(left: DpmxCipherKey, right: DpmxCipherKey): Int {
        val alOrder = left.al.compareTo(right.al)
        if (alOrder != 0) return alOrder
        val clOrder = left.cl.compareTo(right.cl)
        if (clOrder != 0) return clOrder
        return left.initialDl.compareTo(right.initialDl)
    }

    private fun isPrintableShiftJis(bytes: ByteArray): Boolean {
        return try {
            val decoded = SHIFT_JIS.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            decoded.isNotEmpty() && decoded.all { character ->
                character == '\r' || character == '\n' || character == '\t' ||
                    !Character.isISOControl(character)
            }
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun Long.toIntSafe(): Int =
        minOf(Int.MAX_VALUE.toLong(), maxOf(0L, this)).toInt()

    companion object {
        const val GAME_KEY_CONSTANTS_VA = 0x00429E40L
        const val GAME_KEY_CONSTANTS_LENGTH = 0x1C
        private const val HSP3_VERSION_MIN = 0x00000300L
        private const val HSP3_VERSION_MAX = 0x00000399L
        private const val HSPHED_SIZE_BYTES = 0x60
        private const val BMP_PREFIX_LENGTH = 6
        private const val TEXT_PREFIX_LENGTH = 32
        private const val MIN_SAMPLE_LENGTH = 6
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        private val KNOWN_SAMPLE_KEYS = mapOf(
            0xB2B8EDB5L to DpmxCipherKey(al = 0xB2, cl = 0x53),
            0x7C01AD18L to DpmxCipherKey(al = 0x38, cl = 0x8D)
        )
    }
}
