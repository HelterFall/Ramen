package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerLsbParserTest {
    @Test
    fun parsesVersion103VariableCalcAndTextCommands() {
        val tpWord = byteArrayOf('T'.code.toByte(), 'p'.code.toByte(), 'W'.code.toByte(),
            'o'.code.toByte(), 'r'.code.toByte(), 'd'.code.toByte())
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.variableNew("score", 42),
                LiveMakerLsbFixture.calcIncrement("score", 8),
                LiveMakerLsbFixture.textInsert(tpWord),
                LiveMakerLsbFixture.exitWhenTrue()
            )
        )

        withSource(bytes) { source ->
            val script = LiveMakerLsbParser().parse(source)

            assertEquals(103, script.version)
            assertEquals(LiveMakerEdition.LIVE_MAKER_2, script.edition)
            assertEquals(4, script.commands.size)
            assertTrue(script.isComplete)
            assertNull(script.unsupportedCommandIndex)
            assertEquals(15, script.commands[0].typeId)
            assertEquals("score", script.commands[0].strings.single())
            assertEquals(1, script.commands[0].byteValues.first())
            assertEquals(20, script.commands[2].typeId)
            assertTrue(script.commands[2].binaryBlobs.single().contentEquals(tpWord))
            assertEquals(0L, script.trailingByteCount)
        }
    }

    @Test
    fun mapsCompiledVersion117ToLiveMaker3() {
        val bytes = LiveMakerLsbFixture.build(version = 117, commands = emptyList())

        withSource(bytes) { source ->
            val script = LiveMakerLsbParser().parse(source)
            assertEquals(LiveMakerEdition.LIVE_MAKER_3, script.edition)
            assertEquals(117, script.version)
            assertTrue(script.isComplete)
        }
    }

    @Test
    fun stopsCleanlyAtUnknownCommandWithoutGuessingItsLength() {
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(LiveMakerLsbFixture.command(3, LiveMakerLsbFixture.emptyString())),
            unknownCommandType = 0xFE
        )

        withSource(bytes) { source ->
            val script = LiveMakerLsbParser().parse(source)
            assertFalse(script.isComplete)
            assertEquals(1, script.commands.size)
            assertEquals(1, script.unsupportedCommandIndex)
            assertEquals(0xFE, script.unsupportedCommandType)
            assertEquals(1L, script.declaredCommandCount - script.commands.size)
        }
    }

    @Test
    fun rejectsOutOfRangeExpressionOperandType() {
        val badExpression = byteArrayOf(
            1, 0, 0, 0, // one operation
            0, 0, 0, 0, 0, // operator type + empty name length
            1, 0, 0, 0, // one operand
            0x7F // unknown parameter type
        )
        val bytes = LiveMakerLsbFixture.build(
            commands = listOf(LiveMakerLsbFixture.command(14, badExpression))
        )

        withSource(bytes) { source ->
            val error = runCatching { LiveMakerLsbParser().parse(source) }.exceptionOrNull()
            assertTrue(error is IOException)
        }
    }

    private fun withSource(bytes: ByteArray, block: (ReadOnlyRandomAccessSource) -> Unit) {
        val directory = Files.createTempDirectory("ramen-livemaker-lsb-test").toFile()
        try {
            File(directory, "script.lsb").apply { writeBytes(bytes) }
                .let(::FileRandomAccessSource)
                .use(block)
        } finally {
            directory.deleteRecursively()
        }
    }
}
