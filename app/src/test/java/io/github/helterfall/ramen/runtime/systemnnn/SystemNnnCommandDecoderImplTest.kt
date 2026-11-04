package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnTextEntry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalUnsignedTypes::class)
class SystemNnnCommandDecoderImplTest {

    @Test
    fun decodesPrintAsTheExactPrimaryPoolEntry() {
        val script = testScript(
            listOf(record(5, SYSTEM_COMMAND, CODE_PRINT, 1u, 11u)),
            textCount = 2
        )

        val decoded = decoder().decodeNext(script, RECORD_START)

        assertEquals(
            SystemNnnDecodedCommand.Dialogue(
                nextCommandOffset = RECORD_START + 20L,
                textEntryOffset = script.textPool[1].offset
            ),
            decoded
        )
    }

    @Test
    fun decodesLprintAndAppendUsingTheSameVerifiedMessageReference() {
        for (code in listOf(CODE_LPRINT, CODE_APPEND)) {
            val script = testScript(
                listOf(record(5, SYSTEM_COMMAND, code, 0u, 7u)),
                textCount = 1
            )

            val decoded = decoder().decodeNext(script, RECORD_START)

            assertEquals(
                SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = RECORD_START + 20L,
                    textEntryOffset = script.textPool[0].offset
                ),
                decoded
            )
        }
    }

    @Test
    fun rejectsPrintReferencesOutsideThePrimaryTextPool() {
        val script = testScript(
            listOf(record(5, SYSTEM_COMMAND, CODE_PRINT, 1u, 0u)),
            textCount = 1
        )

        assertThrows(IOException::class.java) {
            decoder().decodeNext(script, RECORD_START)
        }
    }

    @Test
    fun skipsDataAndNonBlockingSystemFunctionRecords() {
        val script = testScript(
            listOf(
                record(4, DATA, 0x55550002u, 0xABCDu),
                record(5, SYSTEM_FUNCTION, 0x33330051u, 1u, 0u),
                record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 1u)
            ),
            textCount = 1
        )
        val decoder = decoder()

        val first = decoder.decodeNext(script, RECORD_START)
        val second = decoder.decodeNext(script, RECORD_START + 16L)
        val third = decoder.decodeNext(script, RECORD_START + 36L)

        assertEquals(SystemNnnDecodedCommand.Skip(RECORD_START + 16L), first)
        assertEquals(SystemNnnDecodedCommand.Skip(RECORD_START + 36L), second)
        assertEquals(script.textPool[0].offset, (third as SystemNnnDecodedCommand.Dialogue).textEntryOffset)
    }

    @Test
    fun skipsEndKomaAndEndIfControlRecords() {
        for (code in listOf(CODE_CONTROL_ENDKOMA, CODE_CONTROL_ENDIF, CODE_CONTROL_NOP)) {
            val script = testScript(
                listOf(record(3, CONTROL, code)),
                textCount = 0
            )

            assertEquals(
                SystemNnnDecodedCommand.Skip(RECORD_START + 12L),
                decoder().decodeNext(script, RECORD_START)
            )
        }
    }

    @Test
    fun reportsEndAtTheValidatedRecordStreamBoundary() {
        val script = testScript(
            listOf(record(3, CONTROL, CODE_CONTROL_END)),
            textCount = 0
        )
        val decoder = decoder()

        assertEquals(SystemNnnDecodedCommand.End, decoder.decodeNext(script, RECORD_START))
        assertEquals(
            SystemNnnDecodedCommand.End,
            decoder.decodeNext(script, RECORD_START + 12L)
        )
    }

    @Test
    fun treatsEndFilmAsTheEndOfTheCurrentFilm() {
        val script = testScript(
            listOf(record(3, CONTROL, CODE_CONTROL_ENDFILM)),
            textCount = 0
        )

        assertEquals(SystemNnnDecodedCommand.End, decoder().decodeNext(script, RECORD_START))
    }

    @Test
    fun rejectsControlFlowThatRequiresAnUnimplementedExpressionEvaluator() {
        val script = testScript(
            listOf(record(3, CONTROL, CODE_CONTROL_IF)),
            textCount = 0
        )

        val failure = assertThrows(IOException::class.java) {
            decoder().decodeNext(script, RECORD_START)
        }

        assertEquals(true, failure.message?.contains("Unsupported System-NNN record"))
    }

    @Test
    fun rejectsOffsetsThatDoNotPointToRecordBoundaries() {
        val script = testScript(
            listOf(record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u)),
            textCount = 1
        )

        assertThrows(IOException::class.java) {
            decoder().decodeNext(script, RECORD_START + 4L)
        }
    }

    @Test
    fun returnsEndWhenTheCursorReachesTheEndOfTheRecordArea() {
        val script = testScript(
            listOf(record(3, CONTROL, CODE_CONTROL_NOP)),
            textCount = 0
        )
        val streamEnd = RECORD_START + 12L

        assertEquals(SystemNnnDecodedCommand.Skip(streamEnd), decoder().decodeNext(script, RECORD_START))
        assertEquals(SystemNnnDecodedCommand.End, decoder().decodeNext(script, streamEnd))
    }

    private fun decoder() = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)

    private fun testScript(
        records: List<List<UInt>>,
        textCount: Int
    ): SystemNnnScript {
        val words = listOf(0u, 0u, 0u, 0u) + records.flatten() + listOf(0u, 0u, 0u, 0u)
        val bytes = ByteArray(words.size * DWORD_BYTES.toInt())
        words.forEachIndexed { index, word -> putUInt32(bytes, index * DWORD_BYTES.toInt(), word) }

        val commandEnd = COMMAND_START + bytes.size
        val firstTextOffset = commandEnd + 0x10L
        return SystemNnnScript(
            fileSizeBytes = firstTextOffset + textCount * 0x20L,
            firstHeaderWord = 0u,
            offsetTables = emptyList(),
            commandStartOffset = COMMAND_START,
            commandEndOffset = commandEnd,
            textPoolOffset = firstTextOffset,
            textPool = List(textCount) { index ->
                SystemNnnTextEntry(
                    offset = firstTextOffset + index * 0x20L,
                    text = "Synthetic dialogue " + index
                )
            },
            commandBytes = bytes
        )
    }

    private fun record(
        lengthWords: Int,
        identifier: UInt,
        code: UInt,
        vararg payload: UInt
    ): List<UInt> =
        listOf(lengthWords.toUInt(), identifier, code) + payload.toList()

    private fun putUInt32(bytes: ByteArray, offset: Int, value: UInt) {
        repeat(DWORD_BYTES.toInt()) { index ->
            bytes[offset + index] = ((value shr (index * 8)) and 0xFFu).toByte()
        }
    }

    private companion object {
        const val DWORD_BYTES = 4L
        const val COMMAND_START = 0x40L
        const val RECORD_START = 0x50L

        const val DATA = 0x66660001u
        const val CONTROL = 0x66660002u
        const val SYSTEM_COMMAND = 0x66660006u
        const val SYSTEM_FUNCTION = 0x66660007u

        const val CODE_PRINT = 0x22220001u
        const val CODE_LPRINT = 0x22220002u
        const val CODE_APPEND = 0x22220003u
        const val CODE_CONTROL_NOP = 0x55560001u
        const val CODE_CONTROL_END = 0x55560005u
        const val CODE_CONTROL_IF = 0x55560002u
        const val CODE_CONTROL_ENDKOMA = 0x5556000Eu
        const val CODE_CONTROL_ENDIF = 0x55560013u
        const val CODE_CONTROL_ENDFILM = 0x5556000Cu
    }
}
