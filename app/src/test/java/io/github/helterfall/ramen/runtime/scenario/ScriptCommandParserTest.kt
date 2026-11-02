package io.github.helterfall.ramen.runtime.scenario

import io.github.helterfall.ramen.runtime.engine.BinarySignatureProbe
import io.github.helterfall.ramen.runtime.engine.DirectoryGameSource
import io.github.helterfall.ramen.runtime.engine.EngineProbeResult
import io.github.helterfall.ramen.runtime.engine.EngineAdapterRegistry
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
    fun decodesXorMaskedOpcodesAndOperandsFromOneContinuousStream() {
        val plaintext = commandStream(dialogue("Masked dialogue"), background("bg/masked.png"))
        val masked = ByteArray(plaintext.size) { index ->
            (plaintext[index].toInt() xor 0x10).toByte()
        }
        val parser = ScriptCommandParser(xorKey = 0x10)
        val stream = ByteArrayInputStream(masked)

        assertEquals(DisplayDialogueCommand("Masked dialogue"), parser.parseNextCommand(stream))
        assertEquals(ChangeBackgroundCommand("bg/masked.png"), parser.parseNextCommand(stream))
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
    fun xorLoaderStopsAtTheCommandBoundaryAndLeavesClearVfsTrailerUnread() {
        val commands = dialogue("Before VFS")
        val trailer = "sys0.gpk\u0000ta0.gpk\u0000".toByteArray(StandardCharsets.US_ASCII)
        val encryptedScript = xorMask(script(commands), 0x10) + trailer
        val input = ByteArrayInputStream(encryptedScript)

        val project = ScriptScenarioLoader.load(
            stream = input,
            textCharset = StandardCharsets.UTF_8,
            xorKey = 0x10,
            commandStreamLengthBytes = commands.size.toLong()
        )

        assertEquals(
            "Before VFS",
            ScenarioSession.start(project).frame().dialogue?.text
        )
        assertEquals(trailer.size, input.available())
        val remaining = ByteArray(trailer.size)
        assertEquals(trailer.size, input.read(remaining))
        assertTrue(remaining.contentEquals(trailer))
    }

    @Test
    fun loaderMapsShiftJisInstructionsIntoScenarioAndSaveState() {
        val charset = Charset.forName("Shift_JIS")
        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(
                script(
                    commandStream(background("背景/海.png", charset), dialogue("灯台が見えます。", charset))
                )
            ),
            textCharset = charset
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
        val targetAddress = 8L + 5L
        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(script(commandStream(jump(targetAddress), dialogue("Reached target"))))
        )
        val session = ScenarioSession.start(project)

        assertEquals("Reached target", session.frame().dialogue?.text)
        assertEquals("addr_$targetAddress", session.frame().sceneId)
    }

    @Test
    fun nitroStartSceneMarkerIsRecognizedAndFollowingCommandsAreParsed() {
        val parser = ScriptCommandParser(initialOffset = 8L)
        val stream = ByteArrayInputStream(
            byteArrayOf(ScriptCommandParser.OPCODE_NITRO_START_SCENE.toByte()) +
                dialogue("Opening scene")
        )

        assertEquals(NitroStartSceneCommand, parser.parseNextCommand(stream))
        assertEquals(9L, parser.nextCommandOffset)
        assertEquals(
            DisplayDialogueCommand("Opening scene"),
            parser.parseNextCommand(stream)
        )
        assertNull(parser.parseNextCommand(stream))
    }

    @Test
    fun loaderTreatsNitroStartSceneMarkerAsANoOpAndContinuesToDialogue() {
        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(
                script(
                    byteArrayOf(ScriptCommandParser.OPCODE_NITRO_START_SCENE.toByte()) +
                        dialogue("Opening scene")
                )
            )
        )
        val session = ScenarioSession.start(project)

        assertEquals("Opening scene", session.frame().dialogue?.text)
        assertEquals("addr_9", session.frame().sceneId)
    }

    @Test
    fun nitroInitEngineOpcodeFFIsRecognizedAndFollowingCommandsAreParsed() {
        val parser = ScriptCommandParser(initialOffset = 8L)
        val stream = ByteArrayInputStream(
            byteArrayOf(ScriptCommandParser.OPCODE_NITRO_INIT_ENGINE.toByte()) +
                dialogue("After engine init")
        )

        assertEquals(NitroInitEngineCommand, parser.parseNextCommand(stream))
        assertEquals(9L, parser.nextCommandOffset)
        assertEquals(
            DisplayDialogueCommand("After engine init"),
            parser.parseNextCommand(stream)
        )
        assertNull(parser.parseNextCommand(stream))
    }

    @Test
    fun loaderTreatsNitroInitEngineAsANoOpAndContinuesToDialogue() {
        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(
                script(
                    byteArrayOf(ScriptCommandParser.OPCODE_NITRO_INIT_ENGINE.toByte()) +
                        dialogue("After engine init")
                )
            )
        )
        val session = ScenarioSession.start(project)

        assertEquals("After engine init", session.frame().dialogue?.text)
        assertEquals("addr_9", session.frame().sceneId)
    }

    @Test
    fun loaderAllowsEngineInitThenStartSceneMarkersBeforeFirstDialogue() {
        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(
                script(
                    byteArrayOf(
                        ScriptCommandParser.OPCODE_NITRO_INIT_ENGINE.toByte(),
                        ScriptCommandParser.OPCODE_NITRO_START_SCENE.toByte()
                    ) + dialogue("First visible line")
                )
            )
        )
        val session = ScenarioSession.start(project)

        assertEquals("First visible line", session.frame().dialogue?.text)
        assertEquals("addr_10", session.frame().sceneId)
    }

    @Test
    fun decodesXorMaskedNitroInitMarkerBeforeTheFirstDialogue() {
        val commands = byteArrayOf(
            ScriptCommandParser.OPCODE_NITRO_START_SCENE.toByte(),
            ScriptCommandParser.OPCODE_NITRO_INIT_ENGINE.toByte()
        ) + dialogue("Decrypted init")
        val maskedScript = xorMask(script(commands), 0x10)

        val project = ScriptScenarioLoader.load(
            ByteArrayInputStream(maskedScript),
            StandardCharsets.UTF_8,
            0x10
        )
        val session = ScenarioSession.start(project)

        assertEquals("Decrypted init", session.frame().dialogue?.text)
        assertEquals("addr_10", session.frame().sceneId)
    }

    @Test
    fun loaderRejectsAStreamContainingOnlySystemMarkers() {
        assertThrows(ScriptParseException::class.java) {
            ScriptScenarioLoader.load(
                ByteArrayInputStream(
                    script(
                        byteArrayOf(
                            ScriptCommandParser.OPCODE_NITRO_INIT_ENGINE.toByte(),
                            ScriptCommandParser.OPCODE_NITRO_START_SCENE.toByte()
                        )
                    )
                )
            )
        }
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
    fun binaryProbePreservesScriptMagicWhenTheBodyIsNotRamenScriptV1() {
        val root = Files.createTempDirectory("ramen-script-malformed").toFile()
        try {
            File(root, "broken.script").writeBytes(script(byteArrayOf(0x7F)))
            File(root, "truncated.script").writeBytes(script(byteArrayOf(0x03, 0x01, 0x00)))
            val source = DirectoryGameSource(root, sourceId = "script-malformed-test")
            val probe = BinarySignatureProbe()

            listOf("broken.script", "truncated.script").forEach { path ->
                val result = probe.probeFile(source, path)
                assertTrue(result is EngineProbeResult.Match)
                val match = result as EngineProbeResult.Match
                assertEquals("script", match.evidence.single().resourceType)
                assertTrue(match.evidence.single().description.contains("did not validate"))
                assertEquals(null, match.formatVersion)
            }

            val summary = EngineAdapterRegistry(listOf(probe)).probe(source)
            assertEquals(1, summary.matches.size)
            assertTrue(summary.malformed.isEmpty())
            assertEquals(2, summary.matches.single().evidence.count { it.resourceType == "script" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun xorMask(bytes: ByteArray, key: Int): ByteArray =
        ByteArray(bytes.size) { index -> (bytes[index].toInt() xor key).toByte() }

    private fun script(commands: ByteArray): ByteArray =
        byteArrayOf(
            0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte()
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
