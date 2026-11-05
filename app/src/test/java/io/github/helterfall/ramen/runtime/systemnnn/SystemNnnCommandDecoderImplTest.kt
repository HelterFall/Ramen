package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnOffsetTable
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


    @Test
    fun decodesSelectOptionsFromSecondaryPoolAndMapsThemToCaseBranches() {
        val script = testScript(
            records = listOf(
                record(10, SYSTEM_COMMAND, CODE_SELECT, 2u, 2u, 3u, 0u, 0u, 0u, 0u),
                record(4, CONTROL, CODE_CONTROL_CASE, 1u),
                record(4, CONTROL, CODE_OPEN_BRACE, 7u),
                record(3, CALCULATION, 0u),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                record(4, CONTROL, CODE_CONTROL_CASE, 2u)
            ),
            textCount = 0,
            secondaryTexts = listOf("unused", "unused", "Take path A", "Take path B")
        )

        val decoded = decoder().decodeNext(script, RECORD_START) as SystemNnnDecodedCommand.Choice

        assertEquals("", decoded.promptText)
        assertEquals(listOf("Take path A", "Take path B"), decoded.options.map { it.label })
        assertEquals(listOf("case-1", "case-2"), decoded.options.map { it.id })
        assertEquals(RECORD_START + 40L + 16L, decoded.options[0].targetCommandOffset)
        assertEquals(RECORD_START + 40L + 16L + 16L + 12L + 12L + 16L, decoded.options[1].targetCommandOffset)
    }

    @Test
    fun decodesSelectMessagePrefixBeforeTheSelectableRows() {
        val script = testScript(
            records = listOf(
                record(11, SYSTEM_COMMAND, CODE_SELECT, 2u, 2u, 3u, 4u, 0u, 0u, 0u, 1u),
                record(4, CONTROL, CODE_CONTROL_CASE, 1u),
                record(4, CONTROL, CODE_OPEN_BRACE, 7u),
                record(3, CALCULATION, 0u),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                record(4, CONTROL, CODE_CONTROL_CASE, 2u)
            ),
            textCount = 0,
            secondaryTexts = listOf("unused", "unused", ">Intro line", "Option A", "Option B")
        )

        val decoded = decoder().decodeNext(script, RECORD_START) as SystemNnnDecodedCommand.Choice

        assertEquals("Intro line", decoded.promptText)
        assertEquals(listOf("Option A", "Option B"), decoded.options.map { it.label })
    }

    @Test
    fun rejectsSelectOptionsThatReferenceMissingSecondaryText() {
        val script = testScript(
            records = listOf(
                record(10, SYSTEM_COMMAND, CODE_SELECT, 2u, 2u, 99u, 0u, 0u, 0u, 0u),
                record(4, CONTROL, CODE_CONTROL_CASE, 1u),
                record(4, CONTROL, CODE_OPEN_BRACE, 7u),
                record(3, CALCULATION, 0u),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                record(4, CONTROL, CODE_CONTROL_CASE, 2u)
            ),
            textCount = 0,
            secondaryTexts = listOf("unused", "unused", "Option A")
        )

        assertThrows(IOException::class.java) {
            decoder().decodeNext(script, RECORD_START)
        }
    }


    @Test
    fun executesLocalSubThroughDescriptorThreeAndReturnsToTheExactCallerContinuation() {
        val script = localSubroutineScript()
        val decoder = decoder()

        val call = decoder.decodeNext(script, LOCAL_SUB_OFFSET)
        assertEquals(SystemNnnDecodedCommand.Jump(LOCAL_SUB_TARGET_OFFSET), call)

        assertEquals(
            SystemNnnDecodedCommand.Skip(LOCAL_SUB_TARGET_OFFSET + 20L),
            decoder.decodeNext(script, LOCAL_SUB_TARGET_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(LOCAL_SUB_TARGET_OFFSET + 40L),
            decoder.decodeNext(script, LOCAL_SUB_TARGET_OFFSET + 20L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Jump(LOCAL_SUB_RETURN_CONTINUATION),
            decoder.decodeNext(script, LOCAL_SUB_RETURN_OFFSET)
        )

        val dialogue = decoder.decodeNext(script, LOCAL_SUB_RETURN_CONTINUATION)
        assertEquals(
            SystemNnnDecodedCommand.Dialogue(
                nextCommandOffset = LOCAL_SUB_RETURN_CONTINUATION + 20L,
                textEntryOffset = script.textPool.single().offset
            ),
            dialogue
        )
    }

    @Test
    fun rejectsLocalReturnWhenNoSubroutineCallIsActive() {
        val script = localSubroutineScript()
        assertThrows(IOException::class.java) {
            decoder().decodeNext(script, LOCAL_SUB_RETURN_OFFSET)
        }
    }

    @Test
    fun rejectsLocalSubroutineTargetsOutsideDescriptorThree() {
        val script = localSubroutineScript(subTableIndex = 3u)
        assertThrows(IOException::class.java) {
            decoder().decodeNext(script, LOCAL_SUB_OFFSET)
        }
    }

    @Test
    fun rejectsLocalReturnRecordsWithUnexpectedPayload() {
        val script = localSubroutineScript(returnPayload = listOf(1u))
        val decoder = decoder()
        val subOffset = LOCAL_SUB_OFFSET + 4L
        val returnOffset = LOCAL_SUB_RETURN_OFFSET
        assertEquals(
            SystemNnnDecodedCommand.Jump(LOCAL_SUB_TARGET_OFFSET),
            decoder.decodeNext(script, subOffset)
        )
        decoder.decodeNext(script, LOCAL_SUB_TARGET_OFFSET)
        decoder.decodeNext(script, LOCAL_SUB_TARGET_OFFSET + 20L)
        assertThrows(IOException::class.java) {
            decoder.decodeNext(script, returnOffset)
        }
    }

    private fun localSubroutineScript(
        subTableIndex: UInt = 1u,
        returnPayload: List<UInt> = emptyList()
    ): SystemNnnScript {
        val words = mutableListOf<UInt>()
        words += listOf(0u, 0u, 0u, 0u) // preamble
        // Descriptor-3 table lives in this data record's payload at logical word index 0x17.
        val secondOrdinaryTargetWordOffset = 0x2Cu + returnPayload.size.toUInt()
        words += listOf(6u, DATA, 0x55550002u, 0x1Au, 0x1Fu, secondOrdinaryTargetWordOffset)
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330051u, 1u, 0u) // reserved slot 0
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330050u, 0u, 0u) // ordinary slot 1
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330052u, 0u, 0u) // continuation
        words += record(3 + returnPayload.size, CONTROL, 0x55560007u, *returnPayload.toUIntArray()) // RETURN in local body
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330050u, 0u, 0u) // ordinary slot 2
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330052u, 0u, 0u)
        words += record(5, CONTROL, 0x55560009u, subTableIndex, 7u) // SUB -> descriptor3[1]
        words += record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 1u) // continuation after SUB
        words += listOf(0u, 0u, 0u, 0u) // trailer

        val bytes = ByteArray(words.size * DWORD_BYTES.toInt())
        words.forEachIndexed { index, word -> putUInt32(bytes, index * DWORD_BYTES.toInt(), word) }
        val commandEnd = COMMAND_START + bytes.size
        return SystemNnnScript(
            fileSizeBytes = commandEnd + 0x20L,
            firstHeaderWord = 0u,
            offsetTables = listOf(
                SystemNnnOffsetTable("main", 0u, 0u),
                SystemNnnOffsetTable("secondary", 0u, 0u),
                SystemNnnOffsetTable("tertiary", 3u, 0x17u),
                SystemNnnOffsetTable("auxiliary", 1u, 0u)
            ),
            commandStartOffset = COMMAND_START,
            commandEndOffset = commandEnd,
            textPoolOffset = commandEnd,
            textPool = listOf(SystemNnnTextEntry(offset = commandEnd, text = "Local return test")),
            commandBytes = bytes
        )
    }

    private fun decoder() = SystemNnnCommandDecoderImpl(gtbResourceCount = 8)

    private fun testScript(
        records: List<List<UInt>>,
        textCount: Int,
        secondaryTexts: List<String> = emptyList()
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
            commandBytes = bytes,
            secondaryTextPool = secondaryTexts.mapIndexed { index, text ->
                SystemNnnTextEntry(
                    offset = firstTextOffset + 0x100L + index * 0x20L,
                    text = text
                )
            }
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
        const val LOCAL_SUB_TARGET_OFFSET = 0x7CL
        const val LOCAL_SUB_RETURN_OFFSET = 0xA4L
        const val LOCAL_SUB_OFFSET = 0xD8L
        const val LOCAL_SUB_RETURN_CONTINUATION = 0xECL

        const val DATA = 0x66660001u
        const val CONTROL = 0x66660002u
        const val SYSTEM_COMMAND = 0x66660006u
        const val SYSTEM_FUNCTION = 0x66660007u
        const val CALCULATION = 0x66660005u

        const val CODE_PRINT = 0x22220001u
        const val CODE_LPRINT = 0x22220002u
        const val CODE_APPEND = 0x22220003u
        const val CODE_SELECT = 0x22220006u
        const val CODE_CONTROL_NOP = 0x55560001u
        const val CODE_CONTROL_END = 0x55560005u
        const val CODE_CONTROL_IF = 0x55560002u
        const val CODE_CONTROL_ENDKOMA = 0x5556000Eu
        const val CODE_CONTROL_ENDIF = 0x55560013u
        const val CODE_CONTROL_CASE = 0x55560008u
        const val CODE_CONTROL_RETURN = 0x55560007u
        const val CODE_CONTROL_SUB = 0x55560009u
        const val CODE_OPEN_BRACE = 0xA000007Bu
        const val CODE_CLOSE_BRACE = 0xA000007Du
        const val CODE_CONTROL_ENDFILM = 0x5556000Cu
    }
}
