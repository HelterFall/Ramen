package io.github.helterfall.ramen.nscripter

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

data class NscripterArchiveEntry(
    val name: String,
    val compressionType: Int,
    val dataOffset: Long,
    val packedSize: Long,
    val unpackedSize: Long
)

data class NscripterArchiveIndex(
    val format: String,
    val baseOffset: Long,
    val entries: List<NscripterArchiveEntry>
)

/**
 * Read-only index reader for the big-endian NScripter SAR and NSA containers.
 *
 * SAR entries are stored verbatim. NSA entries record a codec byte plus packed
 * and unpacked sizes. readEntry() supports stored data (0) and SPB bitmaps (1);
 * LZSS (2) and NBZ (4) remain explicitly unsupported rather than being
 * returned as ciphertext or truncated data.
 */
class NscripterArchiveReader(
    private val spbDecoder: NscripterSpbDecoder = NscripterSpbDecoder()
) {
    private val shiftJis: Charset = Charset.forName("Shift_JIS")

    @Throws(IOException::class)
    fun readIndex(file: File): NscripterArchiveIndex {
        if (!file.isFile) throw IOException("Archive does not exist: ${file.path}")
        val format = when (file.extension.lowercase()) {
            "sar" -> "SAR"
            "nsa" -> "NSA"
            else -> throw IOException("Unsupported NScripter archive extension: ${file.extension}")
        }

        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < HEADER_SIZE) malformed("Archive is shorter than its header.")

            val firstCount = readU16(input, 0L)
            val headerOffset = if (firstCount == 0) 2L else 0L
            if (length < headerOffset + HEADER_SIZE) {
                malformed("Extended archive header is truncated.")
            }

            val count = if (headerOffset == 0L) firstCount else readU16(input, headerOffset)
            if (count <= 0) malformed("Archive entry count is zero.")

            val baseOffset = readU32(input, headerOffset + 2L)
            if (baseOffset < headerOffset + HEADER_SIZE || baseOffset > length) {
                malformed("Archive base offset is outside the file or overlaps its header.")
            }

            var cursor = headerOffset + HEADER_SIZE
            val entries = ArrayList<NscripterArchiveEntry>(count)
            repeat(count) { index ->
                if (cursor >= baseOffset) {
                    malformed("Archive directory ended before entry $index.")
                }

                val (name, nameBytesRead) = readName(input, cursor, baseOffset - cursor)
                cursor += nameBytesRead

                val descriptorSize = if (format == "SAR") SAR_DESCRIPTOR_SIZE else NSA_DESCRIPTOR_SIZE
                if (cursor > baseOffset || descriptorSize > baseOffset - cursor) {
                    malformed("Archive entry $index has a truncated directory descriptor.")
                }

                val compressionType: Int
                val relativeOffset: Long
                val packedSize: Long
                val unpackedSize: Long
                if (format == "SAR") {
                    compressionType = 0
                    relativeOffset = readU32(input, cursor)
                    packedSize = readU32(input, cursor + 4L)
                    unpackedSize = packedSize
                } else {
                    compressionType = readU8(input, cursor)
                    relativeOffset = readU32(input, cursor + 1L)
                    packedSize = readU32(input, cursor + 5L)
                    unpackedSize = readU32(input, cursor + 9L)
                }
                cursor += descriptorSize

                val dataOffset = checkedAdd(baseOffset, relativeOffset, "entry data offset")
                if (dataOffset > length || packedSize > length - dataOffset) {
                    malformed("Archive entry '$name' points outside the archive.")
                }
                if (format == "SAR" && unpackedSize != packedSize) {
                    malformed("SAR entry '$name' has inconsistent stored sizes.")
                }

                entries += NscripterArchiveEntry(
                    name = name,
                    compressionType = compressionType,
                    dataOffset = dataOffset,
                    packedSize = packedSize,
                    unpackedSize = unpackedSize
                )
            }

            if (cursor > baseOffset) {
                malformed("Archive directory overlaps its data section.")
            }

            return NscripterArchiveIndex(
                format = format,
                baseOffset = baseOffset,
                entries = entries
            )
        }
    }

    /**
     * Reads a stored entry or decompresses supported SPB bitmaps.
     * LZSS and NBZ remain explicitly unsupported rather than being returned as raw bytes.
     */
    @Throws(IOException::class)
    fun readEntry(
        archiveFile: File,
        entry: NscripterArchiveEntry,
        maxBytes: Long = DEFAULT_MAX_ENTRY_BYTES
    ): ByteArray {
        if (!archiveFile.isFile) throw IOException("Archive does not exist: ${archiveFile.path}")
        if (entry.compressionType != 0 && entry.compressionType != SPB_COMPRESSION) {
            throw UnsupportedNscripterCompressionException(entry.compressionType)
        }
        if (entry.packedSize > maxBytes || entry.packedSize > Int.MAX_VALUE) {
            throw IOException("Packed archive entry exceeds the configured read limit: ${entry.name}")
        }
        if (entry.compressionType == 0 &&
            (entry.unpackedSize > maxBytes || entry.unpackedSize > Int.MAX_VALUE)
        ) {
            throw IOException("Unpacked archive entry exceeds the configured read limit: ${entry.name}")
        }

        val packed = RandomAccessFile(archiveFile, "r").use { input ->
            val length = input.length()
            if (entry.dataOffset < 0L || entry.dataOffset > length ||
                entry.packedSize > length - entry.dataOffset
            ) {
                malformed("Archive entry '${entry.name}' is outside the current archive file.")
            }
            ByteArray(entry.packedSize.toInt()).also { bytes ->
                input.seek(entry.dataOffset)
                input.readFully(bytes)
            }
        }

        return when (entry.compressionType) {
            0 -> {
                if (entry.packedSize != entry.unpackedSize) {
                    throw IOException(
                        "Stored NSA entry has mismatched packed/unpacked sizes: ${entry.name}"
                    )
                }
                packed
            }
            SPB_COMPRESSION -> spbDecoder.decode(
                packed = packed,
                // ONScripter derives SPB's BMP size from the dimensions in its stream
                // and does not use the archive's original-size field for this codec.
                expectedUnpackedSize = null,
                maxOutputBytes = maxBytes
            )
            else -> throw UnsupportedNscripterCompressionException(entry.compressionType)
        }
    }

    private fun readName(
        input: RandomAccessFile,
        start: Long,
        availableBytes: Long
    ): Pair<String, Long> {
        val bytes = ByteArrayOutputStream()
        input.seek(start)
        var consumed = 0L
        var terminated = false
        while (consumed < availableBytes && consumed <= MAX_NAME_BYTES) {
            val value = input.readUnsignedByte()
            consumed++
            if (value == 0) {
                terminated = true
                break
            }
            bytes.write(value)
        }
        if (!terminated) {
            malformed("Archive entry name is unterminated or exceeds $MAX_NAME_BYTES bytes.")
        }
        if (bytes.size() == 0) malformed("Archive contains an empty entry name.")

        val name = try {
            shiftJis.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: Exception) {
            throw IOException("Archive entry name is not valid Shift-JIS.", error)
        }
        return name to consumed
    }

    private fun readU8(input: RandomAccessFile, offset: Long): Int {
        input.seek(offset)
        return input.readUnsignedByte()
    }

    private fun readU16(input: RandomAccessFile, offset: Long): Int {
        input.seek(offset)
        return input.readUnsignedShort()
    }

    private fun readU32(input: RandomAccessFile, offset: Long): Long {
        input.seek(offset)
        return input.readInt().toLong() and 0xFFFFFFFFL
    }

    private fun checkedAdd(left: Long, right: Long, description: String): Long {
        if (right > Long.MAX_VALUE - left) malformed("Overflow in $description.")
        return left + right
    }

    private fun malformed(message: String): Nothing = throw IOException(message)

    companion object {
        private const val SPB_COMPRESSION = 1
        private const val HEADER_SIZE = 6L
        private const val SAR_DESCRIPTOR_SIZE = 8L
        private const val NSA_DESCRIPTOR_SIZE = 13L
        private const val MAX_NAME_BYTES = 4096L
        const val DEFAULT_MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    }
}

class UnsupportedNscripterCompressionException(
    val compressionType: Int
) : IOException(
    "NSA compression type $compressionType is indexed but not decoded by this scanner " +
        "(supported types: 0=stored, 1=SPB; unsupported compressed types: 2=LZSS, 4=NBZ)."
)