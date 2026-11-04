package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnCommandRecord
import io.github.helterfall.ramen.parser.SystemNnnCommandStream
import io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader
import io.github.helterfall.ramen.parser.SystemNnnRecordKind
import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.IOException

/**
 * First executable subset of the System-NNN VM.
 *
 * The record envelope and message-reference layout are verified against the
 * bundled SPT modules and the public reference runtime. PRINT, LPRINT and
 * APPEND resolve their first payload DWORD through the module's primary text
 * pool. Commands whose control-flow or resource semantics are not implemented
 * fail explicitly instead of being treated as ordinary dialogue.
 */
class SystemNnnCommandDecoderImpl(
    private val gtbResourceCount: Int,
    private val streamReader: SystemNnnCommandStreamReader = SystemNnnCommandStreamReader()
) : SystemNnnCommandDecoder {

    private var cachedScript: SystemNnnScript? = null
    private var cachedStream: SystemNnnCommandStream? = null

    init {
        require(gtbResourceCount >= 0) { "gtbResourceCount must not be negative." }
    }

    @Synchronized
    override fun decodeNext(
        script: SystemNnnScript,
        commandOffset: Long
    ): SystemNnnDecodedCommand {
        val stream = streamFor(script)
        if (commandOffset == stream.recordEndOffsetExclusive) {
            return SystemNnnDecodedCommand.End
        }

        val recordIndex = stream.records.binarySearchBy(commandOffset) { it.offset }
        if (recordIndex < 0) {
            throw IOException("The SPT command cursor is not on a record boundary.")
        }
        val record = stream.records[recordIndex]

        return when (record.recordKind) {
            SystemNnnRecordKind.DATA,
            SystemNnnRecordKind.SYSTEM_FUNCTION -> {
                // Data records are metadata. System functions are non-blocking
                // in the reference VM; visual/audio effects are added separately.
                SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
            }

            SystemNnnRecordKind.SYSTEM_COMMAND -> decodeSystemCommand(script, record)

            SystemNnnRecordKind.CONTROL -> decodeControl(record)

            SystemNnnRecordKind.COMMAND,
            SystemNnnRecordKind.FUNCTION,
            SystemNnnRecordKind.CALCULATION,
            null -> throw unsupportedRecord(record)
        }
    }

    private fun decodeSystemCommand(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand {
        if (record.codeFamily != SYSTEM_COMMAND_FAMILY) {
            throw unsupportedRecord(record)
        }

        return when (record.codeId) {
            CODE_PRINT,
            CODE_LPRINT,
            CODE_APPEND -> {
                if (record.payloadWordCount < MESSAGE_REFERENCE_WORD_COUNT) {
                    throw IOException("A System-NNN print command has a truncated message reference.")
                }

                val messageIndex = readPayloadWord(script, record, MESSAGE_INDEX_PAYLOAD_WORD).toLong()
                val textEntry = script.textPool.getOrNull(messageIndex.toInt())
                    ?.takeIf { messageIndex <= Int.MAX_VALUE.toLong() }
                    ?: throw IOException(
                        "A System-NNN print command references missing primary text entry " +
                            messageIndex + "."
                    )

                SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = record.endOffsetExclusive,
                    textEntryOffset = textEntry.offset
                )
            }

            CODE_SELECT -> decodeSelect(script, streamFor(script), record)

            else -> throw unsupportedRecord(record)
        }
    }

    private fun decodeSelect(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        record: SystemNnnCommandRecord
    ): SystemNnnDecodedCommand.Choice {
        if (record.payloadWordCount < 1) {
            throw IOException("A System-NNN select command has no option count.")
        }

        val optionCountValue = readPayloadWord(script, record, 0).toLong()
        if (optionCountValue !in 1L..MAX_SELECT_OPTIONS.toLong()) {
            throw IOException("A System-NNN select command has an invalid option count.")
        }
        val optionCount = optionCountValue.toInt()

        // Reference runtime: old format stores count + option refs only.
        // Newer records append timing/serial fields and put message-prefix count
        // in the final DWORD.
        val prefixCount = if (record.lengthWords.toLong() == optionCountValue + 5L) {
            0
        } else {
            if (record.payloadWordCount < 2) {
                throw IOException("A System-NNN select command has a truncated header.")
            }
            val value = readPayloadWord(script, record, record.payloadWordCount - 1).toLong()
            if (value !in 0L..MAX_SELECT_OPTIONS.toLong()) {
                throw IOException("A System-NNN select command has an invalid prefix count.")
            }
            value.toInt()
        }

        val totalTextCount = optionCount + prefixCount
        if (1 + totalTextCount > record.payloadWordCount) {
            throw IOException("A System-NNN select command has truncated option text references.")
        }

        // The prefix entries are non-select message rows. The reference runtime
        // drops their leading marker character before displaying them.
        val prefixLines = ArrayList<String>(prefixCount)
        repeat(prefixCount) { index ->
            val textIndex = readPayloadWord(script, record, 1 + index).toLong()
            val entry = script.secondaryTextPool.getOrNull(textIndex.toInt())
                ?.takeIf { textIndex <= Int.MAX_VALUE.toLong() }
                ?: throw IOException("A System-NNN select prefix references a missing secondary text entry.")
            prefixLines += entry.text.drop(1)
        }

        val options = ArrayList<SystemNnnChoiceOption>(optionCount)
        repeat(optionCount) { index ->
            val caseNumber = index + 1
            val textIndex = readPayloadWord(script, record, 1 + prefixCount + index).toLong()
            val entry = script.secondaryTextPool.getOrNull(textIndex.toInt())
                ?.takeIf { textIndex <= Int.MAX_VALUE.toLong() }
                ?: throw IOException("A System-NNN select option references a missing secondary text entry.")
            val target = findCaseTarget(script, stream, record.endOffsetExclusive, caseNumber)
            options += SystemNnnChoiceOption(
                id = "case-$caseNumber",
                label = entry.text,
                targetCommandOffset = target
            )
        }

        return SystemNnnDecodedCommand.Choice(
            promptTextEntryOffset = null,
            options = options,
            promptText = prefixLines.joinToString("\n")
        )
    }

    /**
     * Mirrors the reference runtime's CASE scan: each CASE contains a 1-based
     * option number. A following opening-brace record stores the block span in
     * DWORDs; skipping it lands on the closing brace and then the next CASE.
     */
    private fun findCaseTarget(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        afterSelectOffset: Long,
        requestedCase: Int
    ): Long {
        var cursor = afterSelectOffset
        val first = recordAt(stream, cursor)
        if (isOpenBrace(first)) {
            cursor = skipBraceBlock(script, stream, first!!)
        }

        repeat(MAX_CASE_SCAN_RECORDS) {
            val caseRecord = recordAt(stream, cursor)
                ?: throw IOException("A System-NNN select command has no CASE record for option $requestedCase.")
            if (caseRecord.recordKind != SystemNnnRecordKind.CONTROL ||
                caseRecord.codeFamily != CONTROL_FAMILY ||
                caseRecord.codeId != CODE_CONTROL_CASE
            ) {
                throw IOException("A System-NNN select branch does not begin with a CASE record.")
            }
            if (caseRecord.payloadWordCount < 1) {
                throw IOException("A System-NNN CASE record has no case number.")
            }
            val caseNumber = (readPayloadWord(script, caseRecord, 0).toInt() and 0xFFFF)
            if (caseNumber == requestedCase) return caseRecord.endOffsetExclusive

            val body = recordAt(stream, caseRecord.endOffsetExclusive)
                ?: throw IOException("A System-NNN CASE block is truncated.")
            cursor = if (isOpenBrace(body)) {
                skipBraceBlock(script, stream, body!!)
            } else {
                body.endOffsetExclusive
            }
        }

        throw IOException("The System-NNN select branch table exceeded the scan limit.")
    }

    private fun skipBraceBlock(
        script: SystemNnnScript,
        stream: SystemNnnCommandStream,
        openingBrace: SystemNnnCommandRecord
    ): Long {
        if (openingBrace.payloadWordCount < 1) {
            throw IOException("A System-NNN opening brace has no block length.")
        }
        val spanWords = readPayloadWord(script, openingBrace, 0).toLong()
        if (spanWords <= 0L || spanWords > MAX_BRACE_SPAN_WORDS) {
            throw IOException("A System-NNN opening brace has an invalid block length.")
        }
        val closingBraceOffset = openingBrace.offset + spanWords * DWORD_BYTES
        val closingBrace = recordAt(stream, closingBraceOffset)
            ?: throw IOException("A System-NNN brace block does not end on a record boundary.")
        return if (isCloseBrace(closingBrace)) {
            closingBrace.endOffsetExclusive
        } else {
            closingBraceOffset
        }
    }

    private fun recordAt(stream: SystemNnnCommandStream, offset: Long): SystemNnnCommandRecord? {
        val index = stream.records.binarySearchBy(offset) { it.offset }
        return stream.records.getOrNull(index)
    }

    private fun isOpenBrace(record: SystemNnnCommandRecord?): Boolean =
        record?.recordKind == SystemNnnRecordKind.CONTROL &&
            record.codeFamily == EXPRESSION_FAMILY &&
            record.codeId == CODE_OPEN_BRACE

    private fun isCloseBrace(record: SystemNnnCommandRecord?): Boolean =
        record?.recordKind == SystemNnnRecordKind.CONTROL &&
            record.codeFamily == EXPRESSION_FAMILY &&
            record.codeId == CODE_CLOSE_BRACE

    private fun decodeControl(record: SystemNnnCommandRecord): SystemNnnDecodedCommand {
        if (record.codeFamily == EXPRESSION_FAMILY) {
            if (record.codeId == CODE_OPEN_BRACE || record.codeId == CODE_CLOSE_BRACE) {
                return SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)
            }
            throw unsupportedRecord(record)
        }
        if (record.codeFamily != CONTROL_FAMILY) {
            throw unsupportedRecord(record)
        }

        return when (record.codeId) {
            CODE_CONTROL_NOP,
            CODE_CONTROL_ENDKOMA,
            CODE_CONTROL_ENDIF -> SystemNnnDecodedCommand.Skip(record.endOffsetExclusive)

            CODE_CONTROL_END,
            CODE_CONTROL_ENDFILM -> SystemNnnDecodedCommand.End

            else -> throw unsupportedRecord(record)
        }
    }

    private fun streamFor(script: SystemNnnScript): SystemNnnCommandStream {
        if (cachedScript !== script || cachedStream == null) {
            cachedStream = streamReader.read(script)
            cachedScript = script
        }
        return checkNotNull(cachedStream)
    }

    private fun readPayloadWord(
        script: SystemNnnScript,
        record: SystemNnnCommandRecord,
        payloadIndex: Int
    ): UInt {
        if (payloadIndex !in 0 until record.payloadWordCount) {
            throw IOException("The SPT command payload index is outside the record.")
        }
        val absoluteOffset = record.offset + (RECORD_HEADER_WORDS + payloadIndex) * DWORD_BYTES
        val bytes = try {
            script.readCommandBytes(absoluteOffset, DWORD_BYTES.toInt())
        } catch (failure: IllegalArgumentException) {
            throw IOException("Could not read the bounded SPT command payload.", failure)
        }
        return (bytes[0].toInt() and 0xFF).toUInt() or
            ((bytes[1].toInt() and 0xFF).toUInt() shl 8) or
            ((bytes[2].toInt() and 0xFF).toUInt() shl 16) or
            ((bytes[3].toInt() and 0xFF).toUInt() shl 24)
    }

    private fun unsupportedRecord(record: SystemNnnCommandRecord): IOException =
        IOException(
            "Unsupported System-NNN record at 0x" +
                record.offset.toString(16) +
                " (identifier=0x" + record.identifier.toString(16) +
                ", code=0x" + record.code.toString(16) + ")."
        )

    private companion object {
        const val DWORD_BYTES = 4L
        const val RECORD_HEADER_WORDS = 3
        const val SYSTEM_COMMAND_FAMILY = 0x2222
        const val CONTROL_FAMILY = 0x5556
        const val EXPRESSION_FAMILY = 0xA000

        const val CODE_PRINT = 0x0001
        const val CODE_LPRINT = 0x0002
        const val CODE_APPEND = 0x0003

        const val CODE_CONTROL_NOP = 0x0001
        const val CODE_CONTROL_END = 0x0005
        const val CODE_CONTROL_ENDFILM = 0x000C
        const val CODE_CONTROL_ENDKOMA = 0x000E
        const val CODE_CONTROL_ENDIF = 0x0013
        const val CODE_CONTROL_CASE = 0x0008
        const val CODE_OPEN_BRACE = 0x007B
        const val CODE_CLOSE_BRACE = 0x007D
        const val MAX_SELECT_OPTIONS = 64
        const val MAX_CASE_SCAN_RECORDS = 4096
        const val MAX_BRACE_SPAN_WORDS = 1_000_000L
        const val CODE_SELECT = 0x0006

        const val MESSAGE_INDEX_PAYLOAD_WORD = 0
        const val MESSAGE_REFERENCE_WORD_COUNT = 2
    }
}
