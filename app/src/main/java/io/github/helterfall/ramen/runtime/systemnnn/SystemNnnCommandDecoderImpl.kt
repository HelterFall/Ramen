package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.IOException

/**
 * Provisional playback adapter for the validated SPT command byte region.
 *
 * The header's main-table entryCount (currently 1,299) is descriptor metadata,
 * not the end of the executable command stream. The stream continues through
 * commandEndOffset; the Shift-JIS text pool starts after that region.
 * This remains a provisional adapter rather than a verified opcode interpreter.
 */
class SystemNnnCommandDecoderImpl(
    private val gtbResourceCount: Int
) : SystemNnnCommandDecoder {

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
                "Unexpected System-NNN main-table descriptor at 0xC5BA."
            )
        }

        // entryCount=1,299 describes the header's table metadata. The observed
        // executable byte window is independently bounded by the parser and
        // extends to commandEndOffset, well beyond those first 1,299 words.
        val commandEndOffset = script.commandEndOffset
        if (script.commandStartOffset != MAIN_TABLE_OFFSET ||
            commandEndOffset <= MAIN_TABLE_OFFSET ||
            commandEndOffset > script.fileSizeBytes ||
            commandEndOffset > script.textPoolOffset
        ) {
            throw IOException("The SPT command region has invalid validated file bounds.")
        }

        if (commandOffset == commandEndOffset) {
            return SystemNnnDecodedCommand.End
        }
        if (commandOffset < MAIN_TABLE_OFFSET || commandOffset > commandEndOffset) {
            throw IOException("Command cursor is outside the validated SPT command region.")
        }

        val recordOffset = commandOffset - MAIN_TABLE_OFFSET
        if (recordOffset % UINT32_BYTES != 0L ||
            commandOffset + UINT32_BYTES > commandEndOffset
        ) {
            throw IOException("Command cursor is not aligned to a complete four-byte command word.")
        }

        val bytes = try {
            script.readCommandBytes(commandOffset, UINT32_BYTES.toInt())
        } catch (error: IllegalArgumentException) {
            throw IOException("Could not read a bounded transition-table entry.", error)
        }
        val value = readUInt32LittleEndian(bytes)
        val nextCommandOffset = commandOffset + UINT32_BYTES

        return when {
            value >= script.textPoolOffset -> {
                val textEntry = script.textEntryAtOffset(value)
                if (textEntry == null) {
                    // Unmapped large values are engine markers/operands, not
                    // dialogue pointers. Skip this UInt32 word and keep scanning.
                    SystemNnnDecodedCommand.Skip(
                        nextCommandOffset = nextCommandOffset
                    )
                } else {
                    SystemNnnDecodedCommand.Dialogue(
                        nextCommandOffset = nextCommandOffset,
                        textEntryOffset = textEntry.offset
                    )
                }
            }

            value < gtbResourceCount.toLong() -> {
                SystemNnnDecodedCommand.SceneUpdate(
                    nextCommandOffset = nextCommandOffset,
                    backgroundResourceIndex = value
                )
            }

            else -> {
                // Values in the resource-pointer range that do not resolve to a
                // physical GTB row can be service words (coordinates, timing,
                // flags, etc.). Preserve forward progress instead of blocking
                // the scene; the next table entry may contain the dialogue pointer.
                SystemNnnDecodedCommand.Skip(
                    nextCommandOffset = nextCommandOffset
                )
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
    }
}
