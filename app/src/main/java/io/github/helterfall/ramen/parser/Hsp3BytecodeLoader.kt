package io.github.helterfall.ramen.parser

import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

data class Hsp3Segment(
    val fileOffset: Long,
    val sizeBytes: Int,
    val bytes: ByteArray
)

data class Hsp3Header(
    val version: Long,
    val maxValueCount: Long,
    val allSizeBytes: Long,
    val codeOffset: Long,
    val codeSizeBytes: Long,
    val dataOffset: Long,
    val dataSizeBytes: Long,
    val objectTempOffset: Long,
    val objectTempSizeBytes: Long,
    val debugInfoOffset: Long,
    val debugInfoSizeBytes: Long,
    val libraryInfoOffset: Long,
    val libraryInfoSizeBytes: Long,
    val functionInfoOffset: Long,
    val functionInfoSizeBytes: Long,
    val moduleInfoOffset: Long,
    val moduleInfoSizeBytes: Long,
    val functionInfo2Offset: Long,
    val functionInfo2SizeBytes: Long,
    val hpiDataOffset: Long,
    val maxHpiBytes: Int,
    val maxVariableHpi: Int,
    val bootOptions: Long,
    val runtimeNameOffset: Long
) {
    val hasExternalPluginData: Boolean
        get() = maxHpiBytes != 0 || maxVariableHpi != 0
}

data class Hsp3Token(
    val type: Int,
    val extensionFlags: Int,
    val value: Long,
    /** Byte offset relative to the beginning of the Code Segment. */
    val codeOffset: Int,
    val encodedSizeBytes: Int
)

/**
 * HSP 3.1 Windows binaries use the active Windows ANSI code page for script
 * strings. Japanese Windows games use CP932 / Windows-31J, not strict JIS
 * Shift_JIS. DPMX decryption is applied to the whole start.ax before DS is read.
 */
object Hsp3StringEncoding {
    val WINDOWS_31J: Charset = Charset.forName("windows-31j")
}

/**
 * Bounded HSP3 object representation. Segment arrays contain only the
 * declared HSP payloads; the original executable/container is never copied.
 */
class Hsp3Program internal constructor(
    val header: Hsp3Header,
    val codeSegment: Hsp3Segment,
    val dataSegment: Hsp3Segment,
    val objectTempSegment: Hsp3Segment,
    val debugInfoSegment: Hsp3Segment
) {
    /**
     * Decodes the common HSP3 token envelope. It does not assign command
     * semantics or decode the expression/parameter grammar.
     */
    fun tokens(): Sequence<Hsp3Token> = sequence {
        val code = codeSegment.bytes
        var cursor = 0
        while (cursor < code.size) {
            if (code.size - cursor < TOKEN_HEADER_SIZE_BYTES) {
                throw IOException("The HSP3 code segment ends inside a token.")
            }
            val commandWord = readUInt16(code, cursor)
            val type = commandWord and TOKEN_TYPE_MASK
            val flags = commandWord and TOKEN_EXTENSION_FLAGS_MASK
            val isWide = commandWord and TOKEN_32BIT_VALUE_FLAG != 0
            val tokenSize = if (isWide) TOKEN_32BIT_SIZE_BYTES else TOKEN_16BIT_SIZE_BYTES
            if (code.size - cursor < tokenSize) {
                throw IOException("The HSP3 token has a truncated value field.")
            }
            val value = if (isWide) {
                readUInt32(code, cursor + 2)
            } else {
                readUInt16(code, cursor + 2).toLong()
            }
            yield(
                Hsp3Token(
                    type = type,
                    extensionFlags = flags,
                    value = value,
                    codeOffset = cursor,
                    encodedSizeBytes = tokenSize
                )
            )
            cursor += tokenSize
            // OpenHSP's TYPE_CMPCMD dispatcher consumes one raw 16-bit
            // branch-distance word immediately after the command token.
            if (type == TYPE_CMPCMD) {
                if (code.size - cursor < 2) {
                    throw IOException("The HSP if/else command is missing its raw branch offset.")
                }
                cursor += 2
            }
        }
    }

    /** Returns a Shift-JIS NUL-terminated string addressed from the DS start. */
    @Throws(IOException::class)
    fun stringAt(dataOffset: Long, maxBytes: Int = DEFAULT_MAX_STRING_BYTES): String {
        if (dataOffset < 0L || dataOffset >= dataSegment.sizeBytes.toLong()) {
            throw IOException("The HSP3 string offset is outside the Data Segment.")
        }
        require(maxBytes > 0)
        val start = dataOffset.toInt()
        val limit = minOf(dataSegment.bytes.size, start + minOf(maxBytes, dataSegment.bytes.size - start))
        var end = start
        while (end < limit && dataSegment.bytes[end] != 0.toByte()) end += 1
        if (end == limit && (end == dataSegment.bytes.size || end - start == maxBytes)) {
            throw IOException("The HSP3 string is not NUL-terminated within the configured bound.")
        }
        return String(dataSegment.bytes, start, end - start, Hsp3StringEncoding.WINDOWS_31J)
    }

    /** Reads an IEEE-754 little-endian double from the Data Segment. */
    @Throws(IOException::class)
    fun doubleAt(dataOffset: Long): Double {
        if (dataOffset < 0L || dataOffset > dataSegment.sizeBytes.toLong() - java.lang.Double.BYTES) {
            throw IOException("The HSP3 double offset is outside the Data Segment.")
        }
        return ByteBuffer.wrap(dataSegment.bytes, dataOffset.toInt(), java.lang.Double.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .double
    }

    /**
     * Reads one 32-bit label/code offset from Object Temp. HSP stores this
     * segment as an int table; max_ot is a byte size, not an entry count.
     */
    @Throws(IOException::class)
    fun labelCodeOffset(index: Int): Long {
        if (index < 0) throw IOException("The HSP3 label index is negative.")
        val byteOffset = index.toLong() * UInt.SIZE_BYTES
        if (byteOffset > objectTempSegment.sizeBytes.toLong() - UInt.SIZE_BYTES) {
            throw IOException("The HSP3 label index is outside Object Temp.")
        }
        // OpenHSP indexes mem_mcs as unsigned-short words, not bytes.
        val wordOffset = readUInt32(objectTempSegment.bytes, byteOffset.toInt())
        val byteCodeOffset = wordOffset * 2L
        if (byteCodeOffset >= header.codeSizeBytes) {
            throw IOException("The HSP3 label points outside the Code Segment.")
        }
        return byteCodeOffset
    }

    companion object {
        private const val TOKEN_HEADER_SIZE_BYTES = 4
        private const val TOKEN_16BIT_SIZE_BYTES = 4
        private const val TOKEN_32BIT_SIZE_BYTES = 6
        private const val TOKEN_TYPE_MASK = 0x0FFF
        private const val TOKEN_EXTENSION_FLAGS_MASK = 0x7000
        private const val TOKEN_32BIT_VALUE_FLAG = 0x8000
        private const val TYPE_CMPCMD = 11
        private const val DEFAULT_MAX_STRING_BYTES = 1024 * 1024

        private fun readUInt16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readUInt32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}

/**
 * HSPHED/CS/DS/OT/DI reader for HSP3 bytecode files.
 *
 * All offsets are validated against allsize and the source length before
 * allocating a segment. Reads are limited to declared segments and a caller
 * supplied maximum; the loader never materializes unrelated file bytes.
 */
class Hsp3BytecodeLoader(
    private val maxSegmentSizeBytes: Int = DEFAULT_MAX_SEGMENT_SIZE_BYTES
) {
    init {
        require(maxSegmentSizeBytes >= HSPHED_SIZE_BYTES)
    }

    @Throws(IOException::class)
    fun load(file: File): Hsp3Program {
        if (!file.isFile) throw IOException("The HSP3 object is not a regular file.")
        io.github.helterfall.ramen.inspection.FileRandomAccessSource(file).use { source ->
            return load(source)
        }
    }

    /** Loads from a caller-owned positional source and leaves it open. */
    @Throws(IOException::class)
    fun load(source: ReadOnlyRandomAccessSource): Hsp3Program {
        if (source.sizeBytes < HSPHED_SIZE_BYTES.toLong()) {
            throw IOException("The HSP3 object is shorter than the 96-byte HSPHED.")
        }
        val bytes = ByteArray(HSPHED_SIZE_BYTES)
        source.readFullyAt(0L, bytes)
        val magic = bytes.copyOfRange(0, 4)
        if (!magic.contentEquals(HSP3_MAGIC)) {
            throw IOException("The HSP3 object magic is not HSP3.")
        }

        val header = parseHeader(bytes)
        if (header.version < MIN_SUPPORTED_VERSION) {
            throw IOException("HSP version " + header.version.toString(16) + " is older than HSP 3.1.")
        }
        if (header.allSizeBytes < HSPHED_SIZE_BYTES ||
            header.allSizeBytes > source.sizeBytes
        ) {
            throw IOException("HSPHED allsize exceeds the available object bytes.")
        }
        if (header.maxValueCount > MAX_VARIABLES) {
            throw IOException("HSPHED max_val exceeds the configured VM limit.")
        }

        val code = readSegment(source, header.allSizeBytes, header.codeOffset, header.codeSizeBytes, "CS")
        val data = readSegment(source, header.allSizeBytes, header.dataOffset, header.dataSizeBytes, "DS")
        val objectTemp = readSegment(
            source, header.allSizeBytes, header.objectTempOffset, header.objectTempSizeBytes, "OT"
        )
        val debug = readSegment(
            source, header.allSizeBytes, header.debugInfoOffset, header.debugInfoSizeBytes, "DI"
        )

        if (code.sizeBytes % 2 != 0) {
            throw IOException("The HSP3 Code Segment must contain complete 16-bit words.")
        }
        return Hsp3Program(header, code, data, objectTemp, debug)
    }

    private fun parseHeader(bytes: ByteArray): Hsp3Header = Hsp3Header(
        version = readUInt32(bytes, 4),
        maxValueCount = readUInt32(bytes, 8),
        allSizeBytes = readUInt32(bytes, 12),
        codeOffset = readUInt32(bytes, 16),
        codeSizeBytes = readUInt32(bytes, 20),
        dataOffset = readUInt32(bytes, 24),
        dataSizeBytes = readUInt32(bytes, 28),
        objectTempOffset = readUInt32(bytes, 32),
        objectTempSizeBytes = readUInt32(bytes, 36),
        debugInfoOffset = readUInt32(bytes, 40),
        debugInfoSizeBytes = readUInt32(bytes, 44),
        libraryInfoOffset = readUInt32(bytes, 48),
        libraryInfoSizeBytes = readUInt32(bytes, 52),
        functionInfoOffset = readUInt32(bytes, 56),
        functionInfoSizeBytes = readUInt32(bytes, 60),
        moduleInfoOffset = readUInt32(bytes, 64),
        moduleInfoSizeBytes = readUInt32(bytes, 68),
        functionInfo2Offset = readUInt32(bytes, 72),
        functionInfo2SizeBytes = readUInt32(bytes, 76),
        hpiDataOffset = readUInt32(bytes, 80),
        maxHpiBytes = readUInt16(bytes, 84),
        maxVariableHpi = readUInt16(bytes, 86),
        bootOptions = readUInt32(bytes, 88),
        runtimeNameOffset = readUInt32(bytes, 92)
    )

    private fun readSegment(
        source: ReadOnlyRandomAccessSource,
        allSizeBytes: Long,
        offset: Long,
        sizeBytes: Long,
        label: String
    ): Hsp3Segment {
        if (sizeBytes > maxSegmentSizeBytes.toLong()) {
            throw IOException("The HSP3 " + label + " segment exceeds the configured memory bound.")
        }
        if (sizeBytes == 0L) {
            if (offset != 0L && offset > allSizeBytes) {
                throw IOException("The empty HSP3 " + label + " segment has an invalid offset.")
            }
            return Hsp3Segment(offset, 0, ByteArray(0))
        }
        if (offset < HSPHED_SIZE_BYTES.toLong() ||
            offset > allSizeBytes ||
            sizeBytes > allSizeBytes - offset
        ) {
            throw IOException("The HSP3 " + label + " segment lies outside allsize.")
        }
        val data = ByteArray(sizeBytes.toInt())
        source.readFullyAt(offset, data)
        return Hsp3Segment(offset, data.size, data)
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset > bytes.size - 2) {
            throw EOFException("A 16-bit HSPHED field extends beyond the header.")
        }
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > bytes.size - 4) {
            throw EOFException("A 32-bit HSPHED field extends beyond the header.")
        }
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    companion object {
        const val HSPHED_SIZE_BYTES = 0x60
        const val HSP3_VERSION_31 = 0x00000301L
        private const val MIN_SUPPORTED_VERSION = HSP3_VERSION_31
        private const val DEFAULT_MAX_SEGMENT_SIZE_BYTES = 64 * 1024 * 1024
        private const val MAX_VARIABLES = 1_000_000L
        private val HSP3_MAGIC = byteArrayOf(0x48, 0x53, 0x50, 0x33)
    }
}
