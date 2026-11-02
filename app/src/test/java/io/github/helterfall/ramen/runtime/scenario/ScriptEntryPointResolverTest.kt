package io.github.helterfall.ramen.runtime.scenario

import io.github.helterfall.ramen.runtime.engine.DirectoryGameSource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptEntryPointResolverTest {
    @Test
    fun prefersConventional01SptEntryPoint() {
        withGameDirectory { root ->
            File(root, "main.spt").writeBytes(validScript(dialogue("Main")))
            File(root, "nested").mkdirs()
            File(root, "nested/init.spt").writeBytes(validScript(dialogue("Nested init")))
            File(root, "01.spt").writeBytes(validScript(dialogue("Opening")))

            val source = DirectoryGameSource(root, "script-entry-priority")
            val selected = ScriptEntryPointResolver().select(source)

            assertNotNull(selected)
            assertEquals("01.spt", selected?.relativePath)
            val project = source.openRead(selected!!.relativePath).use(ScriptScenarioLoader::load)
            assertEquals("Opening", ScenarioSession.start(project).frame().dialogue?.text)
        }
    }

    @Test
    fun findsInitializerByMagicWhenNoSptExtensionIsPresent() {
        withGameDirectory { root ->
            File(root, "startup.dat").writeBytes(validScript(dialogue("Loaded by magic")))
            File(root, "readme.txt").writeText("not a script")

            val source = DirectoryGameSource(root, "script-magic-entry")
            val selected = ScriptEntryPointResolver().select(source)

            assertEquals("startup.dat", selected?.relativePath)
        }
    }

    @Test
    fun sessionDisplaysDialogueAndAppliesFollowingBackgroundCommand() {
        withGameDirectory { root ->
            val script = validScript(
                dialogue("Opening line") +
                    background("background/shore") +
                    dialogue("Next line")
            )
            File(root, "01.spt").writeBytes(script)

            val source = DirectoryGameSource(root, "script-session-integration")
            val entry = ScriptEntryPointResolver().select(source)
                ?: throw AssertionError("Entry point missing.")
            val project = source.openRead(entry.relativePath).use(ScriptScenarioLoader::load)
            val session = ScenarioSession.start(project)

            assertEquals("Opening line", session.frame().dialogue?.text)
            val next = session.continueStory()
            assertEquals("background/shore", next.backgroundResourceKey)
            assertEquals("Next line", next.dialogue?.text)
        }
    }

    @Test
    fun unknownOpcodeInPreferredSptIsMalformedAndDoesNotCreateASession() {
        withGameDirectory { root ->
            File(root, "01.spt").writeBytes(validScript(byteArrayOf(0x7F)))

            val source = DirectoryGameSource(root, "script-malformed-entry")
            val entry = ScriptEntryPointResolver().select(source)
                ?: throw AssertionError("Preferred .spt candidate should be selected.")
            val failure = runCatching {
                source.openRead(entry.relativePath).use(ScriptScenarioLoader::load)
            }.exceptionOrNull()

            assertTrue(failure is ScriptParseException)
            assertTrue(failure?.message.orEmpty().contains("Unknown command opcode"))
        }
    }

    @Test
    fun loadSkipsMalformedPreferredSptAndUsesNextValidScript() {
        withGameDirectory { root ->
            File(root, "01.spt").writeBytes(validScript(byteArrayOf(0x7F)))
            File(root, "main.spt").writeBytes(validScript(dialogue("Valid fallback")))

            val result = ScriptEntryPointResolver().load(
                DirectoryGameSource(root, "script-starter-fallback")
            )

            assertTrue(result is ScriptEntryPointLoadResult.Ready)
            val ready = result as ScriptEntryPointLoadResult.Ready
            assertEquals("main.spt", ready.entry.relativePath)
            assertEquals(
                "Valid fallback",
                ScenarioSession.start(ready.project).frame().dialogue?.text
            )
        }
    }

    @Test
    fun loadReportsUnknownOpcodeWhenNoCandidateCanBeParsed() {
        withGameDirectory { root ->
            File(root, "01.spt").writeBytes(validScript(byteArrayOf(0x7F)))

            val result = ScriptEntryPointResolver().load(
                DirectoryGameSource(root, "script-starter-only-malformed")
            )

            assertTrue(result is ScriptEntryPointLoadResult.Malformed)
            val malformed = result as ScriptEntryPointLoadResult.Malformed
            assertEquals(1, malformed.candidatesInspected)
            assertTrue(malformed.failures.single().reason.contains("Unknown command opcode"))
        }
    }

    @Test
    fun loadRecognizesMagicInitializerWithoutSptExtension() {
        withGameDirectory { root ->
            File(root, "bootstrap.bin").writeBytes(validScript(dialogue("Magic initializer")))

            val result = ScriptEntryPointResolver().load(
                DirectoryGameSource(root, "script-magic-initializer")
            )

            assertTrue(result is ScriptEntryPointLoadResult.Ready)
            val ready = result as ScriptEntryPointLoadResult.Ready
            assertEquals("bootstrap.bin", ready.entry.relativePath)
            assertEquals(
                "Magic initializer",
                ScenarioSession.start(ready.project).frame().dialogue?.text
            )
        }
    }

    @Test
    fun returnsNoEntryPointWhenThereIsNoSptOrScriptSignature() {
        withGameDirectory { root ->
            File(root, "notes.txt").writeText("ordinary content")
            val source = DirectoryGameSource(root, "no-script")
            assertEquals(null, ScriptEntryPointResolver().select(source))
        }
    }

    private fun validScript(commands: ByteArray): ByteArray =
        byteArrayOf(
            0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte(),
            0x01, 0x01
        ) + commands

    private fun dialogue(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x01, bytes.size.toByte(), 0x00) + bytes
    }

    private fun background(path: String): ByteArray {
        val bytes = path.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x02, bytes.size.toByte(), 0x00) + bytes
    }

    private fun withGameDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ramen-script-player-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
