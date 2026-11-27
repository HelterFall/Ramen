package io.github.helterfall.ramen.runtime.systemnnn

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnFullGameAuditorTest {

    @Test
    fun scansEveryPhysicalRecordAndDoesNotExportDialoguePoolContents() {
        val root = Files.createTempDirectory("ramen-full-game-audit").toFile()
        try {
            val sptDirectory = File(root, "spt").apply { mkdirs() }
            File(sptDirectory, "project.xtx").writeText("sample\n", Charsets.US_ASCII)

            val privateDialogueMarker = "PRIVATE_DIALOGUE_MARKER_DO_NOT_EXPORT"
            val scriptFile = File(sptDirectory, "sample.spt")
            scriptFile.writeBytes(createMinimalScript(privateDialogueMarker))

            val manifestFile = File(sptDirectory, "project.xtx")
            val resolvedModules = io.github.helterfall.ramen.parser.SystemNnnProjectCatalog
                .load(manifestFile).resolveModules(manifestFile)
            assertEquals(1, resolvedModules.size)

            val loadedScript = io.github.helterfall.ramen.parser.SystemNnnScriptLoader().load(scriptFile)
            val streamReader = io.github.helterfall.ramen.parser.SystemNnnCommandStreamReader()
            val commandStream = streamReader.read(loadedScript)
            assertEquals(1, commandStream.records.size)
            assertTrue(streamReader.filmTableEntries(loadedScript, commandStream).isEmpty())

            val report = SystemNnnFullGameAuditor(
                cacheDirectory = File(root, "audit-cache")
            ).audit(root)

            val scope = report["scope"] as Map<*, *>
            assertEquals("Full audit report: " + report, 1, scope["allValidSptModules"])
            assertEquals(1, scope["totalPhysicalRecords"])
            assertEquals(0, scope["failedModules"])

            val modules = report["modules"] as List<*>
            val module = modules.single() as Map<*, *>
            assertEquals("scanned", module["status"])
            assertEquals(1, module["physicalRecordCount"])
            assertEquals(1, module["decodedButNotApplied"])

            val opcodes = report["opcodeInventory"] as List<*>
            val startMessage = opcodes.map { it as Map<*, *> }
                .single { it["name"] == "STARTMESSAGE" }
            assertEquals(1, startMessage["occurrences"])
            assertEquals(1, startMessage["decoded"])
            assertEquals(1, startMessage["runtimeNotApplied"])

            val issues = report["issues"] as List<*>
            assertTrue(issues.map { it as Map<*, *> }
                .any { it["code"] == "decoded_but_not_applied" })

            val json = SystemNnnTraceJson.encode(report)
            assertTrue(json.contains("STARTMESSAGE"))
            assertFalse("Dialogue text must remain local to the device.", json.contains(privateDialogueMarker))

            // Exercise the same staged-file and stream-copy path used by the
            // Android SCAN export, not only the in-memory JSON encoder.
            val stagedFile = File(root, "export/ramen_full_game_audit.json")
            val stagedBytes = SystemNnnFullGameAuditExporter.stageReport(report, stagedFile)
            assertTrue("The staged JSON file must contain data.", stagedBytes > 0L)
            assertEquals(stagedBytes, stagedFile.length())
            val exportedBytes = ByteArrayOutputStream()
            assertEquals(
                stagedBytes,
                SystemNnnFullGameAuditExporter.copyTo(stagedFile, exportedBytes)
            )
            val exportedJson = String(exportedBytes.toByteArray(), Charsets.UTF_8)
            assertTrue(exportedJson.startsWith("{"))
            assertTrue(exportedJson.contains("\"reportType\":\"ramen.system-nnn.full-game-audit\""))
            assertEquals(
                stagedBytes,
                SystemNnnFullGameAuditExporter.verifySavedReport(
                    exportedBytes.toByteArray().inputStream(),
                    stagedBytes
                )
            )
            assertTrue(exportedJson.contains("STARTMESSAGE"))
            assertFalse(exportedJson.contains(privateDialogueMarker))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsInvalidSptCandidatesWithoutCopyingTheirPayload() {
        val root = Files.createTempDirectory("ramen-full-game-audit-invalid").toFile()
        try {
            val sptDirectory = File(root, "spt").apply { mkdirs() }
            val privateMarker = "RAW_GAME_BINARY_MARKER_DO_NOT_EXPORT"
            File(sptDirectory, "invalid.spt").writeText(privateMarker, Charsets.UTF_8)

            val report = SystemNnnFullGameAuditor(
                cacheDirectory = File(root, "audit-cache")
            ).audit(root)
            val scope = report["scope"] as Map<*, *>
            assertEquals(1, scope["invalidSptCandidates"])
            val json = SystemNnnTraceJson.encode(report)
            assertFalse(json.contains(privateMarker))
            assertTrue(json.contains("invalid_spt_candidate"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createMinimalScript(poolMarker: String): ByteArray {
        val header = ByteArray(LEGACY_HEADER_BYTES)
        putUInt32(header, 0x00, LEGACY_HEADER_REVISION)
        putUInt32(header, 0x10, 0)
        putUInt32(header, 0x14, 0)
        putUInt32(header, 0x18, 1)
        putUInt32(header, 0x1C, SECONDARY_POOL_BASE)
        putUInt32(header, 0x20, 0)
        putUInt32(header, 0x24, 0)
        putUInt32(header, 0x28, 0)
        putUInt32(header, 0x2C, 0)
        "SPTHEADER0".toByteArray(Charsets.US_ASCII).copyInto(header, SIGNATURE_OFFSET)

        val output = ByteArrayOutputStream()
        output.write(header)
        repeat(4) { writeUInt32(output, 0x11111111L) }
        listOf(5L, 0x66660007L, 0x3333001BL, 1L, 77L).forEach {
            writeUInt32(output, it)
        }
        repeat(4) { writeUInt32(output, 0x22222222L) }

        writeUInt32(output, SECONDARY_POOL_BASE.toLong() + 1L)
        output.write(poolMarker.toByteArray(SHIFT_JIS))
        output.write(0)
        while (output.size() % 4 != 0) output.write(0)
        return xorMask(output.toByteArray())
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> target[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun writeUInt32(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { index ->
            output.write(((value ushr (index * 8)) and 0xFFL).toInt())
        }
    }

    private fun xorMask(decoded: ByteArray): ByteArray = ByteArray(decoded.size) { index ->
        (decoded[index].toInt() xor 0xFF).toByte()
    }

    private companion object {
        const val LEGACY_HEADER_BYTES = 0x40
        const val LEGACY_HEADER_REVISION = 20
        const val SIGNATURE_OFFSET = 0x30
        const val SECONDARY_POOL_BASE = 0x167F2
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
