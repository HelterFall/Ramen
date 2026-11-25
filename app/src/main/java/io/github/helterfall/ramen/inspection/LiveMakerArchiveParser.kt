package io.github.helterfall.ramen.inspection

import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.min

/** Compression methods stored in the VF directory's per-entry compression table. */
enum class LiveMakerCompressionType(val flag: Int) {
    /** pylivemaker LMCompressType.ZLIB = 0. */
    ZLIB(0),

    /** pylivemaker LMCompressType.NONE = 1. */
    NONE(1),

    /** Reserved here; the LiveMaker Pro scramble layer is not implemented in this parser. */
    ENCRYPTED(2),

    /** Reserved here; the LiveMaker Pro scramble layer is not implemented in this parser. */
    ENCRYPTED_ZLIB(3);

    companion object {
        fun fromFlag(flag: Int): LiveMakerCompressionType? =
            entries.firstOrNull { it.flag == flag }
    }
}

data class LiveMakerArchiveEntry(
    val name: String,
    /** Entry offset relative to the beginning of the VF archive, as stored in the VF table. */
    val archiveRelativeOffset: Long,
    val compressedSizeBytes: Long,
    /** Raw compression byte; retained even when this build does not support the value. */
    val compressionFlag: Int,
    val unknownValue: Long,
    val checksum: Long,
    /** Separate LiveMaker Pro encryption/checksum flag; this is not the compression byte. */
    val encryptFlag: Int
) {
    val compressionType: LiveMakerCompressionType?
        get() = LiveMakerCompressionType.fromFlag(compressionFlag)
}

data class LiveMakerArchive(
    val archiveOffset: Long,
    val version: Int,
    val entries: List<LiveMakerArchiveEntry>,
    /** Absolute end of the directory, immediately before the first file-data byte. */
    val directoryEndOffset: Long
)

/**
 * Bounded, read-only reader for a LiveMaker VF v102 archive embedded in an EXE or standing
 * alone as a VF file.
 *
 * This follows pylivemaker's LMArchiveDirectory/LMArchive layout:
 * - `vf`, UInt32 version, UInt32 entry count;
 * - CP932 length-prefixed names, with one continuous LMObfuscator byte stream;
 * - `count + 1` pairs of obfuscated UInt32 low/high offset words;
 * - compression bytes, UInt32 unknown values, UInt32 checksums, encryption bytes;
 * - file payloads at `archiveOffset + entryOffset`.
 *
 * The source remains owned by the caller. [copyEntryTo] streams data into a caller-owned output
 * stream and does not allocate an entire decompressed resource in memory.
 */
class LiveMakerArchiveParser(
    private val maxEntryCount: Int = DEFAULT_MAX_ENTRY_COUNT,
    private val maxNameBytes: Int = DEFAULT_MAX_NAME_BYTES
) {

    init {
        require(maxEntryCount >= 0) { "maxEntryCount must not be negative." }
        require(maxNameBytes > 0) { "maxNameBytes must be positive." }
    }

    @Throws(IOException::class)
    fun parse(source: ReadOnlyRandomAccessSource): LiveMakerArchive {
        if (source.sizeBytes < VF_HEADER_SIZE_BYTES) {
            malformed("Source is too short to contain a VF header.")
        }

        val archiveOffset = findArchiveOffset(source)
        requireRange(source, archiveOffset, VF_HEADER_SIZE_BYTES.toLong(), "VF header")

        val signature = ByteArray(2)
        source.readFullyAt(archiveOffset, signature)
        if (signature[0] != 'v'.code.toByte() || signature[1] != 'f'.code.toByte()) {
            malformed("VF signature was not found at archive offset 0x${archiveOffset.toString(16)}.")
        }

        // pylivemaker uses a UInt32 here. For v102 the upper two bytes of this word are zero,
        // which is the two-byte padding visible after the 16-bit value 102.
        val versionValue = readU32Le(source, archiveOffset + 2L)
        if (versionValue > Int.MAX_VALUE.toLong()) {
            malformed("VF version does not fit a signed Int.")
        }
        val version = versionValue.toInt()
        if (version != SUPPORTED_VERSION) {
            throw IOException(
                "Unsupported LiveMaker VF version $version; expected $SUPPORTED_VERSION."
            )
        }

        val countValue = readU32Le(source, archiveOffset + 6L)
        if (countValue > maxEntryCount.toLong()) {
            malformed("VF entry count $countValue exceeds the configured limit $maxEntryCount.")
        }
        val count = countValue.toInt()

        var cursor = archiveOffset + VF_HEADER_SIZE_BYTES
        val names = ArrayList<String>(count)
        val nameObfuscator = LiveMakerXorKeystream(LIVEMAKER3_XOR_SEED)

        repeat(count) { index ->
            requireRange(source, cursor, UINT32_SIZE_BYTES.toLong(), "filename[$index] length")
            val nameLengthValue = readU32Le(source, cursor)
            cursor += UINT32_SIZE_BYTES

            if (nameLengthValue == 0L || nameLengthValue > maxNameBytes.toLong()) {
                malformed("Filename[$index] has invalid byte length $nameLengthValue.")
            }
            val nameLength = nameLengthValue.toInt()
            requireRange(source, cursor, nameLengthValue, "filename[$index]")

            val encryptedName = ByteArray(nameLength)
            source.readFullyAt(cursor, encryptedName)
            cursor += nameLengthValue

            val decodedBytes = nameObfuscator.transformBytes(encryptedName)
            val name = try {
                CP932.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decodedBytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IOException("Filename[$index] is not valid CP932.", error)
            }
            if (name.isBlank() || '\u0000' in name) {
                malformed("Filename[$index] is empty or contains a NUL character.")
            }
            names += name
        }

        // pylivemaker creates independent PRNG streams for the low-word and high-word columns.
        val lowWordObfuscator = LiveMakerXorKeystream(LIVEMAKER3_XOR_SEED)
        val highWordObfuscator = LiveMakerXorKeystream(LIVEMAKER3_XOR_SEED)
        val offsets = LongArray(count + 1)

        repeat(count + 1) { index ->
            requireRange(source, cursor, OFFSET_PAIR_SIZE_BYTES.toLong(), "offset[$index]")
            val encryptedLow = readU32Le(source, cursor)
            cursor += UINT32_SIZE_BYTES
            val encryptedHigh = readU32Le(source, cursor)
            cursor += UINT32_SIZE_BYTES

            val low = lowWordObfuscator.transformUInt32(encryptedLow)
            val highDecoded = highWordObfuscator.transformUInt32(encryptedHigh)

            // LMArchiveDirectory.transform_int_high() canonicalizes the high DWORD to either
            // 0 or 0xffffffff based on its sign bit. make_offset() then keeps only bit 31.
            // Such an offset is >= 2^63 and cannot address an Android file, so reject it.
            if ((highDecoded and UINT32_SIGN_BIT) != 0L) {
                malformed("Offset[$index] exceeds the signed 64-bit file-offset range.")
            }
            offsets[index] = low
        }

        val compressionFlags = ByteArray(count)
        requireRange(source, cursor, count.toLong(), "compression table")
        if (count > 0) source.readFullyAt(cursor, compressionFlags)
        cursor += count.toLong()

        val unknownValues = LongArray(count)
        repeat(count) { index ->
            requireRange(source, cursor, UINT32_SIZE_BYTES.toLong(), "unknown[$index]")
            unknownValues[index] = readU32Le(source, cursor)
            cursor += UINT32_SIZE_BYTES
        }

        val checksums = LongArray(count)
        repeat(count) { index ->
            requireRange(source, cursor, UINT32_SIZE_BYTES.toLong(), "checksum[$index]")
            checksums[index] = readU32Le(source, cursor)
            cursor += UINT32_SIZE_BYTES
        }

        val encryptFlags = ByteArray(count)
        requireRange(source, cursor, count.toLong(), "encryption flag table")
        if (count > 0) source.readFullyAt(cursor, encryptFlags)
        cursor += count.toLong()

        val directoryEndOffset = cursor
        val directoryRelativeEnd = directoryEndOffset - archiveOffset
        if (count > 0 && offsets[0] < directoryRelativeEnd) {
            malformed("The first file offset points into the VF directory.")
        }

        val entries = ArrayList<LiveMakerArchiveEntry>(count)
        for (index in 0 until count) {
            val start = offsets[index]
            val end = offsets[index + 1]
            if (end < start) {
                malformed("VF file offsets are not monotonic at entry $index.")
            }
            val absoluteStart = checkedAdd(archiveOffset, start, "entry[$index] start")
            val size = end - start
            requireRange(source, absoluteStart, size, "entry[$index] payload")

            entries += LiveMakerArchiveEntry(
                name = names[index],
                archiveRelativeOffset = start,
                compressedSizeBytes = size,
                compressionFlag = compressionFlags[index].toInt() and 0xFF,
                unknownValue = unknownValues[index],
                checksum = checksums[index],
                encryptFlag = encryptFlags[index].toInt() and 0xFF
            )
        }

        return LiveMakerArchive(
            archiveOffset = archiveOffset,
            version = version,
            entries = entries,
            directoryEndOffset = directoryEndOffset
        )
    }

    /**
     * Copies one entry to [output]. Compression flag 0 is zlib and flag 1 is uncompressed,
     * matching pylivemaker's LMCompressType enum. Encryption modes 2/3 are reported but not
     * decoded by this v102 zlib/NONE implementation.
     *
     * The caller owns [source] and [output]. [maxOutputBytes] bounds decompression-bomb output.
     */
    @Throws(IOException::class)
    fun copyEntryTo(
        source: ReadOnlyRandomAccessSource,
        archive: LiveMakerArchive,
        entry: LiveMakerArchiveEntry,
        output: OutputStream,
        maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES
    ): Long {
        require(maxOutputBytes >= 0L) { "maxOutputBytes must not be negative." }
        if (entry !in archive.entries) {
            throw IllegalArgumentException("Entry does not belong to the supplied archive.")
        }

        val absoluteOffset = checkedAdd(
            archive.archiveOffset,
            entry.archiveRelativeOffset,
            "entry absolute offset"
        )
        requireRange(source, absoluteOffset, entry.compressedSizeBytes, "entry data")

        return when (entry.compressionFlag) {
            LiveMakerCompressionType.NONE.flag -> copyStored(
                source = source,
                offset = absoluteOffset,
                sizeBytes = entry.compressedSizeBytes,
                output = output,
                maxOutputBytes = maxOutputBytes
            )

            LiveMakerCompressionType.ZLIB.flag -> copyZlib(
                source = source,
                offset = absoluteOffset,
                sizeBytes = entry.compressedSizeBytes,
                output = output,
                maxOutputBytes = maxOutputBytes
            )

            LiveMakerCompressionType.ENCRYPTED.flag,
            LiveMakerCompressionType.ENCRYPTED_ZLIB.flag -> throw IOException(
                "LiveMaker Pro encrypted compression flag ${entry.compressionFlag} is not supported."
            )

            else -> throw IOException(
                "Unknown LiveMaker compression flag ${entry.compressionFlag}."
            )
        }
    }

    /**
     * Returns a portable relative path for safe extraction. Windows separators are converted to
     * '/', while absolute paths, drive-qualified paths, empty segments, '.' and '..' are rejected.
     */
    @Throws(IOException::class)
    fun safeRelativePath(entry: LiveMakerArchiveEntry): String {
        val normalized = entry.name.replace('\\', '/')
        if (normalized.startsWith('/') ||
            normalized.startsWith("//") ||
            DRIVE_PREFIX.matches(normalized)
        ) {
            throw IOException("LiveMaker entry path is absolute: ${entry.name}")
        }

        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("LiveMaker entry path contains an unsafe segment: ${entry.name}")
        }
        if ('\u0000' in normalized) {
            throw IOException("LiveMaker entry path contains a NUL character.")
        }
        return segments.joinToString("/")
    }

    private fun findArchiveOffset(source: ReadOnlyRandomAccessSource): Long {
        if (source.sizeBytes < 2L) malformed("Source is too short.")
        val dosSignature = ByteArray(2)
        source.readFullyAt(0L, dosSignature)

        // pylivemaker treats an MZ-prefixed file as an EXE and requires the exact 6-byte trailer:
        // UInt32 LE archive offset followed by ASCII 'lv'. Standalone VF archives start at 0.
        if (dosSignature[0] != 'M'.code.toByte() || dosSignature[1] != 'Z'.code.toByte()) {
            return 0L
        }
        if (source.sizeBytes < EXE_TRAILER_SIZE_BYTES) {
            malformed("MZ executable is too short to contain a LiveMaker trailer.")
        }

        val trailer = ByteArray(EXE_TRAILER_SIZE_BYTES)
        source.readFullyAt(source.sizeBytes - EXE_TRAILER_SIZE_BYTES, trailer)
        if (trailer[4] != 'l'.code.toByte() || trailer[5] != 'v'.code.toByte()) {
            malformed("MZ executable does not end with the LiveMaker 'lv' trailer.")
        }

        val offset = readU32Le(trailer, 0)
        requireRange(source, offset, VF_HEADER_SIZE_BYTES.toLong(), "embedded VF header")
        return offset
    }

    private fun copyStored(
        source: ReadOnlyRandomAccessSource,
        offset: Long,
        sizeBytes: Long,
        output: OutputStream,
        maxOutputBytes: Long
    ): Long {
        if (sizeBytes > maxOutputBytes) {
            throw IOException("Stored entry exceeds the configured output limit.")
        }
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var position = offset
        var remaining = sizeBytes
        var copied = 0L
        while (remaining > 0L) {
            val length = min(buffer.size.toLong(), remaining).toInt()
            source.readFullyAt(position, buffer, 0, length)
            output.write(buffer, 0, length)
            position += length.toLong()
            remaining -= length.toLong()
            copied += length.toLong()
        }
        return copied
    }

    private fun copyZlib(
        source: ReadOnlyRandomAccessSource,
        offset: Long,
        sizeBytes: Long,
        output: OutputStream,
        maxOutputBytes: Long
    ): Long {
        val inflater = Inflater(/* nowrap = */ false)
        val inputBuffer = ByteArray(COPY_BUFFER_BYTES)
        val outputBuffer = ByteArray(COPY_BUFFER_BYTES)
        var inputPosition = offset
        var compressedRemaining = sizeBytes
        var outputCount = 0L

        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (compressedRemaining == 0L) {
                        throw EOFException("ZLIB stream ended before its end marker.")
                    }
                    val length = min(inputBuffer.size.toLong(), compressedRemaining).toInt()
                    source.readFullyAt(inputPosition, inputBuffer, 0, length)
                    inflater.setInput(inputBuffer, 0, length)
                    inputPosition += length.toLong()
                    compressedRemaining -= length.toLong()
                }

                val inflated = try {
                    inflater.inflate(outputBuffer)
                } catch (error: DataFormatException) {
                    throw IOException("Invalid ZLIB stream in LiveMaker entry.", error)
                }

                when {
                    inflated > 0 -> {
                        if (outputCount > maxOutputBytes - inflated.toLong()) {
                            throw IOException("Decompressed entry exceeds the configured output limit.")
                        }
                        output.write(outputBuffer, 0, inflated)
                        outputCount += inflated.toLong()
                    }
                    inflater.needsDictionary() ->
                        throw IOException("ZLIB stream requires a preset dictionary.")
                    inflater.needsInput() && compressedRemaining == 0L ->
                        throw EOFException("Truncated ZLIB stream in LiveMaker entry.")
                    inflater.needsInput() -> Unit // Feed the next compressed chunk on the next loop.
                    !inflater.finished() ->
                        throw IOException("ZLIB inflater made no progress.")
                }
            }

            // Each VF entry is exactly the byte range between two directory offsets.
            if (compressedRemaining != 0L || inflater.remaining != 0) {
                throw IOException("ZLIB stream does not consume the complete VF entry payload.")
            }
            return outputCount
        } finally {
            inflater.end()
        }
    }

    private fun requireRange(
        source: ReadOnlyRandomAccessSource,
        offset: Long,
        length: Long,
        label: String
    ) {
        if (offset < 0L || length < 0L || offset > source.sizeBytes ||
            length > source.sizeBytes - offset
        ) {
            malformed("$label range is outside the source.")
        }
    }

    private fun checkedAdd(left: Long, right: Long, label: String): Long {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            malformed("$label overflows a signed 64-bit offset.")
        }
        return left + right
    }

    private fun readU32Le(
        source: ReadOnlyRandomAccessSource,
        offset: Long
    ): Long {
        val bytes = ByteArray(UINT32_SIZE_BYTES)
        source.readFullyAt(offset, bytes)
        return readU32Le(bytes, 0)
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun malformed(message: String): Nothing =
        throw IOException("Malformed LiveMaker VF archive: $message")

    private class LiveMakerXorKeystream(private val seed: Long) {
        private var state = 0L

        init {
            require(seed in 0L..UINT32_MASK)
        }

        private fun nextKey(): Long {
            // Exact pylivemaker LMObfuscator._keystream recurrence, modulo UInt32.
            state = ((state shl 2) + state + seed) and UINT32_MASK
            return state
        }

        fun transformBytes(input: ByteArray): ByteArray {
            val output = ByteArray(input.size)
            input.indices.forEach { index ->
                val keyByte = (nextKey() and 0xFFL).toInt()
                output[index] = (input[index].toInt() xor keyByte).toByte()
            }
            return output
        }

        fun transformUInt32(value: Long): Long = (value xor nextKey()) and UINT32_MASK
    }

    companion object {
        const val SUPPORTED_VERSION = 102
        const val LIVEMAKER3_XOR_SEED = 0x75D6EE39L

        private const val VF_HEADER_SIZE_BYTES = 10
        private const val EXE_TRAILER_SIZE_BYTES = 6
        private const val UINT32_SIZE_BYTES = 4
        private const val OFFSET_PAIR_SIZE_BYTES = 8
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val DEFAULT_MAX_ENTRY_COUNT = 100_000
        private const val DEFAULT_MAX_NAME_BYTES = 16 * 1024
        private const val DEFAULT_MAX_OUTPUT_BYTES = 512L * 1024L * 1024L
        private const val UINT32_MASK = 0xFFFF_FFFFL
        private const val UINT32_SIGN_BIT = 0x8000_0000L

        private val CP932: Charset = Charset.forName("windows-31j")
        private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}