package io.github.helterfall.ramen.parser

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * One of the four descriptors stored in the SPT header.
 *
 * Despite the historical property name [offset], the value is a logical
 * address base, not a physical byte offset in the file. Physical address
 * tables are discovered separately from their first logical address.
 */
data class SystemNnnOffsetTable(
    val name: String,
    val entryCount: UInt,
    val offset: UInt
) {
    val logicalBase: UInt
        get() = offset

    val hasPayload: Boolean
        get() = entryCount > 0u && offset != 0u
}

/** A raw byte from the instruction/code area; it is not a decoded VM opcode. */
data class SystemNnnInstruction(
    val offset: Long,
    val opcode: Int
)

/**
 * One NUL-terminated record from an indexed SPT pool.
 *
 * [offset] is the physical file offset of the first byte. [logicalAddress]
 * comes from the pool's UInt32 address table. The original bytes are retained
 * even when the record is not valid Shift-JIS, so decoding never changes the
 * record count or loses its boundary.
 */
data class SystemNnnTextEntry(
    val offset: Long,
    val text: String,
    val rawBytes: ByteArray = text.toByteArray(Charsets.UTF_8),
    val logicalAddress: UInt? = null,
    val hasDecodingErrors: Boolean = false
) {
    val utf8Bytes: ByteArray
        get() = text.toByteArray(Charsets.UTF_8)
}

/** A fully bounded address table and the records which immediately follow it. */
data class SystemNnnStringPool(
    val name: String,
    val entryCount: UInt,
    val logicalBase: UInt,
    val addressTableOffset: Long?,
    val dataOffset: Long?,
    val endOffset: Long?,
    val logicalAddresses: List<UInt>,
    val entries: List<SystemNnnTextEntry>
) {
    val isEmpty: Boolean
        get() = entries.isEmpty()
}

/**
 * Read-only representation of a demasked System-NNN SPT file.
 *
 * [commandStartOffset]..[commandEndOffset] is the bounded pre-pool code area.
 * Bytes in that area are exposed for diagnostics only; opcode and operand
 * boundaries have not yet been decoded.
 */
class SystemNnnScript internal constructor(
    val fileSizeBytes: Long,
    val firstHeaderWord: UInt,
    val offsetTables: List<SystemNnnOffsetTable>,
    val commandStartOffset: Long,
    val commandEndOffset: Long,
    val textPoolOffset: Long,
    val textPool: List<SystemNnnTextEntry>,
    private val commandBytes: ByteArray,
    val secondaryTextPool: List<SystemNnnTextEntry> = emptyList(),
    val stringPools: List<SystemNnnStringPool> = emptyList()
) {
    val commandByteCount: Int
        get() = commandBytes.size

    val allTextEntries: List<SystemNnnTextEntry>
        get() = textPool + secondaryTextPool

    private val textEntriesByOffset: Map<Long, SystemNnnTextEntry> =
        allTextEntries.associateBy(SystemNnnTextEntry::offset)

    /** Resolves only exact record starts, never pointers into the middle of a record. */
    fun textEntryAtOffset(absoluteOffset: Long): SystemNnnTextEntry? =
        textEntriesByOffset[absoluteOffset]

    /** Reads a bounded slice of the raw pre-pool code area. */
    fun readCommandBytes(absoluteOffset: Long, length: Int): ByteArray {
        require(length >= 0) { "length must not be negative" }
        require(absoluteOffset >= commandStartOffset) {
            "command offset is before the validated code region"
        }
        val relativeOffset = absoluteOffset - commandStartOffset
        require(relativeOffset <= commandBytes.size.toLong()) {
            "command offset is after the validated code region"
        }
        require(length.toLong() <= commandBytes.size.toLong() - relativeOffset) {
            "requested bytes exceed the validated code region"
        }
        val start = relativeOffset.toInt()
        return commandBytes.copyOfRange(start, start + length)
    }

    fun commandByteAt(absoluteOffset: Long): Int? {
        if (absoluteOffset < commandStartOffset || absoluteOffset >= commandEndOffset) {
            return null
        }
        return commandBytes[(absoluteOffset - commandStartOffset).toInt()].toInt() and 0xFF
    }

    /** Iterates raw bytes for diagnostics; these are not VM instructions. */
    fun instructions(): Sequence<SystemNnnInstruction> = sequence {
        var index = 0
        while (index < commandBytes.size) {
            yield(
                SystemNnnInstruction(
                    offset = commandStartOffset + index,
                    opcode = commandBytes[index].toInt() and 0xFF
                )
            )
            index += 1
        }
    }
}

/**
 * Bounded reader for the observed System-NNN SPT container.
 *
 * The first two header descriptors describe indexed string pools. Their
 * logical UInt32 address tables are located by searching for the first address
 * (logicalBase + entryCount), then validating the complete increasing table
 * against the 4-byte-aligned NUL-terminated records that follow it.
 *
 * The third and fourth descriptors are preserved as metadata only; their
 * meanings have not yet been established.
 */
class SystemNnnScriptLoader(
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES
) {
    init {
        require(maxFileSizeBytes >= FIXED_HEADER_SIZE.toLong()) {
            "maxFileSizeBytes is too small for an SPT header."
        }
    }

    @Throws(IOException::class)
    fun load(file: File): SystemNnnScript {
        if (!file.isFile) {
            throw IOException("The SPT script is not a regular file.")
        }
        FileRandomAccessSource(file).use { source ->
            return load(source)
        }
    }

    /** Loads from a caller-owned source. This method does not close [source]. */
    @Throws(IOException::class)
    fun load(source: ReadOnlyRandomAccessSource): SystemNnnScript {
        val sourceSize = source.sizeBytes
        if (sourceSize < FIXED_HEADER_SIZE.toLong()) {
            throw IOException("The SPT file is shorter than its fixed header.")
        }
        if (sourceSize > maxFileSizeBytes || sourceSize > Int.MAX_VALUE.toLong()) {
            throw IOException("The SPT file exceeds the configured parser size limit.")
        }

        val masked = ByteArray(sourceSize.toInt())
        source.readFullyAt(0L, masked)

        val decoded = ByteArray(masked.size) { index ->
            (masked[index].toInt() xor 0xFF).toByte()
        }
        validateSignature(decoded)

        val firstWord = readUInt32LE(decoded, FIRST_HEADER_WORD_OFFSET)
        val descriptors = listOf(
            readTable(decoded, "main", MAIN_TABLE_COUNT_OFFSET, MAIN_TABLE_BASE_OFFSET),
            readTable(decoded, "secondary", SECONDARY_TABLE_COUNT_OFFSET, SECONDARY_TABLE_BASE_OFFSET),
            readTable(decoded, "tertiary", TERTIARY_TABLE_COUNT_OFFSET, TERTIARY_TABLE_BASE_OFFSET),
            readTable(decoded, "auxiliary", AUXILIARY_TABLE_COUNT_OFFSET, AUXILIARY_TABLE_BASE_OFFSET)
        )

        val primaryPool = locatePool(decoded, descriptors[0])
        val secondaryPool = locatePool(decoded, descriptors[1])

        if (secondaryPool.endOffset != sourceSize) {
            throw IOException("The secondary SPT string pool does not end at the file boundary.")
        }

        if (!primaryPool.isEmpty) {
            validateInterPoolStub(decoded, primaryPool, secondaryPool)
        }

        val firstPhysicalTable = listOfNotNull(
            primaryPool.addressTableOffset,
            secondaryPool.addressTableOffset
        ).minOrNull() ?: sourceSize

        if (firstPhysicalTable < FIXED_HEADER_SIZE.toLong()) {
            throw IOException("An SPT address table overlaps the fixed header.")
        }

        val commandStart = FIXED_HEADER_SIZE.toLong()
        val commandEnd = firstPhysicalTable
        val commandBytes = decoded.copyOfRange(commandStart.toInt(), commandEnd.toInt())
        val compatibleTextPoolOffset = primaryPool.dataOffset
            ?: secondaryPool.dataOffset
            ?: sourceSize

        return SystemNnnScript(
            fileSizeBytes = sourceSize,
            firstHeaderWord = firstWord,
            offsetTables = descriptors,
            commandStartOffset = commandStart,
            commandEndOffset = commandEnd,
            textPoolOffset = compatibleTextPoolOffset,
            textPool = primaryPool.entries,
            commandBytes = commandBytes,
            secondaryTextPool = secondaryPool.entries,
            stringPools = listOf(primaryPool, secondaryPool)
        )
    }

    private fun locatePool(
        decoded: ByteArray,
        descriptor: SystemNnnOffsetTable
    ): SystemNnnStringPool {
        val countLong = descriptor.entryCount.toLong()
        if (countLong == 0L) {
            return emptyPool(descriptor)
        }

        val firstLogicalAddress = descriptor.logicalBase.toLong() + countLong
        if (firstLogicalAddress > UINT32_MAX) {
            throw IOException("The " + descriptor.name + " SPT logical address range overflows UInt32.")
        }

        val tableByteCount = countLong * UINT32_BYTES
        if (tableByteCount > decoded.size.toLong()) {
            throw IOException("The " + descriptor.name + " SPT address table exceeds the file size.")
        }

        val candidates = ArrayList<SystemNnnStringPool>()
        var tableOffset = FIXED_HEADER_SIZE.toLong()
        val lastPossibleStart = decoded.size.toLong() - tableByteCount

        while (tableOffset <= lastPossibleStart) {
            val physicalOffset = tableOffset.toInt()
            if (readUInt32LE(decoded, physicalOffset).toLong() == firstLogicalAddress) {
                val addresses = readLogicalAddresses(
                    decoded = decoded,
                    tableOffset = physicalOffset,
                    count = countLong.toInt()
                )
                if (addresses != null) {
                    val dataOffset = tableOffset + tableByteCount
                    val parsedRecords = readPoolRecords(
                        decoded = decoded,
                        dataOffset = dataOffset,
                        addresses = addresses
                    )
                    if (parsedRecords != null) {
                        candidates += SystemNnnStringPool(
                            name = descriptor.name,
                            entryCount = descriptor.entryCount,
                            logicalBase = descriptor.logicalBase,
                            addressTableOffset = tableOffset,
                            dataOffset = dataOffset,
                            endOffset = parsedRecords.endOffset,
                            logicalAddresses = addresses,
                            entries = parsedRecords.entries
                        )
                    }
                }
            }
            tableOffset += ADDRESS_ALIGNMENT_BYTES
        }

        return when (candidates.size) {
            1 -> candidates.single()
            0 -> throw IOException(
                "Could not locate a valid " + descriptor.name +
                    " SPT address table and aligned string pool."
            )
            else -> throw IOException(
                "The " + descriptor.name + " SPT address table is ambiguous: " +
                    candidates.size + " valid candidates were found."
            )
        }
    }

    private fun readLogicalAddresses(
        decoded: ByteArray,
        tableOffset: Int,
        count: Int
    ): List<UInt>? {
        val values = ArrayList<UInt>(count)
        var previous = -1L
        repeat(count) { index ->
            val value = readUInt32LE(decoded, tableOffset + index * UINT32_BYTES.toInt())
            val current = value.toLong()
            if (current <= previous) return null
            values += value
            previous = current
        }
        return values
    }

    private data class ParsedPoolRecords(
        val entries: List<SystemNnnTextEntry>,
        val endOffset: Long
    )

    private fun readPoolRecords(
        decoded: ByteArray,
        dataOffset: Long,
        addresses: List<UInt>
    ): ParsedPoolRecords? {
        val entries = ArrayList<SystemNnnTextEntry>(addresses.size)
        val nextOffsets = ArrayList<Long>(addresses.size)
        var cursor = dataOffset

        for (index in addresses.indices) {
            if (cursor < 0L || cursor >= decoded.size.toLong()) return null

            var terminator = cursor.toInt()
            while (terminator < decoded.size && decoded[terminator] != 0.toByte()) {
                if (terminator.toLong() - cursor >= MAX_TEXT_ENTRY_BYTES.toLong()) return null
                terminator += 1
            }
            if (terminator >= decoded.size) return null

            val raw = decoded.copyOfRange(cursor.toInt(), terminator)
            val decodedText = decodeShiftJis(raw)
            val nextOffset = alignToFourBytes(terminator.toLong() + 1L)
            if (nextOffset > decoded.size.toLong()) return null

            entries += SystemNnnTextEntry(
                offset = cursor,
                text = decodedText.text,
                rawBytes = raw,
                logicalAddress = addresses[index],
                hasDecodingErrors = decodedText.hasErrors
            )
            nextOffsets += nextOffset
            cursor = nextOffset
        }

        for (index in 0 until addresses.size - 1) {
            val logicalDelta = addresses[index + 1].toLong() - addresses[index].toLong()
            val physicalDeltaCells = (nextOffsets[index] - entries[index].offset) / ADDRESS_CELL_BYTES
            if (logicalDelta != physicalDeltaCells) return null
        }

        return ParsedPoolRecords(entries, cursor)
    }

    private data class DecodedText(
        val text: String,
        val hasErrors: Boolean
    )

    private fun decodeShiftJis(raw: ByteArray): DecodedText {
        return try {
            val text = SHIFT_JIS.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
            DecodedText(text, false)
        } catch (_: CharacterCodingException) {
            val replacementText = SHIFT_JIS.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(raw))
                .toString()
            DecodedText(replacementText, true)
        }
    }

    private fun validateInterPoolStub(
        decoded: ByteArray,
        primaryPool: SystemNnnStringPool,
        secondaryPool: SystemNnnStringPool
    ) {
        val primaryEnd = primaryPool.endOffset
            ?: throw IOException("The primary SPT pool has no validated end offset.")
        val secondaryTable = secondaryPool.addressTableOffset
            ?: throw IOException("The secondary SPT pool has no address table.")

        val expectedSecondaryTable = primaryEnd + INTER_POOL_STUB_BYTES
        if (secondaryTable != expectedSecondaryTable) {
            throw IOException(
                "The secondary SPT address table does not follow the 16-byte inter-pool stub."
            )
        }
        if (expectedSecondaryTable > decoded.size.toLong()) {
            throw IOException("The SPT inter-pool stub extends beyond the file.")
        }

        val stubOffset = primaryEnd.toInt()
        val validStub =
            readUInt16LE(decoded, stubOffset + 4) == 1 &&
                readUInt16LE(decoded, stubOffset + 6) == 0x6666 &&
                readUInt16LE(decoded, stubOffset + 8) == 2 &&
                readUInt16LE(decoded, stubOffset + 10) == 0x5555 &&
                readUInt16LE(decoded, stubOffset + 12) == 1 &&
                readUInt16LE(decoded, stubOffset + 14) == 0x4444

        if (!validStub) {
            throw IOException("The SPT inter-pool marker block is invalid.")
        }
    }

    private fun emptyPool(descriptor: SystemNnnOffsetTable) = SystemNnnStringPool(
        name = descriptor.name,
        entryCount = 0u,
        logicalBase = descriptor.logicalBase,
        addressTableOffset = null,
        dataOffset = null,
        endOffset = null,
        logicalAddresses = emptyList(),
        entries = emptyList()
    )

    private fun validateSignature(decoded: ByteArray) {
        val signatureEnd = SIGNATURE_OFFSET + SIGNATURE_LENGTH
        if (decoded.size < signatureEnd) {
            throw IOException("The SPT header is truncated.")
        }
        val signature = String(decoded, SIGNATURE_OFFSET, SIGNATURE_LENGTH, Charsets.US_ASCII)
        if (!SIGNATURE_PATTERN.matches(signature)) {
            throw IOException("SPTHEADER[0-9] signature was not found at 0x30.")
        }
    }

    private fun readTable(
        decoded: ByteArray,
        name: String,
        countOffset: Int,
        baseOffset: Int
    ): SystemNnnOffsetTable {
        val count = readUInt32LE(decoded, countOffset)
        val logicalBase = readUInt32LE(decoded, baseOffset)
        if (count > MAX_TABLE_ENTRIES) {
            throw IOException("The " + name + " SPT entry count exceeds the parser limit.")
        }
        return SystemNnnOffsetTable(name, count, logicalBase)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): UInt {
        if (offset < 0 || offset > bytes.size - UInt.SIZE_BYTES) {
            throw IOException("A 32-bit SPT field extends beyond the file.")
        }
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        return value.toUInt()
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun alignToFourBytes(value: Long): Long =
        (value + (ADDRESS_ALIGNMENT_BYTES - 1L)) and -ADDRESS_ALIGNMENT_BYTES

    companion object {
        const val SIGNATURE_OFFSET = 0x30
        const val FIXED_HEADER_SIZE = 0x40

        private const val FIRST_HEADER_WORD_OFFSET = 0x00
        private const val MAIN_TABLE_COUNT_OFFSET = 0x10
        private const val MAIN_TABLE_BASE_OFFSET = 0x14
        private const val SECONDARY_TABLE_COUNT_OFFSET = 0x18
        private const val SECONDARY_TABLE_BASE_OFFSET = 0x1C
        private const val TERTIARY_TABLE_COUNT_OFFSET = 0x20
        private const val TERTIARY_TABLE_BASE_OFFSET = 0x24
        private const val AUXILIARY_TABLE_COUNT_OFFSET = 0x28
        private const val AUXILIARY_TABLE_BASE_OFFSET = 0x2C

        private const val UINT32_BYTES = 4L
        private const val UINT32_MAX = 0xFFFF_FFFFL
        private const val ADDRESS_ALIGNMENT_BYTES = 4L
        private const val ADDRESS_CELL_BYTES = 4L
        private const val INTER_POOL_STUB_BYTES = 16L
        private const val DEFAULT_MAX_FILE_SIZE_BYTES = 64L * 1024L * 1024L
        private const val MAX_TABLE_ENTRIES = 1_000_000u
        private const val MAX_TEXT_ENTRY_BYTES = 1024 * 1024

        private const val SIGNATURE_LENGTH = 10
        private val SIGNATURE_PATTERN = Regex("SPTHEADER[0-9]")
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        /** Signature-only probe for fast directory detection; full validation is performed by [load]. */
        fun hasValidHeader(file: File): Boolean = runCatching {
            if (!file.isFile) return@runCatching false
            FileRandomAccessSource(file).use { source ->
                hasValidHeader(source)
            }
        }.getOrDefault(false)

        /** Checks the fixed XOR-demasked signature without taking ownership of [source]. */
        fun hasValidHeader(source: ReadOnlyRandomAccessSource): Boolean {
            val signatureEnd = SIGNATURE_OFFSET + SIGNATURE_LENGTH
            if (source.sizeBytes < signatureEnd.toLong()) return false

            return runCatching {
                val masked = ByteArray(signatureEnd)
                source.readFullyAt(0L, masked)
                for (index in masked.indices) {
                    masked[index] = (masked[index].toInt() xor 0xFF).toByte()
                }
                val signature = String(masked, SIGNATURE_OFFSET, SIGNATURE_LENGTH, Charsets.US_ASCII)
                SIGNATURE_PATTERN.matches(signature)
            }.getOrDefault(false)
        }
    }
}
