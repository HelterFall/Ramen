package io.github.helterfall.ramen.inspection

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private fun checkedDpmxAdd(left: Long, right: Long): Long {
    if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
        throw IOException("A DPMX offset overflows the supported address range.")
    }
    return left + right
}

data class DpmxPeSection(
    val name: String,
    val virtualSize: Long,
    val virtualAddress: Long,
    val rawSize: Long,
    val rawPointer: Long
)

data class DpmxFileEntry(
    val name: String,
    val marker: Long,
    val fileKey: Long,
    val relativeOffset: Long,
    val sizeBytes: Long,
    val absoluteOffset: Long
)

data class DpmxContainer(
    val peOffset: Long,
    val imageBase: Long,
    val sections: List<DpmxPeSection>,
    val overlayOffset: Long,
    val dataOffset: Long,
    val dataAbsoluteOffset: Long,
    val fileCount: Int,
    val optionalNameLength: Int,
    val entries: List<DpmxFileEntry>
) {
    fun findEntry(name: String): DpmxFileEntry? =
        entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun readVirtualAddress(
        source: ReadOnlyRandomAccessSource,
        virtualAddress: Long,
        length: Int
    ): ByteArray? {
        require(length >= 0) { "length must not be negative" }
        if (virtualAddress < imageBase) return null
        val rva = virtualAddress - imageBase
        val section = sections.firstOrNull { candidate ->
            val span = maxOf(candidate.virtualSize, candidate.rawSize)
            rva >= candidate.virtualAddress &&
                rva - candidate.virtualAddress < span
        } ?: return null
        val delta = rva - section.virtualAddress
        if (delta > section.rawSize || length.toLong() > section.rawSize - delta) return null
        val offset = checkedDpmxAdd(section.rawPointer, delta)
        if (offset > source.sizeBytes || length.toLong() > source.sizeBytes - offset) return null
        return ByteArray(length).also { source.readFullyAt(offset, it) }
    }

    fun openEntryStream(
        source: ReadOnlyRandomAccessSource,
        entry: DpmxFileEntry
    ): DpmxEntryInputStream {
        require(entry in entries) { "Entry does not belong to this DPMX container." }
        return DpmxEntryInputStream(source, entry.absoluteOffset, entry.sizeBytes)
    }
}

/**
 * Read-only parser for a PE32 executable with a DPMX overlay.
 * Reads headers and table records positionally; the executable is never
 * materialized as a single byte array.
 */
class DpmxContainerParser(
    private val maxSections: Int = DEFAULT_MAX_SECTIONS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxOptionalNameLength: Int = DEFAULT_MAX_OPTIONAL_NAME_LENGTH
) {
    init {
        require(maxSections in 1..MAX_SUPPORTED_SECTIONS)
        require(maxEntries in 1..MAX_SUPPORTED_ENTRIES)
        require(maxOptionalNameLength in 0..MAX_SUPPORTED_OPTIONAL_NAME_LENGTH)
    }

    @Throws(IOException::class)
    fun parse(file: File): DpmxContainer {
        if (!file.isFile) throw IOException("The executable is not a regular file.")
        FileRandomAccessSource(file).use { source -> return parse(source) }
    }

    @Throws(IOException::class)
    fun parse(source: ReadOnlyRandomAccessSource): DpmxContainer {
        if (source.sizeBytes < DOS_HEADER_SIZE) throw IOException("The PE file is shorter than a DOS header.")
        val dosHeader = readBytes(source, 0L, DOS_HEADER_SIZE)
        if (readUInt16(dosHeader, 0) != DOS_MAGIC) throw IOException("The executable does not have an MZ signature.")

        val peOffset = readUInt32(dosHeader, PE_POINTER_OFFSET)
        requireRange(source.sizeBytes, peOffset, PE_HEADER_PREFIX_SIZE.toLong(), "PE header")
        val pePrefix = readBytes(source, peOffset, PE_HEADER_PREFIX_SIZE)
        if (pePrefix[0] != 'P'.code.toByte() || pePrefix[1] != 'E'.code.toByte() ||
            pePrefix[2] != 0.toByte() || pePrefix[3] != 0.toByte()
        ) {
            throw IOException("The PE signature was not found at e_lfanew.")
        }

        val sectionCount = readUInt16(pePrefix, 6)
        val optionalHeaderSize = readUInt16(pePrefix, 20)
        if (sectionCount !in 1..maxSections) throw IOException("The PE section count is outside the supported range.")
        if (optionalHeaderSize < PE32_MIN_OPTIONAL_HEADER_SIZE) throw IOException("The PE optional header is truncated.")

        val optionalHeaderOffset = checkedAdd(peOffset, PE_HEADER_PREFIX_SIZE.toLong())
        requireRange(source.sizeBytes, optionalHeaderOffset, optionalHeaderSize.toLong(), "PE optional header")
        val optionalHeader = readBytes(source, optionalHeaderOffset, optionalHeaderSize)
        if (readUInt16(optionalHeader, 0) != PE32_MAGIC) {
            throw IOException("Only PE32 images are supported by this DPMX parser.")
        }
        val imageBase = readUInt32(optionalHeader, PE32_IMAGE_BASE_OFFSET)
        val sectionTableOffset = checkedAdd(optionalHeaderOffset, optionalHeaderSize.toLong())
        val sectionTableBytes = checkedMultiply(sectionCount.toLong(), PE_SECTION_SIZE.toLong())
        requireRange(source.sizeBytes, sectionTableOffset, sectionTableBytes, "PE section table")

        val sections = ArrayList<DpmxPeSection>(sectionCount)
        var overlayOffset = 0L
        repeat(sectionCount) { index ->
            val headerOffset = checkedAdd(
                sectionTableOffset,
                checkedMultiply(index.toLong(), PE_SECTION_SIZE.toLong())
            )
            val sectionHeader = readBytes(source, headerOffset, PE_SECTION_SIZE)
            val sectionName = decodeSectionName(sectionHeader.copyOfRange(0, SECTION_NAME_SIZE))
            val virtualSize = readUInt32(sectionHeader, 8)
            val virtualAddress = readUInt32(sectionHeader, 12)
            val rawSize = readUInt32(sectionHeader, 16)
            val rawPointer = readUInt32(sectionHeader, 20)
            if (rawSize > 0L) {
                requireRange(source.sizeBytes, rawPointer, rawSize, "PE section $sectionName raw data")
                overlayOffset = maxOf(overlayOffset, checkedAdd(rawPointer, rawSize))
            }
            sections += DpmxPeSection(sectionName, virtualSize, virtualAddress, rawSize, rawPointer)
        }

        requireRange(source.sizeBytes, overlayOffset, DPMX_HEADER_SIZE.toLong(), "DPMX header")
        val header = readBytes(source, overlayOffset, DPMX_HEADER_SIZE)
        if (!header.copyOfRange(0, 4).contentEquals(DPMX_MAGIC)) {
            throw IOException("A DPMX overlay was not found at the end of the last PE raw section.")
        }
        val dataOffset = readUInt32(header, 4)
        val fileCountLong = readUInt32(header, 8)
        val optionalNameLengthLong = readUInt32(header, 12)
        if (fileCountLong > maxEntries.toLong()) {
            throw IOException("The DPMX file count exceeds the configured parser limit.")
        }
        if (optionalNameLengthLong > maxOptionalNameLength.toLong()) {
            throw IOException("The DPMX optional-name area exceeds the configured parser limit.")
        }
        val fileCount = fileCountLong.toInt()
        val optionalNameLength = optionalNameLengthLong.toInt()
        val tableOffset = checkedAdd(
            checkedAdd(overlayOffset, DPMX_HEADER_SIZE.toLong()),
            optionalNameLength.toLong()
        )
        val tableLength = checkedMultiply(fileCount.toLong(), DPMX_ENTRY_SIZE.toLong())
        val tableEnd = checkedAdd(tableOffset, tableLength)
        val dataAbsoluteOffset = checkedAdd(overlayOffset, dataOffset)
        if (dataOffset < DPMX_HEADER_SIZE.toLong() || tableEnd > dataAbsoluteOffset ||
            dataAbsoluteOffset > source.sizeBytes
        ) {
            throw IOException("DPMX table or data-block offsets are inconsistent.")
        }
        requireRange(source.sizeBytes, tableOffset, tableLength, "DPMX file table")

        val entries = ArrayList<DpmxFileEntry>(fileCount)
        repeat(fileCount) { index ->
            val recordOffset = checkedAdd(
                tableOffset,
                checkedMultiply(index.toLong(), DPMX_ENTRY_SIZE.toLong())
            )
            val record = readBytes(source, recordOffset, DPMX_ENTRY_SIZE)
            val name = decodeEntryName(record.copyOfRange(0, ENTRY_NAME_SIZE))
            val marker = readUInt32(record, 16)
            val fileKey = readUInt32(record, 20)
            val relativeOffset = readUInt32(record, 24)
            val sizeBytes = readUInt32(record, 28)
            if (marker != DPMX_ENTRY_MARKER) throw IOException("DPMX entry $index has an unexpected record marker.")
            val absoluteOffset = checkedAdd(dataAbsoluteOffset, relativeOffset)
            requireRange(source.sizeBytes, absoluteOffset, sizeBytes, "DPMX entry $name")
            entries += DpmxFileEntry(name, marker, fileKey, relativeOffset, sizeBytes, absoluteOffset)
        }

        return DpmxContainer(
            peOffset = peOffset,
            imageBase = imageBase,
            sections = sections,
            overlayOffset = overlayOffset,
            dataOffset = dataOffset,
            dataAbsoluteOffset = dataAbsoluteOffset,
            fileCount = fileCount,
            optionalNameLength = optionalNameLength,
            entries = entries
        )
    }

    private fun decodeEntryName(bytes: ByteArray): String {
        val terminator = bytes.indexOf(0)
        val length = if (terminator >= 0) terminator else bytes.size
        if (length == 0) throw IOException("A DPMX entry has an empty filename.")
        val decoded = try {
            SHIFT_JIS.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString()
                .trim()
        } catch (error: CharacterCodingException) {
            throw IOException("A DPMX filename is not valid Shift-JIS.", error)
        }
        if (decoded.isBlank() || decoded == "." || decoded == ".." ||
            decoded.any { it.isISOControl() || it == '/' || it == '\\' || it == ':' }
        ) {
            throw IOException("A DPMX entry contains an unsafe filename.")
        }
        return decoded
    }

    private fun decodeSectionName(bytes: ByteArray): String {
        val terminator = bytes.indexOf(0)
        val length = if (terminator >= 0) terminator else bytes.size
        return String(bytes, 0, length, Charsets.US_ASCII)
    }

    companion object {
        private const val DOS_HEADER_SIZE = 64
        private const val DOS_MAGIC = 0x5A4D
        private const val PE_POINTER_OFFSET = 0x3C
        private const val PE_HEADER_PREFIX_SIZE = 24
        private const val PE32_MAGIC = 0x10B
        private const val PE32_MIN_OPTIONAL_HEADER_SIZE = 96
        private const val PE32_IMAGE_BASE_OFFSET = 28
        private const val PE_SECTION_SIZE = 40
        private const val SECTION_NAME_SIZE = 8
        private const val DPMX_HEADER_SIZE = 16
        private const val DPMX_ENTRY_SIZE = 32
        private const val ENTRY_NAME_SIZE = 16
        private const val DPMX_ENTRY_MARKER = 0xFFFF_FFFFL
        private const val MAX_SUPPORTED_SECTIONS = 96
        private const val MAX_SUPPORTED_ENTRIES = 100_000
        private const val MAX_SUPPORTED_OPTIONAL_NAME_LENGTH = 4096
        private const val DEFAULT_MAX_SECTIONS = 96
        private const val DEFAULT_MAX_ENTRIES = 100_000
        private const val DEFAULT_MAX_OPTIONAL_NAME_LENGTH = 4096
        private val DPMX_MAGIC = byteArrayOf(0x44, 0x50, 0x4D, 0x58)
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        private fun readBytes(
            source: ReadOnlyRandomAccessSource,
            offset: Long,
            length: Int
        ): ByteArray = ByteArray(length).also { source.readFullyAt(offset, it) }

        private fun readUInt16(bytes: ByteArray, offset: Int): Int {
            if (offset < 0 || offset > bytes.size - 2) throw EOFException("A 16-bit field extends beyond its structure.")
            return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun readUInt32(bytes: ByteArray, offset: Int): Long {
            if (offset < 0 || offset > bytes.size - 4) throw EOFException("A 32-bit field extends beyond its structure.")
            return (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
        }

        private fun checkedAdd(left: Long, right: Long): Long {
            if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
                throw IOException("A binary offset overflows the supported address range.")
            }
            return left + right
        }

        private fun checkedMultiply(left: Long, right: Long): Long {
            if (left < 0L || right < 0L || (right != 0L && left > Long.MAX_VALUE / right)) {
                throw IOException("A binary size overflows the supported address range.")
            }
            return left * right
        }

        private fun requireRange(totalSize: Long, offset: Long, length: Long, label: String) {
            if (offset < 0L || length < 0L || offset > totalSize || length > totalSize - offset) {
                throw IOException("$label extends beyond the source.")
            }
        }
    }
}

/** InputStream view over one validated DPMX entry; source ownership stays with the caller. */
class DpmxEntryInputStream internal constructor(
    private val source: ReadOnlyRandomAccessSource,
    private val startOffset: Long,
    private val lengthBytes: Long
) : java.io.InputStream() {
    private var cursor = 0L

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        return if (count < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > target.size - offset) {
            throw IndexOutOfBoundsException("Invalid destination range.")
        }
        if (length == 0) return 0
        if (cursor >= lengthBytes) return -1
        val requested = minOf(length.toLong(), lengthBytes - cursor).toInt()
        val count = source.readAt(startOffset + cursor, target, offset, requested)
        if (count < 0) throw EOFException("The DPMX entry was truncated while reading.")
        if (count == 0) return 0
        cursor += count.toLong()
        return count
    }

    override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), lengthBytes - cursor).toInt()
}
