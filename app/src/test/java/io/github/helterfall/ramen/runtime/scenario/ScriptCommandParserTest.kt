package io.github.helterfall.ramen.runtime.scenario

import io.github.helterfall.ramen.runtime.engine.BinarySignatureProbe
import io.github.helterfall.ramen.runtime.engine.DirectoryGameSource
import io.github.helterfall.ramen.runtime.engine.EngineProbeResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ScriptCommandParserTest {
    @Test
    fun parsesAllThreeCommandsFromOneStream() {
        val parser = ScriptCommandParser(StandardCharsets.UTF_8)
        val stream = ByteArrayInputStream(
            commandStream(dialogue("Hello"), background("bg/coast.png"), jump(0x1234ABCDL))
        )

        assertEquals(DisplayDialogueCommand("Hello"), parser.parseNextCommand(stream))
        assertEquals(ChangeBackgroundCommand("bg/coast.png"), parser.parseNextCommand(stream))
        assertEquals(JumpToSceneCommand(0x1234ABCDL), parser.parseNextCommand(stream))
        assertNull(parser.parseNextCommand(stream))
    }

    @Test
    fun decodesShiftJisDialogueAndUnicodeBackgroundNames() {
        val charset = Charset.forName("Shift_JIS")
        val parser = ScriptCommandParser(charset)
        val stream = ByteArrayInputStream(
            commandStream(dialogue("こんにちは", charset), background("背景/海.png", charset))
        )

        assertEquals(
            DisplayDialogueCommand("こんにちは"),
            parser.parseNextCommand(stream)
        )
        assertEquals(
            ChangeBackgroundCommand("背景/海.png"),
            parser.parseNextCommand(stream)
        )
    }

    @Test
    fun cleanEofBetweenCommandsReturnsNull() {
        assertNull(ScriptCommandParser().parseNextCommand(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun unknownOpcodeAndTruncatedOperandsAreMalformed() {
        assertThrows(ScriptParseException::class.java) {
            ScriptCommandParser().parseNextCommand(ByteArrayInputStream(byteArrayOf(0x7F)))
        }
        assertThrows(ScriptParseException::class.java) {
            ScriptCommandParser().parseNextCommand(ByteArrayInputStream(byteArrayOf(0x01, 0x02)))
        }
        assertThrows(ScriptParseException::class.java) {
            ScriptCommandParser().parseNextCommand(ByteArrayInputStream(byteArrayOf(0x03, 0x01, 0x00)))
        }
        assertThrows(ScriptParseException::class.java) {
            ScriptCommandParser().parseNextCommand(
                ByteArrayInputStream(byteArrayOf(0x01, 0x02, 0x00, 0x48))
            )
        }
    }

    @Test
    fun rejectsInvalidTextAndUnsafeBackgroundPaths() {
        assertThrows(ScriptParseException::class.java) {
            ScriptCommandParser(StandardCharsets.UTF_8).parseNextCommand(
                ByteArrayInputStream(byteArrayOf(0x01, 0x01, 0x00, 0xFF.toByte()))
            )
        }
        assertThrows(ScriptParseException::class.java) {
            ScriptCommandParser().parseNextCommand(
                ByteArrayInputStream(commandStream(background("../outside.png")))
            )
        }
    }

    @Test
    fun loaderMapsShiftJisInstructionsIntoScenarioAndSaveState() {
        val charset = Charset.forName("Shift_JIS")
        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(
                script(
                    commandStream(background("背景/海.png", charset), dialogue("灯台が見えます。", charset)),
                    encoding = 2
                )
            )
        )
        val session = ScenarioSession.start(project)

        assertEquals("背景/海.png", session.frame().backgroundResourceKey)
        assertEquals("灯台が見えます。", session.frame().dialogue?.text)

        val restored = ScenarioSession.restore(
            project,
            ScenarioSaveCodec.decode(ScenarioSaveCodec.encode(session.saveState()))
        )
        assertEquals("背景/海.png", restored.frame().backgroundResourceKey)
        assertEquals("灯台が見えます。", restored.frame().dialogue?.text)
        assertTrue(session.continueStory().ended)
    }

    @Test
    fun loaderResolvesJumpAddressesToCommandBoundaries() {
        val targetAddress = 10L + 5L
        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(script(commandStream(jump(targetAddress), dialogue("Reached target"))))
        )
        val session = ScenarioSession.start(project)

        assertEquals("Reached target", session.frame().dialogue?.text)
        assertEquals("addr_$targetAddress", session.frame().sceneId)
    }

    @Test
    fun loaderRejectsUnknownOpcodeTruncatedEndAndInvalidJumpTarget() {
        assertThrows(ScriptParseException::class.java) {
            ScriptScenarioLoader.load(ByteArrayInputStream(script(byteArrayOf(0x7F))))
        }
        assertThrows(ScriptParseException::class.java) {
            ScriptScenarioLoader.load(ByteArrayInputStream(script(byteArrayOf(0x01, 0x03))))
        }
        assertThrows(ScriptParseException::class.java) {
            ScriptScenarioLoader.load(ByteArrayInputStream(script(commandStream(jump(999L)))))
        }
    }

    @Test
    fun binaryProbeReportsMalformedScriptAsMalformedInsteadOfMatch() {
        val root = Files.createTempDirectory("ramen-script-malformed").toFile()
        try {
            File(root, "broken.script").writeBytes(script(byteArrayOf(0x7F)))
            File(root, "truncated.script").writeBytes(script(byteArrayOf(0x03, 0x01, 0x00)))
            val source = DirectoryGameSource(root, sourceId = "script-malformed-test")
            val probe = BinarySignatureProbe()
            val unknownOpcode = probe.probeFile(source, "broken.script")
            val truncatedOperand = probe.probeFile(source, "truncated.script")

            assertTrue(unknownOpcode is EngineProbeResult.Malformed)
            assertTrue((unknownOpcode as EngineProbeResult.Malformed).reason.contains("Invalid Script"))
            assertTrue(truncatedOperand is EngineProbeResult.Malformed)
            assertTrue((truncatedOperand as EngineProbeResult.Malformed).reason.contains("Invalid Script"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun script(commands: ByteArray, encoding: Int = 1): ByteArray =
        byteArrayOf(
            0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte(),
            0x01,
            encoding.toByte()
        ) + commands

    private fun commandStream(vararg commands: ByteArray): ByteArray =
        commands.fold(ByteArrayOutputStream()) { output, command ->
            output.apply { write(command) }
        }.toByteArray()

    private fun dialogue(text: String, charset: Charset = StandardCharsets.UTF_8): ByteArray =
        stringCommand(ScriptCommandParser.OPCODE_DISPLAY_DIALOGUE, text, charset)

    private fun background(path: String, charset: Charset = StandardCharsets.UTF_8): ByteArray =
        stringCommand(ScriptCommandParser.OPCODE_CHANGE_BACKGROUND, path, charset)

    private fun stringCommand(opcode: Int, value: String, charset: Charset): ByteArray {
        val bytes = value.toByteArray(charset)
        return byteArrayOf(opcode.toByte(), bytes.size.toByte(), (bytes.size shr 8).toByte()) + bytes
    }

    private fun jump(targetAddress: Long): ByteArray = byteArrayOf(
        ScriptCommandParser.OPCODE_JUMP_TO_SCENE.toByte(),
        targetAddress.toByte(),
        (targetAddress shr 8).toByte(),
        (targetAddress shr 16).toByte(),
        (targetAddress shr 24).toByte()
    )
}
