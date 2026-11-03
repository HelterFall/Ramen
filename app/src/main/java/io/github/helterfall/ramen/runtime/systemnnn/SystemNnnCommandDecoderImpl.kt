package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.IOException

/**
 * Interpreter for the verified fixed-width System-NNN scene pointer table.
 *
 * The 1,299-entry table at 0xC5BA consists of UInt32 little-endian values.
 * Values in the parsed GTB range become indexed scene updates. Other
 * transition-table values advance through the validated Shift-JIS text pool
 * in order. This fallback keeps dialogue presentation paced by user taps
 * while the remaining System-NNN opcode grammar is being reverse engineered.
 */
class SystemNnnCommandDecoderImpl(
    private val gtbResourceCount: Int
) : SystemNnnCommandDecoder {

    private var textPoolScript: SystemNnnScript? = null
    private var nextTextEntryIndex: Int = 0

    init {
        require(gtbResourceCount >= 0) { "gtbResourceCount must not be negative." }
    }

    override fun decodeNext(
        script: SystemNnnScript,
        commandOffset: Long
    ): SystemNnnDecodedCommand {
        val mainTable = script.offsetTables.firstOrNull { it.name == MAIN_TABLE_NAME }
            ?: throw IOException("The SPT main transition table is missing.")

        if (mainTable.offset.toLong() != MAIN_TABLE_OFFSET ||
            mainTable.entryCount.toLong() != MAIN_TABLE_ENTRY_COUNT
        ) {
            throw IOException(
                "Unexpected System-NNN transition table: expected 1,299 UInt32 entries at 0xC5BA."
            )
        }

        val tableEndOffset = MAIN_TABLE_OFFSET + MAIN_TABLE_BYTE_COUNT
        if (script.commandStartOffset != MAIN_TABLE_OFFSET ||
            tableEndOffset > script.commandEndOffset ||
            tableEndOffset > script.fileSizeBytes
        ) {
            throw IOException("The 1,299-entry transition table extends beyond the validated SPT data.")
        }

        if (commandOffset == tableEndOffset) {
            return SystemNnnDecodedCommand.End
        }
        if (commandOffset < MAIN_TABLE_OFFSET || commandOffset > tableEndOffset) {
            throw IOException("Command cursor is outside the System-NNN transition table.")
        }

        val recordOffset = commandOffset - MAIN_TABLE_OFFSET
        if (recordOffset % UINT32_BYTES != 0L ||
            recordOffset >= MAIN_TABLE_BYTE_COUNT
        ) {
            throw IOException("Command cursor is not aligned to a four-byte transition-table entry.")
        }

        val bytes = try {
            script.readCommandBytes(commandOffset, UINT32_BYTES.toInt())
        } catch (error: IllegalArgumentException) {
            throw IOException("Could not read a bounded transition-table entry.", error)
        }
        val value = readUInt32LittleEndian(bytes)
        val nextCommandOffset = commandOffset + UINT32_BYTES

        if (textPoolScript !== script) {
            textPoolScript = script
            nextTextEntryIndex = 0
        }

        return when {
            value < gtbResourceCount.toLong() -> {
                SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = nextCommandOffset,
                    backgroundResourceIndex = value
                )
            }

            else -> {
                val textEntry = script.textPool.getOrNull(nextTextEntryIndex++)
                if (textEntry != null) {
                    SystemNnnDecodedCommand.Dialogue(
                        nextCommandOffset = nextCommandOffset,
                        textEntryOffset = textEntry.offset
                    )
                } else {
                    SystemNnnDecodedCommand.Skip(
                        nextCommandOffset = nextCommandOffset
                    )
                }
            }
        }
    }

    private fun readUInt32LittleEndian(bytes: ByteArray): Long {
        if (bytes.size != UINT32_BYTES.toInt()) {
            throw IOException("A System-NNN transition-table entry must contain exactly four bytes.")
        }
        return (bytes[0].toLong() and 0xFFL) or
            ((bytes[1].toLong() and 0xFFL) shl 8) or
            ((bytes[2].toLong() and 0xFFL) shl 16) or
            ((bytes[3].toLong() and 0xFFL) shl 24)
    }

    companion object {
        private const val MAIN_TABLE_NAME = "main"
        private const val MAIN_TABLE_OFFSET = 0xC5BAL
        private const val MAIN_TABLE_ENTRY_COUNT = 1_299L
        private const val UINT32_BYTES = 4L
        private const val MAIN_TABLE_BYTE_COUNT = MAIN_TABLE_ENTRY_COUNT * UINT32_BYTES
    }
}
