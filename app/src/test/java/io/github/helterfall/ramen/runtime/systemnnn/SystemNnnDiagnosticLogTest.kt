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
        val previousDecoderLog = File(root, "ramen_debug.previous.log").apply {
            writeText("unsupported_record address=0x50 code=0x33330013 reason=SETEFFECT\n")
        }
        val diagnostics = SystemNnnDiagnosticLog(root, decoderLog, previousDecoderLog)
        diagnostics.record("effect_set", "module=main layer=3 id=66 parameters=1,2,3")
        diagnostics.record("decoder_unsupported", "module=main offset=0x55 code=0x22220005 payloadWords=[00000001,00000002]")
        diagnostics.record("decoder_known_unimplemented", "module=main code=0x3333001b reason=STARTMESSAGE")
        diagnostics.record("decoder_known_ignored", "module=main code=0x33330018 reason=SETCG metadata")
        diagnostics.record("effect_unsupported", "module=op1 film=15 layer=5 id=50")
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
            assertTrue(names.contains("ramen_debug.previous.log"))
            assertTrue(names.contains("diagnostic-summary.txt"))
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
            assertTrue(crash.contains("stack_trace:"))
            assertTrue(crash.contains("runtime_snapshot:"))
            assertTrue(crash.contains("commandOffset=0x1234"))
            val summary = zip.getInputStream(zip.getEntry("diagnostic-summary.txt"))
                .bufferedReader().readText()
            assertTrue(summary.contains("Current session unsupported decoder records: 1"))
            assertTrue(summary.contains("All exported unsupported decoder records: 1"))
            assertTrue(summary.contains("Known native operations not implemented: 1"))
            assertTrue(summary.contains("Known native metadata/no-op operations ignored: 1"))
            assertTrue(summary.contains("Effects without a renderer path: 1"))
            assertTrue(summary.contains("0x22220005: 1"))
            assertTrue(summary.contains("Known native operations not implemented by code:"))
            assertTrue(summary.contains("0x3333001b: 1"))
            assertTrue(summary.contains("Known native metadata/no-op operations by code:"))
            assertTrue(summary.contains("0x33330018: 1"))
            assertTrue(summary.contains("Effects with no native Android renderer path:"))
            assertTrue(summary.contains("effect 50: 1"))
            assertTrue(summary.contains("Raw decoder lines labelled unsupported_record: 1"))
            val decoder = zip.getInputStream(zip.getEntry("ramen_debug.log"))
                .bufferedReader().readText()
            assertTrue(decoder.contains("SETEFFECT"))
        }
    }

    @Test
    fun beginSessionKeepsThePreviousRunSeparateFromCurrentEventsAndCrashes() {
        val root = temporaryFolder.newFolder("sessions")
        val diagnostics = SystemNnnDiagnosticLog(root)
        diagnostics.record("previous_session_event", "from=first")
        diagnostics.updateRuntimeSnapshot("module=op1 command=Dialogue offset=0x40")
        diagnostics.recordException("previous_session_crash", IllegalStateException("first failure"))

        diagnostics.beginSession()
        diagnostics.record("session_start", "appVersion=test")
        diagnostics.updateRuntimeSnapshot("module=main command=Dialogue offset=0x80")

        val archive = diagnostics.createArchive(File(root, "sessions.zip"))
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("system-nnn-diagnostics.previous.log"))
            assertTrue(names.contains("system-nnn-runtime-snapshot.previous.txt"))
            assertTrue(names.contains("system-nnn-crashes.previous.txt"))

            val currentLog = zip.getInputStream(zip.getEntry("system-nnn-diagnostics.log"))
                .bufferedReader().readText()
            val previousLog = zip.getInputStream(zip.getEntry("system-nnn-diagnostics.previous.log"))
                .bufferedReader().readText()
            val previousCrash = zip.getInputStream(zip.getEntry("system-nnn-crashes.previous.txt"))
                .bufferedReader().readText()
            val previousSnapshot = zip.getInputStream(zip.getEntry("system-nnn-runtime-snapshot.previous.txt"))
                .bufferedReader().readText()
            val summary = zip.getInputStream(zip.getEntry("diagnostic-summary.txt"))
                .bufferedReader().readText()

            assertTrue(currentLog.contains("session_start"))
            assertFalse(currentLog.contains("previous_session_event"))
            assertTrue(previousLog.contains("previous_session_event"))
            assertTrue(previousCrash.contains("first failure"))
            assertTrue(previousSnapshot.contains("module=op1 command=Dialogue"))
            assertTrue(summary.contains("Current session diagnostic events: 1"))
            assertTrue(summary.contains("Previous session diagnostic events: 2"))
            assertTrue(summary.contains("Current session crash/exception records: 0"))
            assertTrue(summary.contains("Previous session crash/exception records: 1"))
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
            assertTrue(zip.entries().asSequence().any { it.name == "diagnostic-summary.txt" })
        }
    }
}
