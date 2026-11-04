package io.github.helterfall.ramen.parser

import java.io.IOException

/** Record classes from the 0x6666 identifier family. */
enum class SystemNnnRecordKind(val identifierType: Int) {
    DATA(1),
    CONTROL(2),
    COMMAND(3),
    FUNCTION(4),
    CALCULATION(5),
    SYSTEM_COMMAND(6),
    SYSTEM_FUNCTION(7);

    companion object {
        fun fromIdentifier(identifier: UInt): SystemNnnRecordKind? {
            if (((identifier shr 16).toInt() and 0xFFFF) != IDENTIFIER_FAMILY) return null
            val type = (identifier and 0xFFFFu).toInt()
            return values().firstOrNull { it.identifierType == type }
        }

        private const val IDENTIFIER_FAMILY = 0x6666
    }
}

/** Recognized high-word code families; this does not decode their operands. */
enum class SystemNnnCodeFamily(val value: Int) {
    DATA(0x5555),
    CONTROL(0x5556),
    SYSTEM_COMMAND(0x2222),
    SYSTEM_FUNCTION(0x3333),
    EXPRESSION(0xA000);

    companion object {
        fun fromCode(code: UInt): SystemNnnCodeFamily? {
            val family = (code shr 16).toInt() and 0xFFFF
            return values().firstOrNull { it.value == family }
        }
    }
}

/**
 * One length-prefixed record in the System-NNN code stream.
 *
 * [lengthWords] counts 32-bit words, including the length, identifier and
 * code words. Payload words are preserved in the source SPT and are not
 * interpreted by this structural reader.
 */
data class SystemNnnCommandRecord(
    val offset: Long,
    val endOffsetExclusive: Long,
    val lengthWords: UInt,
    val identifier: UInt,
    val code: UInt
) {
    val identifierType: Int
        get() = (identifier and 0xFFFFu).toInt()

    val codeFamily: Int
        get() = (code shr 16).toInt() and 0xFFFF

    val codeId: Int
        get() = (code and 0xFFFFu).toInt()

    val payloadWordCount: Int
        get() = lengthWords.toInt() - RECORD_HEADER_WORD_COUNT

    val recordKind: SystemNnnRecordKind?
        get() = SystemNnnRecordKind.fromIdentifier(identifier)

    val knownCodeFamily: SystemNnnCodeFamily?
        get() = SystemNnnCodeFamily.fromCode(code)

    private companion object {
        const val RECORD_HEADER_WORD_COUNT = 3
    }
}

/**
 * Structural map of one SPT module's length-prefixed code records.
 *
 * The four-word preamble and four-word trailer are retained as raw DWORDs.
 * Their meanings are intentionally not inferred here.
 */
data class SystemNnnCommandStream(
    val preambleWords: List<UInt>,
    val records: List<SystemNnnCommandRecord>,
    val trailerWords: List<UInt>,
    val recordStartOffset: Long,
    val recordEndOffsetExclusive: Long
)

/**
 * Reads the observed SPT record envelope without executing any command.
 *
 * Across the supplied module set, the bounded code area has a four-DWORD
 * preamble, a sequence of records whose first DWORD is their length in DWORDs,
 * and a four-DWORD trailer. Each record starts with:
 *   length, 0x6666xxxx identifier, command/code DWORD.
 *
 * Only the record envelope is decoded. Payload words, command meanings,
 * branch targets and resource operands remain opaque.
 */
class SystemNnnCommandStreamReader(
    private val preambleWordCount: Int = DEFAULT_PREAMBLE_WORD_COUNT,
    private val trailerWordCount: Int = DEFAULT_TRAILER_WORD_COUNT
) {
    init {
        require(preambleWordCount >= 0) { "preambleWordCount must not be negative." }
        require(trailerWordCount >= 0) { "trailerWordCount must not be negative." }
    }

    @Throws(IOException::class)
    fun read(script: SystemNnnScript): SystemNnnCommandStream {
        val commandByteCount = script.commandEndOffset - script.commandStartOffset
        if (commandByteCount < 0L || commandByteCount > Int.MAX_VALUE.toLong()) {
            throw IOException("The SPT code region has an invalid size.")
        }
        if (commandByteCount % DWORD_BYTES != 0L) {
            throw IOException("The SPT code region is not DWORD-aligned.")
        }

        val bytes = try {
            script.readCommandBytes(script.commandStartOffset, commandByteCount.toInt())
        } catch (failure: IllegalArgumentException) {
            throw IOException("Could not read the bounded SPT code region.", failure)
        }

        val preambleByteCount = preambleWordCount.toLong() * DWORD_BYTES
        val trailerByteCount = trailerWordCount.toLong() * DWORD_BYTES
        if (preambleByteCount + trailerByteCount > bytes.size.toLong()) {
            throw IOException("The SPT code region is shorter than its preamble and trailer.")
        }

        val recordStart = preambleByteCount.toInt()
        val recordEnd = (bytes.size.toLong() - trailerByteCount).toInt()
        if (recordStart % DWORD_BYTES.toInt() != 0 || recordEnd % DWORD_BYTES.toInt() != 0) {
            throw IOException("The SPT command record window is not DWORD-aligned.")
        }

        val preamble = readWords(bytes, 0, preambleWordCount)
        val trailer = readWords(bytes, recordEnd, trailerWordCount)
        val records = ArrayList<SystemNnnCommandRecord>()
        var cursor = recordStart

        while (cursor < recordEnd) {
            val remainingBytes = recordEnd - cursor
            if (remainingBytes < RECORD_HEADER_BYTES) {
                throw IOException("A System-NNN command record header is truncated.")
            }

            val lengthWords = readUInt32LE(bytes, cursor)
            val lengthWordCount = lengthWords.toLong()
            if (lengthWordCount < RECORD_HEADER_WORD_COUNT.toLong()) {
                throw IOException("A System-NNN command record has an invalid length.")
            }

            val lengthBytes = lengthWordCount * DWORD_BYTES
            if (lengthBytes > remainingBytes.toLong()) {
                throw IOException("A System-NNN command record extends beyond the code region.")
            }

            val identifier = readUInt32LE(bytes, cursor + DWORD_BYTES.toInt())
            val identifierFamily = (identifier shr 16).toInt() and 0xFFFF
            val identifierType = (identifier and 0xFFFFu).toInt()
            if (identifierFamily != IDENTIFIER_FAMILY || identifierType !in MIN_IDENTIFIER_TYPE..MAX_IDENTIFIER_TYPE) {
                throw IOException("The SPT command record has an unsupported identifier.")
            }

            val code = readUInt32LE(bytes, cursor + (2L * DWORD_BYTES).toInt())
            val absoluteOffset = script.commandStartOffset + cursor.toLong()
            val endOffset = absoluteOffset + lengthBytes
            records += SystemNnnCommandRecord(
                offset = absoluteOffset,
                endOffsetExclusive = endOffset,
                lengthWords = lengthWords,
                identifier = identifier,
                code = code
            )

            cursor += lengthBytes.toInt()
        }

        return SystemNnnCommandStream(
            preambleWords = preamble,
            records = records,
            trailerWords = trailer,
            recordStartOffset = script.commandStartOffset + recordStart.toLong(),
            recordEndOffsetExclusive = script.commandStartOffset + recordEnd.toLong()
        )
    }

    /**
     * Resolves the entry point for a zero-based film index.
     *
     * The third header descriptor identifies a DWORD-indexed table embedded in
     * the SPT code region. The fourth descriptor's count is the reserved-entry
     * prefix used by the reference runtime; film zero follows that prefix.
     * The table value itself is a DWORD offset to a command record.
     */
    @Throws(IOException::class)
    fun filmStartOffset(script: SystemNnnScript, filmNumber: Int): Long {
        if (filmNumber < 0) {
            throw IOException("The SPT film number cannot be negative.")
        }
        val filmTable = script.offsetTables.getOrNull(FILM_TABLE_DESCRIPTOR_INDEX)
            ?: throw IOException("The SPT film-entry descriptor is missing.")
        val reservedEntries = script.offsetTables
            .getOrNull(RESERVED_FILM_ENTRIES_DESCRIPTOR_INDEX)
            ?.entryCount
            ?.toLong()
            ?: 0L
        val totalEntries = filmTable.entryCount.toLong()
        if (reservedEntries > totalEntries) {
            throw IOException("The SPT reserved-entry count exceeds the film-entry table.")
        }
        val entryIndex = reservedEntries + filmNumber.toLong()
        if (filmTable.entryCount == 0u || entryIndex >= totalEntries) {
            throw IOException("The requested film number is outside the SPT film-entry table.")
        }

        // Validate the whole table, not only the requested slot. In all supplied
        // modules, the reserved prefix targets SYSTEM_FUNCTION 0x3333/0x51;
        // all remaining slots target SYSTEM_FUNCTION 0x3333/0x50.
        val stream = read(script)
        var selectedTargetOffset: Long? = null
        var tableIndex = 0L
        while (tableIndex < totalEntries) {
            val logicalWordIndex = filmTable.logicalBase.toLong() + tableIndex
            if (logicalWordIndex > Long.MAX_VALUE / DWORD_BYTES) {
                throw IOException("The SPT film-entry address overflows the file offset range.")
            }
            val tableEntryOffset = logicalWordIndex * DWORD_BYTES
            val pointerBytes = try {
                script.readCommandBytes(tableEntryOffset, DWORD_BYTES.toInt())
            } catch (failure: IllegalArgumentException) {
                throw IOException(
                    "The SPT film-entry table is outside the validated code region.",
                    failure
                )
            }

            val targetWordOffset = readUInt32LE(pointerBytes, 0).toLong()
            val targetOffset = targetWordOffset * DWORD_BYTES
            val recordIndex = stream.records.binarySearchBy(targetOffset) { it.offset }
            val targetRecord = stream.records.getOrNull(recordIndex)
                ?: throw IOException(
                    "The SPT film table entry $tableIndex does not point to a command record boundary."
                )
            val expectedCodeId = if (tableIndex < reservedEntries) {
                RESERVED_FILM_ENTRY_CODE_ID
            } else {
                FILM_ENTRY_CODE_ID
            }
            if (
                targetRecord.recordKind != SystemNnnRecordKind.SYSTEM_FUNCTION ||
                targetRecord.codeFamily != SYSTEM_FUNCTION_FAMILY ||
                targetRecord.codeId != expectedCodeId
            ) {
                throw IOException(
                    "The SPT film table entry $tableIndex points to an unexpected " +
                        "System-NNN function record (expected 0x" +
                        expectedCodeId.toString(16) + ")."
                )
            }

            if (tableIndex == entryIndex) {
                selectedTargetOffset = targetOffset
            }
            tableIndex += 1L
        }

        return selectedTargetOffset
            ?: throw IOException("The requested SPT film entry could not be resolved.")
    }

    private fun readWords(bytes: ByteArray, startOffset: Int, count: Int): List<UInt> =
        List(count) { index -> readUInt32LE(bytes, startOffset + index * DWORD_BYTES.toInt()) }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): UInt {
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        return value.toUInt()
    }

    private companion object {
        const val DWORD_BYTES = 4L
        const val RECORD_HEADER_WORD_COUNT = 3
        const val RECORD_HEADER_BYTES = RECORD_HEADER_WORD_COUNT * DWORD_BYTES.toInt()
        const val IDENTIFIER_FAMILY = 0x6666
        const val MIN_IDENTIFIER_TYPE = 1
        const val MAX_IDENTIFIER_TYPE = 7
        const val DEFAULT_PREAMBLE_WORD_COUNT = 4
        const val DEFAULT_TRAILER_WORD_COUNT = 4
        const val FILM_TABLE_DESCRIPTOR_INDEX = 2
        const val RESERVED_FILM_ENTRIES_DESCRIPTOR_INDEX = 3
        const val SYSTEM_FUNCTION_FAMILY = 0x3333
        const val RESERVED_FILM_ENTRY_CODE_ID = 0x51
        const val FILM_ENTRY_CODE_ID = 0x50
    }
}
