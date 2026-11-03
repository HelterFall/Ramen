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
 * Describes one header table. A zero offset means that the table is declared
 * but has no separately addressable payload in this SPT variant.
 */
data class SystemNnnOffsetTable(
    val name: String,
    val entryCount: UInt,
    val offset: UInt
) {
    val hasPayload: Boolean
        get() = offset != 0u
}

/**
 * A byte-level command candidate, not a fully decoded VM instruction.
 *
 * The command grammar (operand lengths and opcode meanings) has not yet been
 * established. Keeping the original offset and byte lets a future interpreter
 * add semantic decoding without losing source coordinates.
 */
data class SystemNnnInstruction(
    val offset: Long,
    val opcode: Int
)

/** One NUL-delimited Shift-JIS string from the text/resource pool. */
data class SystemNnnTextEntry(
    val offset: Long,
    val text: String
) {
    /** UTF-8 representation for consumers that persist or pass text as bytes. */
    val utf8Bytes: ByteArray
        get() = text.toByteArray(Charsets.UTF_8)
}

/**
 * Read-only representation of a demasked System-NNN SPT script.
 *
 * The byte-wise instruction sequence is deliberately provisional: this class
 * identifies the validated command window but does not claim to understand
 * individual opcodes or operands yet.
 */
class SystemNnnScript internal constructor(
    val fileSizeBytes: Long,
    val firstHeaderWord: UInt,
    val offsetTables: List<SystemNnnOffsetTable>,
    val commandStartOffset: Long,
    val commandEndOffset: Long,
    val textPoolOffset: Long,
    val textPool: List<SystemNnnTextEntry>,
    private val commandBytes: ByteArray
) {
    val commandByteCount: Int
        get() = commandBytes.size

    // Text references are absolute offsets in the demasked SPT file.
    // Resolve them only at the exact start of a validated NUL-delimited entry.
    private val textEntriesByOffset: Map<Long, SystemNnnTextEntry> =
        textPool.associateBy(SystemNnnTextEntry::offset)

    fun textEntryAtOffset(absoluteOffset: Long): SystemNnnTextEntry? =
        textEntriesByOffset[absoluteOffset]

    /**
     * Reads a bounded command slice using an absolute file offset. A future
     * verified opcode decoder should use this instead of reading outside the
     * command region or treating every byte as an instruction.
     */
    fun readCommandBytes(absoluteOffset: Long, length: Int): ByteArray {
        require(length >= 0) { "length must not be negative" }
        require(absoluteOffset >= commandStartOffset) {
            "command offset is before the validated command region"
        }
        val relativeOffset = absoluteOffset - commandStartOffset
        require(relativeOffset <= commandBytes.size.toLong()) {
            "command offset is after the validated command region"
        }
        require(length.toLong() <= commandBytes.size.toLong() - relativeOffset) {
            "requested command bytes exceed the validated command region"
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

    /**
     * Iterates bytes from the bounded command window only. Header and offset
     * table bytes before commandStartOffset are never exposed by this iterator.
     */
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
 * Bounded parser for the observed XOR-masked System-NNN SPT layout.
 *
 * The parser does not execute script data. It validates the header and all
 * declared offsets, exposes the candidate command byte window, and decodes
 * NUL-delimited strings from the known Shift-JIS pool start.
 */
class SystemNnnScriptLoader(
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES
) {
    init {
        require(maxFileSizeBytes >= TEXT_POOL_OFFSET + 1L) {
            "maxFileSizeBytes is too small for an SPT script."
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

    /**
     * Loads from a caller-owned source. This method does not close [source].
     */
    @Throws(IOException::class)
    fun load(source: ReadOnlyRandomAccessSource): SystemNnnScript {
        val sourceSize = source.sizeBytes
        if (sourceSize < TEXT_POOL_OFFSET + 1L) {
            throw IOException("The SPT file is shorter than the known fixed layout.")
        }
        if (sourceSize > maxFileSizeBytes || sourceSize > Int.MAX_VALUE.toLong()) {
            throw IOException("The SPT file exceeds the configured parser size limit.")
        }

        val masked = ByteArray(sourceSize.toInt())
        source.readFullyAt(0L, masked)

        // The on-disk representation is XOR-masked byte-by-byte with 0xFF.
        val decoded = ByteArray(masked.size) { index ->
            (masked[index].toInt() xor 0xFF).toByte()
        }

        validateSignature(decoded)

        val firstWord = readUInt32LE(decoded, FIRST_HEADER_WORD_OFFSET)
        val tables = listOf(
            readTable(decoded, "main", MAIN_TABLE_COUNT_OFFSET, MAIN_TABLE_OFFSET_OFFSET),
            readTable(decoded, "secondary", SECONDARY_TABLE_COUNT_OFFSET, SECONDARY_TABLE_OFFSET_OFFSET),
            readTable(decoded, "tertiary", TERTIARY_TABLE_COUNT_OFFSET, TERTIARY_TABLE_OFFSET_OFFSET),
            readTable(decoded, "auxiliary", AUXILIARY_TABLE_COUNT_OFFSET, AUXILIARY_TABLE_OFFSET_OFFSET)
        )

        val mainTable = tables.first()
        if (mainTable.entryCount == 0u || mainTable.offset == 0u) {
            throw IOException("The main SPT table descriptor is empty.")
        }

        val commandStart = mainTable.offset.toLong()
        val commandEnd = COMMAND_REGION_END_OFFSET.toLong()
        val textStart = TEXT_POOL_OFFSET.toLong()

        if (commandStart < FIXED_HEADER_SIZE.toLong() || commandStart >= commandEnd) {
            throw IOException("The main table offset is outside the known command region.")
        }
        if (commandEnd > sourceSize || textStart >= sourceSize) {
            throw IOException("The command or text-pool boundary exceeds the file.")
        }

        val commandBytes = decoded.copyOfRange(
            commandStart.toInt(),
            commandEnd.toInt()
        )
        val textEntries = decodeTextPool(decoded, textStart.toInt())

        return SystemNnnScript(
            fileSizeBytes = sourceSize,
            firstHeaderWord = firstWord,
            offsetTables = tables,
            commandStartOffset = commandStart,
            commandEndOffset = commandEnd,
            textPoolOffset = textStart,
            textPool = textEntries,
            commandBytes = commandBytes
        )
    }

    private fun validateSignature(decoded: ByteArray) {
        val signatureEnd = SIGNATURE_OFFSET + SIGNATURE.size
        if (decoded.size < signatureEnd) {
            throw IOException("The SPT header is truncated.")
        }
        for (index in SIGNATURE.indices) {
            if (decoded[SIGNATURE_OFFSET + index] != SIGNATURE[index]) {
                throw IOException("SPTHEADER0 signature was not found at 0x30.")
            }
        }
    }

    private fun readTable(
        decoded: ByteArray,
        name: String,
        countOffset: Int,
        tableOffset: Int
    ): SystemNnnOffsetTable {
        val count = readUInt32LE(decoded, countOffset)
        val offset = readUInt32LE(decoded, tableOffset)

        if (count > MAX_TABLE_ENTRIES) {
            throw IOException("The $name table count exceeds the parser limit.")
        }
        if (offset != 0u && offset.toLong() >= decoded.size.toLong()) {
            throw IOException("The $name table offset points outside the file.")
        }
        return SystemNnnOffsetTable(name, count, offset)
    }

    private fun decodeTextPool(
        decoded: ByteArray,
        startOffset: Int
    ): List<SystemNnnTextEntry> {
        val entries = ArrayList<SystemNnnTextEntry>()
        var cursor = startOffset

        while (cursor < decoded.size && entries.size < MAX_TEXT_ENTRIES) {
            var end = cursor
            while (end < decoded.size && decoded[end] != 0.toByte()) {
                end += 1
            }

            val length = end - cursor
            if (length in 1..MAX_TEXT_ENTRY_BYTES) {
                val value = decodeStrictShiftJis(decoded, cursor, length)
                if (value != null && value.isNotEmpty() && value.all { character ->
                        character == '\r' ||
                            character == '\n' ||
                            character == '\t' ||
                            !Character.isISOControl(character)
                    }
                ) {
                    entries += SystemNnnTextEntry(
                        offset = cursor.toLong(),
                        text = value
                    )
                }
            }

            if (end == decoded.size) {
                break
            }
            cursor = end + 1
        }

        return entries
    }

    private fun decodeStrictShiftJis(
        bytes: ByteArray,
        offset: Int,
        length: Int
    ): String? {
        return try {
            SHIFT_JIS.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): UInt {
        if (offset < 0 || offset > bytes.size - UInt.SIZE_BYTES) {
            throw IOException("A 32-bit header field extends beyond the file.")
        }
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        return value.toUInt()
    }

    companion object {
        const val SIGNATURE_OFFSET = 0x30
        const val FIXED_HEADER_SIZE = 0x50
        const val COMMAND_REGION_END_OFFSET = 0x32B20
        const val TEXT_POOL_OFFSET = 0x32B64

        private const val FIRST_HEADER_WORD_OFFSET = 0x00
        private const val MAIN_TABLE_COUNT_OFFSET = 0x10
        private const val MAIN_TABLE_OFFSET_OFFSET = 0x14
        private const val SECONDARY_TABLE_COUNT_OFFSET = 0x18
        private const val SECONDARY_TABLE_OFFSET_OFFSET = 0x1C
        private const val TERTIARY_TABLE_COUNT_OFFSET = 0x20
        private const val TERTIARY_TABLE_OFFSET_OFFSET = 0x24
        private const val AUXILIARY_TABLE_COUNT_OFFSET = 0x28
        private const val AUXILIARY_TABLE_OFFSET_OFFSET = 0x2C

        private const val DEFAULT_MAX_FILE_SIZE_BYTES = 64L * 1024L * 1024L
        private const val MAX_TABLE_ENTRIES = 1_000_000u
        private const val MAX_TEXT_ENTRIES = 1_000_000
        private const val MAX_TEXT_ENTRY_BYTES = 1024 * 1024

        private val SIGNATURE = "SPTHEADER0".toByteArray(Charsets.US_ASCII)
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")

        /**
         * Fast signature probe for directory detection. It checks only the
         * XOR-demasked marker at 0x30; full structural validation is done by load().
         */
        fun hasValidHeader(file: File): Boolean = runCatching {
            if (!file.isFile) return@runCatching false
            FileRandomAccessSource(file).use { source ->
                hasValidHeader(source)
            }
        }.getOrDefault(false)

        /**
         * Checks the fixed signature without taking ownership of [source].
         */
        fun hasValidHeader(source: ReadOnlyRandomAccessSource): Boolean {
            val signatureEnd = SIGNATURE_OFFSET + SIGNATURE.size
            if (source.sizeBytes < signatureEnd.toLong()) return false

            return runCatching {
                val masked = ByteArray(signatureEnd)
                source.readFullyAt(0L, masked)
                for (index in masked.indices) {
                    masked[index] = (masked[index].toInt() xor 0xFF).toByte()
                }
                SIGNATURE.indices.all { index ->
                    masked[SIGNATURE_OFFSET + index] == SIGNATURE[index]
                }
            }.getOrDefault(false)
        }
    }
}
