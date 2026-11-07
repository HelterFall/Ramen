package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemNnnDiagnosticLogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportContainsRuntimeSnapshotDecoderLogAndCrashTrace() {
        val root = temporaryFolder.newFolder("diagnostics")
        val decoderLog = File(root, "ramen_debug.log").apply {
            writeText("SETEFFECT layer=3 effect=66 parameters=1,2,3\n")
        }
        val diagnostics = SystemNnnDiagnosticLog(root, decoderLog)
        diagnostics.record("effect", "layer=3 id=66 parameters=1,2,3")
        diagnostics.updateRuntimeSnapshot("module=main film=2 commandOffset=0x1234")
        diagnostics.recordException("decode_failure", IllegalStateException("test failure"), "offset=0x1234")

        val archive = diagnostics.createArchive(File(root, "export.zip"))
        assertTrue(archive.isFile)

        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("system-nnn-diagnostics.log"))
            assertTrue(names.contains("system-nnn-runtime-snapshot.txt"))
            assertTrue(names.contains("system-nnn-crashes.txt"))
            assertTrue(names.contains("ramen_debug.log"))
            assertTrue(names.contains("README.txt"))

            val log = zip.getInputStream(zip.getEntry("system-nnn-diagnostics.log"))
                .bufferedReader().readText()
            assertTrue(log.contains("layer=3 id=66"))
            val snapshot = zip.getInputStream(zip.getEntry("system-nnn-runtime-snapshot.txt"))
                .bufferedReader().readText()
            assertTrue(snapshot.contains("commandOffset=0x1234"))
            val crash = zip.getInputStream(zip.getEntry("system-nnn-crashes.txt"))
                .bufferedReader().readText()
            assertTrue(crash.contains("test failure"))
            val decoder = zip.getInputStream(zip.getEntry("ramen_debug.log"))
                .bufferedReader().readText()
            assertTrue(decoder.contains("SETEFFECT"))
        }
    }

    @Test
    fun diagnosticsSanitizeMultilineValuesAndDoNotRequireDecoderLog() {
        val root = temporaryFolder.newFolder("minimal")
        val diagnostics = SystemNnnDiagnosticLog(root)
        diagnostics.record("warning", "first line\nsecond line")
        val archive = diagnostics.createArchive(File(root, "minimal.zip"))

        ZipFile(archive).use { zip ->
            val log = zip.getInputStream(zip.getEntry("system-nnn-diagnostics.log"))
                .bufferedReader().readText()
            assertTrue(log.contains("first line second line"))
            assertFalse(zip.entries().asSequence().any { it.name == "ramen_debug.log" })
        }
    }
}
