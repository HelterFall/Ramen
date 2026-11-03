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
        val text = "fixture".toByteArray(Charset.forName("Shift_JIS")) + byteArrayOf(0)
        val decoded = ByteArray(0x32B64 + text.size)
        putUInt32(decoded, 0x00, 20)
        putUInt32(decoded, 0x10, 1299)
        putUInt32(decoded, 0x14, 0xC5BA)
        putUInt32(decoded, 0x18, 1124)
        putUInt32(decoded, 0x1C, 0x167F2)
        putUInt32(decoded, 0x20, 69)
        putUInt32(decoded, 0x24, 0xC390)
        putUInt32(decoded, 0x28, 2)
        putUInt32(decoded, 0x2C, 0)
        "SPTHEADER$headerVersion".toByteArray(Charsets.US_ASCII).copyInto(decoded, 0x30)

        text.copyInto(decoded, 0x32B64)

        return ByteArray(decoded.size) { index ->
            (decoded[index].toInt() xor 0xFF).toByte()
        }
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            target[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
