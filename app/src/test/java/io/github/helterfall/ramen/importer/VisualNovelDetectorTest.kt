package io.github.helterfall.ramen.importer

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualNovelDetectorTest {
    @Test
    fun detectsRootHtmlEntryPointAsLimitedSupportedRuntime() {
        val root = Files.createTempDirectory("ramen-html-detect").toFile()

        try {
            File(root, "index.html").writeText("<html></html>")

            val result = VisualNovelDetector().detect(root)

            assertEquals("html5", result.engineId)
            assertEquals("SUPPORTED_LIMITED", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nestedHtmlEntryPointIsNotMistakenForRootRuntime() {
        val root = Files.createTempDirectory("ramen-nested-html-detect").toFile()

        try {
            val nested = File(root, "www")
            nested.mkdirs()
            File(nested, "index.html").writeText("<html></html>")

            val result = VisualNovelDetector().detect(root)

            assertEquals("unknown", result.engineId)
            assertEquals("UNKNOWN", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsTyranoMarker() {
        val root = Files.createTempDirectory("ramen-tyrano-detect").toFile()

        try {
            File(root, "index.html").writeText("<html></html>")
            val marker = File(root, "tyrano/tyrano.js")
            marker.parentFile?.mkdirs()
            marker.writeText("// original fixture marker")

            val result = VisualNovelDetector().detect(root)

            assertEquals("tyranoscript", result.engineId)
            assertEquals("DETECTED_UNSUPPORTED", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsRenpyScript() {
        val root = Files.createTempDirectory("ramen-renpy-detect").toFile()
        val gameFolder = File(root, "game")

        try {
            gameFolder.mkdirs()
            File(gameFolder, "script.rpy").writeText("label start:")

            val result = VisualNovelDetector().detect(root)

            assertEquals("renpy", result.engineId)
            assertEquals("DETECTED_UNSUPPORTED", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsSystemNnnSptAndDoesNotCallItAPlaybackRuntime() {
        val root = Files.createTempDirectory("ramen-system-nnn-detect").toFile()

        try {
            val script = File(root, "scenario.spt")
            script.writeBytes(createMinimalSptFixture())

            val result = VisualNovelDetector().detect(root)

            assertEquals("system-nnn-spt", result.engineId)
            assertEquals("PARSER_AVAILABLE", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsUpdatedSystemNnnHeaderDigit() {
        val root = Files.createTempDirectory("ramen-system-nnn-header-detect").toFile()
        try {
            File(root, "scenario.spt").writeBytes(createMinimalSptFixture(headerVersion = 7))
            val result = VisualNovelDetector().detect(root)
            assertEquals("system-nnn-spt", result.engineId)
            assertEquals("PARSER_AVAILABLE", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownContentStaysUnknown() {
        val root = Files.createTempDirectory("ramen-unknown-detect").toFile()

        try {
            File(root, "readme.txt").writeText("not an engine marker")

            val result = VisualNovelDetector().detect(root)

            assertEquals("unknown", result.engineId)
            assertEquals("UNKNOWN", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createMinimalSptFixture(headerVersion: Int = 0): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val header = ByteArray(0x100)

        putUInt32(header, 0x00, 20)
        putUInt32(header, 0x10, 1)
        putUInt32(header, 0x14, 0xC5BA)
        putUInt32(header, 0x18, 1)
        putUInt32(header, 0x1C, 0x167F2)
        putUInt32(header, 0x20, 0)
        putUInt32(header, 0x24, 0)
        putUInt32(header, 0x28, 0)
        putUInt32(header, 0x2C, 0)
        "SPTHEADER$headerVersion".toByteArray(Charsets.US_ASCII).copyInto(header, 0x30)
        output.write(header)

        // Pool 1 address table: one logical address, base + entry count.
        writeUInt32(output, 0xC5BBL)
        writeAlignedRecord(output, "fixture")

        // The inter-pool marker is 16 bytes: 4 bytes of per-file value
        // followed by the three stable marker pairs.
        writeUInt32(output, 0x12345678L)
        writeUInt16(output, 1)
        writeUInt16(output, 0x6666)
        writeUInt16(output, 2)
        writeUInt16(output, 0x5555)
        writeUInt16(output, 1)
        writeUInt16(output, 0x4444)

        // Pool 2 has its own logical address table and record.
        writeUInt32(output, 0x167F3L)
        writeAlignedRecord(output, "module")

        return ByteArray(output.size()) { index ->
            (output.toByteArray()[index].toInt() xor 0xFF).toByte()
        }
    }

    private fun writeAlignedRecord(output: java.io.ByteArrayOutputStream, value: String) {
        output.write(value.toByteArray(Charset.forName("Shift_JIS")))
        output.write(0)
        while (output.size() % 4 != 0) output.write(0)
    }

    private fun writeUInt32(output: java.io.ByteArrayOutputStream, value: Long) {
        repeat(4) { index ->
            output.write(((value ushr (index * 8)) and 0xFFL).toInt())
        }
    }

    private fun writeUInt16(output: java.io.ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            target[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
