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

            else -> throw unsupportedRecord(record)
        }
    }

    private fun decodeControl(record: SystemNnnCommandRecord): SystemNnnDecodedCommand {
        if (record.codeFamily == EXPRESSION_FAMILY) {
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
        const val CODE_CONTROL_ENDKOMA = 0x000E
        const val CODE_CONTROL_ENDIF = 0x0013

        const val MESSAGE_INDEX_PAYLOAD_WORD = 0
        const val MESSAGE_REFERENCE_WORD_COUNT = 2
    }
}
