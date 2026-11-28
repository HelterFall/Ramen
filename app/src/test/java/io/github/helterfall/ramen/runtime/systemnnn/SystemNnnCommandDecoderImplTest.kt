package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnScript
import io.github.helterfall.ramen.parser.SystemNnnOffsetTable
import io.github.helterfall.ramen.parser.SystemNnnTextEntry
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalUnsignedTypes::class)
class SystemNnnCommandDecoderImplTest {

    @Test
    fun skipsAndLogsUnknownIdentifiersAndUnsupportedSystemCommands() {
        val logFile = File.createTempFile("ramen-debug-", ".log").apply { delete() }
        try {
            val script = testScript(
                records = listOf(
                    record(3, 0x66660008u, 0x55560005u),
                    record(3, SYSTEM_COMMAND, 0x22220099u),
                    record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u)
                ),
                textCount = 1
            )
            val decoder = SystemNnnCommandDecoderImpl(
                gtbResourceCount = 8,
                debugLogFile = logFile
            )
            val unknownIdentifierEnd = RECORD_START + 12L
            val unsupportedCommandEnd = unknownIdentifierEnd + 12L

            val unknownIdentifier = decoder.decodeNext(script, RECORD_START)
                as SystemNnnDecodedCommand.Skip
            assertEquals(unknownIdentifierEnd, unknownIdentifier.nextCommandOffset)
            assertTrue(unknownIdentifier.unsupported)
            assertTrue(unknownIdentifier.reason.orEmpty().isNotBlank())
            assertEquals(0x66660008L, unknownIdentifier.rawRecordIdentifier)
            assertEquals(0x55560005L, unknownIdentifier.rawCode)

            val unsupportedCommand = decoder.decodeNext(script, unknownIdentifierEnd)
                as SystemNnnDecodedCommand.Skip
            assertEquals(unsupportedCommandEnd, unsupportedCommand.nextCommandOffset)
            assertTrue(unsupportedCommand.unsupported)
            assertTrue(unsupportedCommand.reason.orEmpty().isNotBlank())
            assertEquals(0x22220099L, unsupportedCommand.rawCode)
            assertEquals(
                SystemNnnDecodedCommand.Dialogue(
                    nextCommandOffset = unsupportedCommandEnd + 20L,
                    textEntryOffset = script.textPool.single().offset
                ),
                decoder.decodeNext(script, unsupportedCommandEnd)
            )

            val log = logFile.readText()
            assertTrue(log.contains("address=0x50 identifier=0x66660008 code=0x55560005"))
            assertTrue(log.contains("address=0x5c identifier=0x66660006 code=0x22220099"))
        } finally {
            logFile.delete()
        }
    }

    @Test
    fun decodesLoadDwqFromItsLayerAndPoolTwoResourceOperands() {
        val script = testScript(
            records = listOf(record(5, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_LOADDWQ, 3u, 0u)),
            textCount = 0,
            secondaryTexts = listOf("bg\\scene_01")
        )

        assertEquals(
            SystemNnnDecodedCommand.LoadImageLayer(
                nextCommandOffset = RECORD_START + 20L,
                layerIndex = 3,
                resourceId = "bg\\scene_01"
            ),
            decoder().decodeNext(script, RECORD_START)
        )
    }

    @Test
    fun decodesSetEffectAndPreservesNativeParameters() {
        val script = testScript(
            records = listOf(
                record(
                    8,
                    SYSTEM_FUNCTION,
                    CODE_SYSTEMFUNCTION_SETEFFECT,
                    4u,
                    2u,
                    132u,
                    24u,
                    7u
                )
            ),
            textCount = 0
        )

        assertEquals(
            SystemNnnDecodedCommand.SetImageLayerEffect(
                nextCommandOffset = RECORD_START + 32L,
                layerIndex = 2,
                effectId = 132,
                parameters = listOf(24, 7)
            ),
            decoder().decodeNext(script, RECORD_START)
        )
    }

    @Test
    fun decodedSetEffectIsNotWrittenToTheUnsupportedCommandLog() {
        val logFile = File.createTempFile("ramen-supported-effect-", ".log").apply { delete() }
        try {
            val script = testScript(
                records = listOf(
                    record(7, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_SETEFFECT, 3u, 0u, 54u, 20u)
                ),
                textCount = 0
            )
            val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8, debugLogFile = logFile)

            assertEquals(
                SystemNnnDecodedCommand.SetImageLayerEffect(
                    nextCommandOffset = RECORD_START + 28L,
                    layerIndex = 0,
                    effectId = 54,
                    parameters = listOf(20)
                ),
                decoder.decodeNext(script, RECORD_START)
            )
            assertTrue(!logFile.exists() || logFile.length() == 0L)
        } finally {
            logFile.delete()
        }
    }

    @Test
    fun decodesSetEffectRectRightAndBottomAsAbsoluteEdges() {
        val script = testScript(
            records = listOf(
                record(
                    13,
                    SYSTEM_FUNCTION,
                    CODE_SYSTEMFUNCTION_SETEFFECTRECT,
                    9u,
                    3u,
                    10u, 20u, 410u, 320u,
                    30u, 40u, 430u, 340u
                )
            ),
            textCount = 0
        )

        assertEquals(
            SystemNnnDecodedCommand.SetImageLayerRect(
                nextCommandOffset = RECORD_START + 52L,
                layerIndex = 3,
                fromRect = SystemNnnNativeRect(10, 20, 410, 320),
                toRect = SystemNnnNativeRect(30, 40, 430, 340)
            ),
            decoder().decodeNext(script, RECORD_START)
        )
    }

    @Test
    fun setEffectRectPreservesZeroAndNegativeExtentsLikeTheNativeCallback() {
        val script = testScript(
            records = listOf(
                record(
                    13,
                    SYSTEM_FUNCTION,
                    CODE_SYSTEMFUNCTION_SETEFFECTRECT,
                    9u,
                    3u,
                    100u, 100u, 0u, 50u,
                    10u, 20u, 410u, 0u
                )
            ),
            textCount = 0
        )

        assertEquals(
            SystemNnnDecodedCommand.SetImageLayerRect(
                nextCommandOffset = RECORD_START + 52L,
                layerIndex = 3,
                fromRect = SystemNnnNativeRect(100, 100, 0, 50),
                toRect = SystemNnnNativeRect(10, 20, 410, 0)
            ),
            decoder().decodeNext(script, RECORD_START)
        )
    }

    @Test
    fun setEffectRectAcceptsNativeExtraArgumentsAndSignedOrigins() {
        val script = testScript(
            records = listOf(
                record(
                    14,
                    SYSTEM_FUNCTION,
                    CODE_SYSTEMFUNCTION_SETEFFECTRECT,
                    10u,
                    3u,
                    0xFFFF_FFF6u, 20u, 410u, 320u,
                    30u, 40u, 430u, 340u,
                    99u
                )
            ),
            textCount = 0
        )

        assertEquals(
            SystemNnnDecodedCommand.SetImageLayerRect(
                nextCommandOffset = RECORD_START + 56L,
                layerIndex = 3,
                fromRect = SystemNnnNativeRect(-10, 20, 410, 320),
                toRect = SystemNnnNativeRect(30, 40, 430, 340)
            ),
            decoder().decodeNext(script, RECORD_START)
        )
    }

    @Test
    fun decodesClearEffectLayerAndClearAllEffect() {
        val clearOne = testScript(
            records = listOf(record(4, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_CLEAREFFECTLAYER, 5u)),
            textCount = 0
        )
        val clearAll = testScript(
            records = listOf(record(3, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_CLEARALLEFFECT)),
            textCount = 0
        )

        assertEquals(
            SystemNnnDecodedCommand.ClearImageLayer(RECORD_START + 16L, 5),
            decoder().decodeNext(clearOne, RECORD_START)
        )
        assertEquals(
            SystemNnnDecodedCommand.ClearAllImageLayers(RECORD_START + 12L),
            decoder().decodeNext(clearAll, RECORD_START)
        )
    }

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
                record(5, SYSTEM_FUNCTION, 0x33330054u, 1u, 0u),
                record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 1u)
            ),
            textCount = 1
        )
        val decoder = decoder()

        val first = decoder.decodeNext(script, RECORD_START)
        val second = decoder.decodeNext(script, RECORD_START + 16L)
        val third = decoder.decodeNext(script, RECORD_START + 36L)

        assertEquals(RECORD_START + 16L, (first as SystemNnnDecodedCommand.Skip).nextCommandOffset)
        val unsupportedFunction = second as SystemNnnDecodedCommand.Skip
        assertEquals(RECORD_START + 36L, unsupportedFunction.nextCommandOffset)
        assertTrue(unsupportedFunction.unsupported)
        assertTrue(unsupportedFunction.reason.orEmpty().isNotBlank())
        assertEquals(script.textPool[0].offset, (third as SystemNnnDecodedCommand.Dialogue).textEntryOffset)
    }

    @Test
    fun decodesNonzeroDrawAsATimedFrameBoundary() {
        val script = testScript(
            listOf(
                record(5, SYSTEM_COMMAND, CODE_DRAW, 1u, 3u),
                record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u)
            ),
            textCount = 1
        )
        val decoder = decoder()
        val drawEndOffset = RECORD_START + 20L

        assertEquals(
            SystemNnnDecodedCommand.Draw(drawEndOffset, frameCount = 3),
            decoder.decodeNext(script, RECORD_START)
        )
        assertEquals(
            SystemNnnDecodedCommand.Dialogue(
                nextCommandOffset = drawEndOffset + 20L,
                textEntryOffset = script.textPool.single().offset
            ),
            decoder.decodeNext(script, drawEndOffset)
        )
    }

    @Test
    fun zeroCountDrawDoesNotYieldABoundary() {
        val script = testScript(
            listOf(
                record(5, SYSTEM_COMMAND, CODE_DRAW, 1u, 0u),
                record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u)
            ),
            textCount = 1
        )
        val decoder = decoder()
        val drawEndOffset = RECORD_START + 20L

        assertEquals(
            SystemNnnDecodedCommand.Skip(drawEndOffset),
            decoder.decodeNext(script, RECORD_START)
        )
        assertEquals(
            SystemNnnDecodedCommand.Dialogue(
                nextCommandOffset = drawEndOffset + 20L,
                textEntryOffset = script.textPool.single().offset
            ),
            decoder.decodeNext(script, drawEndOffset)
        )
    }

    @Test
    fun rejectsDrawWithoutAFrameCountOperand() {
        val script = testScript(
            listOf(record(4, SYSTEM_COMMAND, CODE_DRAW)),
            textCount = 0
        )
        org.junit.Assert.assertThrows(IOException::class.java) {
            decoder().decodeNext(script, RECORD_START)
        }
    }

    @Test
    fun decodesAllThirtyThreeObservedSetEffectParameterVectorsExactly() {
        val vectors = listOf(
            0 to emptyList(), 1 to emptyList(), 2 to listOf(9999, 0, 0, 0, 1),
            8 to listOf(0, 20), 9 to listOf(2000, 100, 0, 2, 100, 0, 0, 800, 600, 0, 0, 800, 600),
            10 to listOf(150, 0, 0, 0, 0), 11 to listOf(30, 255, 255, 255),
            17 to listOf(20, 1, 4, 255, 255, 255), 20 to listOf(40, 255, 255, 255, 3, 0),
            22 to listOf(120, 0, 600, 0, 100, 100, 100, 9, 7, 11, 4, 17, 6, 30, 2, -5),
            27 to listOf(1, 32, 32, 16, 0, 10, 5, 1, 12, 81, 4, 120),
            29 to listOf(90, 1, 20, 20, 0, 0, 0, 800, 600), 31 to listOf(20, 5, 5, 100, 0),
            39 to listOf(20, 32, 0, 1), 40 to listOf(60, 9, 100, 100, 0, 0, 0, 0),
            43 to listOf(50, 5, 2, 0, 0, 800, 600),
            50 to listOf(9999, 255, 255, 255, 255, 239, 237, 2, 245, 80, 90, 70, 100, 50, 110),
            52 to listOf(90), 54 to emptyList(),
            55 to listOf(9999, 10, 0, 800, 600, 7, 1, 140, 100, 70, 200, 80, 40),
            56 to listOf(0, 0, 170, 3, 5, 0, 50, 100),
            63 to listOf(9999, 300, 244, 10, 332, 88, 332, 88, 0, 2, 100, 100, 0, 0, 9999),
            64 to listOf(700, 375, 312, 0, 0, 220, 300, 2, 100, 100, 0, 0, 0, 0, 0, 0),
            65 to listOf(30, 0, 0, 50, 50, 100, 100, 0, 0, 2, 100, 0, 5, 5),
            66 to emptyList(), 69 to listOf(230, 8, 400, 300, 40, 220, 4, 18, 10, 100, 0, 0, 300),
            71 to listOf(0, 50, 0, 0, 900, 800, -1, -2, 185, 130, 120, 1, 0, 0, 100, 135, 169, 16),
            73 to listOf(20), 74 to listOf(90, 1, 200, 400, 400, 70, 100, 0, 0, 0, 0, 430, 1),
            82 to listOf(0, 300, 13, 4, 9999, 200, 1362, 200, 0, 1, 100, 60, 50, 50),
            99 to listOf(20, -189, -255, -255, -189, -255, -255, 1, 0, 0),
            100 to listOf(20, 20, 30, 55, 30, 1, 0, 0, 0, 0, 0, 0, 0),
            102 to listOf(0, 3, 500, 20, -2, 400, 300, 30, 200)
        )
        assertEquals(33, vectors.size)
        val records = vectors.map { pair ->
            val effectId = pair.first
            val parameters = pair.second
            record(6 + parameters.size, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_SETEFFECT,
                (parameters.size + 2).toUInt(), 0u, effectId.toUInt(), *parameters.map { it.toUInt() }.toUIntArray())
        }
        val script = testScript(records, 0)
        var offset = RECORD_START
        vectors.zip(records).forEach { pair ->
            val effectId = pair.first.first
            val parameters = pair.first.second
            val recordWords = pair.second
            assertEquals(
                SystemNnnDecodedCommand.SetImageLayerEffect(
                    offset + recordWords.size * DWORD_BYTES, 0, effectId, parameters
                ),
                decoder().decodeNext(script, offset)
            )
            offset += recordWords.size * DWORD_BYTES
        }
    }
    @Test
    fun decodesOverrapAsAVisibleBoundaryAndPreservesItsArguments() {
        val script = testScript(listOf(record(6, SYSTEM_COMMAND, 0x22220005u, 2u, 40u, 5u)), 0)
        assertEquals(
            SystemNnnDecodedCommand.Draw(RECORD_START + 24L, 1, overrapParameters = listOf(40, 5)),
            decoder().decodeNext(script, RECORD_START)
        )
    }

    @Test
    fun decodesAllRequestedNativeSystemFunctionsAndResolvesNameStrings() {
        val records = mutableListOf<List<UInt>>()
        records += record(3, SYSTEM_FUNCTION, 0x33330011u)
        for (code in listOf(0x18u, 0x1Bu, 0x1Cu, 0x1Eu, 0x52u, 0x57u, 0x58u, 0x59u, 0x5Au)) {
            records += record(5, SYSTEM_FUNCTION, 0x33330000u or code, 1u, 77u)
        }
        records += record(5, SYSTEM_FUNCTION, 0x33330050u, 1u, 0u)
        records += record(5, SYSTEM_FUNCTION, 0x33330051u, 1u, 1u)
        val script = testScript(records, 0, listOf("Film title", "Story title"))
        val expected = listOf(
            0x11 to (emptyList<Int>() to null),
            0x18 to (listOf(77) to null), 0x1B to (listOf(77) to null), 0x1C to (listOf(77) to null),
            0x1E to (listOf(77) to null), 0x52 to (listOf(77) to null), 0x57 to (listOf(77) to null),
            0x58 to (listOf(77) to null), 0x59 to (listOf(77) to null), 0x5A to (listOf(77) to null),
            0x50 to (listOf(0) to "Film title"), 0x51 to (listOf(1) to "Story title")
        )
        var offset = RECORD_START
        records.zip(expected).forEach { pair ->
            val rec = pair.first
            val exp = pair.second
            assertEquals(
                SystemNnnDecodedCommand.NativeSystemFunction(
                    offset + rec.size * DWORD_BYTES, exp.first, exp.second.first, exp.second.second
                ),
                decoder().decodeNext(script, offset)
            )
            offset += rec.size * DWORD_BYTES
        }
    }
    @Test
    fun decodesNoMessageWindowConfigMaskAndFrameControl() {
        val records = listOf(
            record(3, SYSTEM_FUNCTION, 0x33330017u),
            record(5, SYSTEM_FUNCTION, 0x33330053u, 1u, 77u),
            record(5, SYSTEM_FUNCTION, 0x33330028u, 1u, 45u)
        )
        val script = testScript(records, 0)
        var offset = RECORD_START
        val expected = listOf(
            SystemNnnDecodedCommand.NativeSystemFunction(offset + 12L, 0x17, emptyList()).also { offset += 12L },
            SystemNnnDecodedCommand.NativeSystemFunction(offset + 20L, 0x53, listOf(77)).also { offset += 20L },
            SystemNnnDecodedCommand.NativeSystemFunction(offset + 20L, 0x28, listOf(45)).also { offset += 20L }
        )
        offset = RECORD_START
        expected.forEach { command ->
            assertEquals(command, decoder().decodeNext(script, offset))
            offset = command.nextCommandOffset
        }
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

        assertEquals(
            SystemNnnDecodedCommand.ControllerReset,
            decoder.decodeNext(script, RECORD_START)
        )
        assertEquals(
            SystemNnnDecodedCommand.End,
            decoder.decodeNext(script, RECORD_START + 12L)
        )
    }

    @Test
    fun endFilmAdvancesToTheNextRecordInsteadOfEndingThePlayer() {
        val script = testScript(
            listOf(
                record(3, CONTROL, CODE_CONTROL_ENDFILM),
                record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u)
            ),
            textCount = 1
        )
        val decoder = decoder()

        assertEquals(
            SystemNnnDecodedCommand.EndFilm(RECORD_START + 12L),
            decoder.decodeNext(script, RECORD_START)
        )
        assertEquals(
            SystemNnnDecodedCommand.Dialogue(
                nextCommandOffset = RECORD_START + 32L,
                textEntryOffset = script.textPool.single().offset
            ),
            decoder.decodeNext(script, RECORD_START + 12L)
        )
    }

    @Test
    fun setFilmMustMatchTheOneBasedFilmNumberOfItsDescriptorThreeInterval() {
        val script = filmSetScript(filmNumber = 1u)

        assertEquals(
            SystemNnnDecodedCommand.SetFilm(
                nextCommandOffset = SETFILM_OFFSET + 20L,
                filmNumber = 1
            ),
            decoder().decodeNext(script, SETFILM_OFFSET)
        )
    }

    @Test
    fun rejectsSetFilmWhenItsNumberDoesNotMatchTheOwningDescriptorThreeEntry() {
        val script = filmSetScript(filmNumber = 2u)

        assertThrows(IOException::class.java) {
            decoder().decodeNext(script, SETFILM_OFFSET)
        }
    }

    @Test
    fun continuesFromEndFilmToTheNextFilmSetFilmRecordInTheSameModule() {
        val script = filmSetScript(filmNumber = 1u)
        val decoder = decoder()

        assertEquals(
            SystemNnnDecodedCommand.SetFilm(SETFILM_OFFSET + 20L, 1),
            decoder.decodeNext(script, SETFILM_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.EndFilm(END_FILM_OFFSET + 12L),
            decoder.decodeNext(script, END_FILM_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.Dialogue(
                nextCommandOffset = PRINT_OFFSET + 20L,
                textEntryOffset = script.textPool.single().offset
            ),
            decoder.decodeNext(script, PRINT_OFFSET)
        )
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

        assertEquals(true, failure.message?.contains("no expression payload"))
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
    fun skipsFalseIfAndElsifBodiesUntilTheElseBody() {
        val script = testScript(
            records = listOf(
                conditional(CODE_CONTROL_IF, false),
                record(4, CONTROL, CODE_OPEN_BRACE, 10u),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                conditional(CODE_CONTROL_ELSIF, false),
                record(4, CONTROL, CODE_OPEN_BRACE, 10u),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                record(3, CONTROL, CODE_CONTROL_ELSE),
                record(4, CONTROL, CODE_OPEN_BRACE, 10u),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                record(3, CONTROL, CODE_CONTROL_ENDIF)
            ),
            textCount = 0
        )
        val decoder = decoder()

        // Both false conditions are skipped; execution resumes after ELSE.
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 140L),
            decoder.decodeNext(script, RECORD_START)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 156L),
            decoder.decodeNext(script, RECORD_START + 140L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 168L),
            decoder.decodeNext(script, RECORD_START + 156L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 180L),
            decoder.decodeNext(script, RECORD_START + 168L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 192L),
            decoder.decodeNext(script, RECORD_START + 180L)
        )
    }

    @Test
    fun chosenIfBodySkipsRemainingElsifAndElseBodies() {
        val script = testScript(
            records = listOf(
                conditional(CODE_CONTROL_IF, true),
                record(4, CONTROL, CODE_OPEN_BRACE, 10u),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                conditional(CODE_CONTROL_ELSIF, false),
                record(4, CONTROL, CODE_OPEN_BRACE, 10u),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                record(3, CONTROL, CODE_CONTROL_ELSE),
                record(4, CONTROL, CODE_OPEN_BRACE, 10u),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(3, CONTROL, CODE_CLOSE_BRACE),
                record(3, CONTROL, CODE_CONTROL_ENDIF)
            ),
            textCount = 0
        )
        val decoder = decoder()

        // A true IF enters its body. Later alternatives are skipped after it.
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 24L),
            decoder.decodeNext(script, RECORD_START)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 40L),
            decoder.decodeNext(script, RECORD_START + 24L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 52L),
            decoder.decodeNext(script, RECORD_START + 40L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 64L),
            decoder.decodeNext(script, RECORD_START + 52L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 128L),
            decoder.decodeNext(script, RECORD_START + 64L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 180L),
            decoder.decodeNext(script, RECORD_START + 128L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 192L),
            decoder.decodeNext(script, RECORD_START + 180L)
        )
    }

    @Test
    fun conditionalDivisionByZeroUsesTheNativeOneDivisorFallback() {
        val script = testScript(
            records = listOf(
                record(
                    8,
                    CONTROL,
                    CODE_CONTROL_IF,
                    0xA0000000u,
                    3u,
                    0x9000000Au,
                    0x90000000u,
                    0xA000002Fu
                )
            ),
            textCount = 0
        )

        // Native gss.exe changes a zero divisor to 1, so 10 / 0 evaluates to 10.
        assertEquals(
            SystemNnnDecodedCommand.Skip(RECORD_START + 32L),
            decoder().decodeNext(script, RECORD_START)
        )
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
    fun skipsOnlyTheOpeningRecordWhenSelectIsFollowedByAnOuterBrace() {
        val script = testScript(
            records = listOf(
                record(10, SYSTEM_COMMAND, CODE_SELECT, 2u, 2u, 3u, 0u, 0u, 0u, 0u),
                // The target GetCase routine skips this wrapper record but does
                // not skip its declared span before looking for CASE records.
                record(4, CONTROL, CODE_OPEN_BRACE, 99u),
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

        assertEquals(RECORD_START + 40L + 16L + 16L, decoded.options[0].targetCommandOffset)
        assertEquals(RECORD_START + 40L + 16L + 16L + 16L + 12L + 12L + 16L, decoded.options[1].targetCommandOffset)
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
    fun runsSetFilmEndFilmAndReturnInsideARealDescriptorThreeSubroutine() {
        val script = localFilmSubroutineScript()
        val decoder = decoder()

        assertEquals(
            SystemNnnDecodedCommand.Jump(LOCAL_FILM_TARGET_OFFSET),
            decoder.decodeNext(script, LOCAL_FILM_SUB_CALL_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(LOCAL_FILM_TARGET_OFFSET + 20L),
            decoder.decodeNext(script, LOCAL_FILM_TARGET_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(LOCAL_FILM_TARGET_OFFSET + 40L),
            decoder.decodeNext(script, LOCAL_FILM_TARGET_OFFSET + 20L)
        )
        assertEquals(
            SystemNnnDecodedCommand.SetFilm(LOCAL_FILM_ENDFILM_OFFSET, 1),
            decoder.decodeNext(script, LOCAL_FILM_SETFILM_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.EndFilm(LOCAL_FILM_RETURN_OFFSET),
            decoder.decodeNext(script, LOCAL_FILM_ENDFILM_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.Jump(LOCAL_FILM_CALLER_CONTINUATION),
            decoder.decodeNext(script, LOCAL_FILM_RETURN_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.Dialogue(
                nextCommandOffset = LOCAL_FILM_CALLER_CONTINUATION + 20L,
                textEntryOffset = script.textPool.single().offset
            ),
            decoder.decodeNext(script, LOCAL_FILM_CALLER_CONTINUATION)
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

    @Test
    fun nextClearsStaleLocalSubFramesAndIgnoresItsPayload() {
        val script = localSubroutineScript(
            returnPayload = listOf(0xDEADBEEFu),
            returnCode = 0x55560006u
        )
        val decoder = decoder()

        assertEquals(
            SystemNnnDecodedCommand.Jump(LOCAL_SUB_TARGET_OFFSET),
            decoder.decodeNext(script, LOCAL_SUB_OFFSET + 4L)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(LOCAL_SUB_TARGET_OFFSET + 20L),
            decoder.decodeNext(script, LOCAL_SUB_TARGET_OFFSET)
        )
        assertEquals(
            SystemNnnDecodedCommand.Skip(LOCAL_SUB_TARGET_OFFSET + 40L),
            decoder.decodeNext(script, LOCAL_SUB_TARGET_OFFSET + 20L)
        )
        assertEquals(
            SystemNnnDecodedCommand.ModuleReturn,
            decoder.decodeNext(script, LOCAL_SUB_RETURN_OFFSET)
        )
    }

    @Test
    fun decodesMusicVoiceAndSoundSystemFunctions() {
        val music = testScript(
            records = listOf(record(5, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_MUSIC, 1u, 1u)),
            textCount = 0
        )
        assertEquals(
            SystemNnnDecodedCommand.Music(RECORD_START + 20L, musicNumber = 1, loopCount = 0),
            decoder().decodeNext(music, RECORD_START)
        )

        val voice = testScript(
            records = listOf(record(6, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_VOICE, 2u, 0u, 3u)),
            textCount = 0,
            secondaryTexts = listOf("voice001")
        )
        assertEquals(
            SystemNnnDecodedCommand.Voice(RECORD_START + 24L, resourceId = "voice001", channel = 3),
            decoder().decodeNext(voice, RECORD_START)
        )

        val sound = testScript(
            records = listOf(record(6, SYSTEM_FUNCTION, CODE_SYSTEMFUNCTION_SOUND, 2u, 1u, 2u)),
            textCount = 0
        )
        assertEquals(
            SystemNnnDecodedCommand.Sound(RECORD_START + 24L, soundNumber = 1, channel = 2),
            decoder().decodeNext(sound, RECORD_START)
        )
    }

    @Test
    fun logsUnsupportedSystemFunctionCodesWithoutCorruptingTheRecordCursor() {
        val logFile = File.createTempFile("ramen-system-function-audit-", ".log").apply { delete() }
        try {
            val unsupported = listOf(
                0x33330054u, 0x33330056u, 0x33330060u
            )
            val records = unsupported.map { code -> record(3, SYSTEM_FUNCTION, code) }
            val script = testScript(records, textCount = 0)
            val decoder = SystemNnnCommandDecoderImpl(gtbResourceCount = 8, debugLogFile = logFile)
            unsupported.forEachIndexed { index, code ->
                val offset = RECORD_START + index * 12L
                val skipped = decoder.decodeNext(script, offset) as SystemNnnDecodedCommand.Skip
                assertEquals(offset + 12L, skipped.nextCommandOffset)
                assertTrue(skipped.unsupported)
                assertTrue(skipped.reason.orEmpty().isNotBlank())
                assertEquals(code.toLong(), skipped.rawCode)
            }
            val log = logFile.readText()
            unsupported.forEach { code ->
                assertTrue(log.contains("code=0x" + code.toString(16)))
            }
        } finally {
            logFile.delete()
        }
    }

    @Test
    fun decodesAllThreeNativeModuleTransferControlCodes() {
        val transfers = listOf(
            0x5556000Au to SystemNnnModuleTransferKind.SCRIPT,
            0x55560016u to SystemNnnModuleTransferKind.SUBSCRIPT,
            0x55560015u to SystemNnnModuleTransferKind.SCRIPTJUMP
        )
        transfers.forEach { (code, kind) ->
            val script = testScript(
                records = listOf(record(4, CONTROL, code, 0u)),
                textCount = 0,
                secondaryTexts = listOf("day")
            )
            assertEquals(
                SystemNnnDecodedCommand.ModuleTransfer(
                    nextCommandOffset = RECORD_START + 16L,
                    targetModuleName = "day",
                    kind = kind
                ),
                decoder().decodeNext(script, RECORD_START)
            )
        }
    }

    @Test
    fun gotoUsesAbsoluteDwordTargetAndRejectsPayloadOffsets() {
        val targetOffset = RECORD_START + 16L
        val validScript = testScript(
            records = listOf(
                record(4, CONTROL, CODE_CONTROL_GOTO, (targetOffset / DWORD_BYTES).toUInt()),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u)
            ),
            textCount = 1
        )

        assertEquals(
            SystemNnnDecodedCommand.Jump(targetOffset),
            decoder().decodeNext(validScript, RECORD_START)
        )

        val invalidScript = testScript(
            records = listOf(
                record(4, CONTROL, CODE_CONTROL_GOTO, ((RECORD_START + 4L) / DWORD_BYTES).toUInt()),
                record(3, CONTROL, CODE_CONTROL_NOP),
                record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u)
            ),
            textCount = 1
        )
        assertThrows(IOException::class.java) {
            decoder().decodeNext(invalidScript, RECORD_START)
        }
    }

    private fun localSubroutineScript(
        subTableIndex: UInt = 1u,
        returnPayload: List<UInt> = emptyList(),
        returnCode: UInt = CODE_CONTROL_RETURN
    ): SystemNnnScript {
        val words = mutableListOf<UInt>()
        words += listOf(0u, 0u, 0u, 0u) // preamble
        // Descriptor-3 table lives in this data record's payload at logical word index 0x17.
        val secondOrdinaryTargetWordOffset = 0x2Cu + returnPayload.size.toUInt()
        words += listOf(6u, DATA, 0x55550002u, 0x1Au, 0x1Fu, secondOrdinaryTargetWordOffset)
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330051u, 1u, 0u) // reserved slot 0
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330050u, 0u, 0u) // ordinary slot 1
        words += listOf(5u, SYSTEM_FUNCTION, 0x33330052u, 0u, 0u) // continuation
        words += record(3 + returnPayload.size, CONTROL, returnCode, *returnPayload.toUIntArray()) // local transfer in body
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

    private fun localFilmSubroutineScript(): SystemNnnScript {
        val words = listOf(0u, 0u, 0u, 0u) +
            record(6, DATA, 0x55550002u, 0x1Au, 0x1Fu, 0x34u) +
            record(5, SYSTEM_FUNCTION, 0x33330051u, 1u, 0u) +
            record(5, SYSTEM_FUNCTION, 0x33330050u, 0u, 0u) +
            record(5, SYSTEM_FUNCTION, 0x33330052u, 0u, 0u) +
            record(5, SYSTEM_FUNCTION, 0x3333001Au, 1u, 1u) +
            record(3, CONTROL, CODE_CONTROL_ENDFILM) +
            record(3, CONTROL, CODE_CONTROL_RETURN) +
            record(5, SYSTEM_FUNCTION, 0x33330050u, 0u, 0u) +
            record(5, SYSTEM_FUNCTION, 0x33330052u, 0u, 0u) +
            record(5, CONTROL, CODE_CONTROL_SUB, 1u, 0u) +
            record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u) +
            listOf(0u, 0u, 0u, 0u)
        val bytes = ByteArray(words.size * DWORD_BYTES.toInt())
        words.forEachIndexed { index, word ->
            putUInt32(bytes, index * DWORD_BYTES.toInt(), word)
        }
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
            textPool = listOf(SystemNnnTextEntry(offset = commandEnd, text = "Returned from local film")),
            commandBytes = bytes
        )
    }

    private fun filmSetScript(filmNumber: UInt): SystemNnnScript {
        val words = listOf(0u, 0u, 0u, 0u) +
            record(5, DATA, 0x55550002u, 0x19u, 0x1Eu) +
            record(5, SYSTEM_FUNCTION, 0x33330051u, 1u, 0u) +
            record(5, SYSTEM_FUNCTION, 0x33330050u, 1u, 0u) +
            record(5, SYSTEM_FUNCTION, 0x33330052u, 1u, 0u) +
            record(5, SYSTEM_FUNCTION, 0x3333001Au, 1u, filmNumber) +
            record(3, CONTROL, CODE_CONTROL_ENDFILM) +
            record(5, SYSTEM_COMMAND, CODE_PRINT, 0u, 0u) +
            listOf(0u, 0u, 0u, 0u)
        val bytes = ByteArray(words.size * DWORD_BYTES.toInt())
        words.forEachIndexed { index, word ->
            putUInt32(bytes, index * DWORD_BYTES.toInt(), word)
        }
        val commandEnd = COMMAND_START + bytes.size
        return SystemNnnScript(
            fileSizeBytes = commandEnd + 0x20L,
            firstHeaderWord = 0u,
            offsetTables = listOf(
                SystemNnnOffsetTable("main", 0u, 0u),
                SystemNnnOffsetTable("secondary", 0u, 0u),
                SystemNnnOffsetTable("tertiary", 2u, 0x17u),
                SystemNnnOffsetTable("auxiliary", 1u, 0u)
            ),
            commandStartOffset = COMMAND_START,
            commandEndOffset = commandEnd,
            textPoolOffset = commandEnd,
            textPool = listOf(SystemNnnTextEntry(offset = commandEnd, text = "Film continuation")),
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

    private fun conditional(code: UInt, value: Boolean): List<UInt> =
        record(
            6,
            CONTROL,
            code,
            0xA0000000u,
            1u,
            if (value) 0x90000001u else 0x90000000u
        )

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
        const val SETFILM_OFFSET = 0xA0L
        const val END_FILM_OFFSET = 0xB4L
        const val PRINT_OFFSET = 0xC0L
        const val LOCAL_FILM_TARGET_OFFSET = 0x7CL
        const val LOCAL_FILM_SETFILM_OFFSET = 0xA4L
        const val LOCAL_FILM_ENDFILM_OFFSET = 0xB8L
        const val LOCAL_FILM_RETURN_OFFSET = 0xC4L
        const val LOCAL_FILM_SUB_CALL_OFFSET = 0xF8L
        const val LOCAL_FILM_CALLER_CONTINUATION = 0x10CL

        const val DATA = 0x66660001u
        const val CONTROL = 0x66660002u
        const val SYSTEM_COMMAND = 0x66660006u
        const val SYSTEM_FUNCTION = 0x66660007u
        const val CALCULATION = 0x66660005u

        const val CODE_SYSTEMFUNCTION_CLEARALLEFFECT = 0x33330012u
        const val CODE_SYSTEMFUNCTION_CLEAREFFECTLAYER = 0x33330014u
        const val CODE_SYSTEMFUNCTION_LOADDWQ = 0x33330015u
        const val CODE_SYSTEMFUNCTION_SETEFFECT = 0x33330013u
        const val CODE_SYSTEMFUNCTION_SETEFFECTRECT = 0x33330016u

        const val CODE_PRINT = 0x22220001u
        const val CODE_LPRINT = 0x22220002u
        const val CODE_APPEND = 0x22220003u
        const val CODE_DRAW = 0x22220004u
        const val CODE_SELECT = 0x22220006u
        const val CODE_CONTROL_NOP = 0x55560001u
        const val CODE_CONTROL_END = 0x55560005u
        const val CODE_CONTROL_IF = 0x55560002u
        const val CODE_CONTROL_ELSIF = 0x55560003u
        const val CODE_CONTROL_ELSE = 0x55560004u
        const val CODE_CONTROL_ENDKOMA = 0x5556000Eu
        const val CODE_CONTROL_ENDIF = 0x55560013u
        const val CODE_CONTROL_CASE = 0x55560008u
        const val CODE_CONTROL_RETURN = 0x55560007u
        const val CODE_CONTROL_GOTO = 0x55560020u
        const val CODE_SYSTEMFUNCTION_MUSIC = 0x33330007u
        const val CODE_SYSTEMFUNCTION_VOICE = 0x33330008u
        const val CODE_SYSTEMFUNCTION_SOUND = 0x33330009u
        const val CODE_CONTROL_SUB = 0x55560009u
        const val CODE_OPEN_BRACE = 0xA000007Bu
        const val CODE_CLOSE_BRACE = 0xA000007Du
        const val CODE_CONTROL_ENDFILM = 0x5556000Cu
    }
}
