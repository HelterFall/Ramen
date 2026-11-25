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
    fun reportsDpmxStageFailureInsteadOfReturningGenericUnknown() {
        val root = Files.createTempDirectory("ramen-dpmx-diagnostic").toFile()
        try {
            File(root, "broken.exe").writeBytes(minimalPeWithOverlay("DPMX".toByteArray(Charsets.US_ASCII)))
            val result = VisualNovelDetector().detect(root)
            assertEquals("hsp3-dpmx", result.engineId)
            assertEquals("DETECTED_UNSUPPORTED", result.compatibilityStatus)
            org.junit.Assert.assertTrue(result.evidence.contains("DPMX overlay signature"))
            org.junit.Assert.assertTrue(result.evidence.contains("PE32/DPMX parsing failed"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsNullsoftInstallerMarkerWhenNoGameEngineCanBeLoaded() {
        val root = Files.createTempDirectory("ramen-nsis-diagnostic").toFile()
        try {
            val marker = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte()) +
                "NullsoftInst".toByteArray(Charsets.US_ASCII)
            File(root, "setup.exe").writeBytes(minimalPeWithOverlay(marker))
            val result = VisualNovelDetector().detect(root)
            assertEquals("nsis-installer", result.engineId)
            assertEquals("DETECTED_UNSUPPORTED", result.compatibilityStatus)
            org.junit.Assert.assertTrue(result.evidence.contains("NullsoftInst"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun minimalPeWithOverlay(marker: ByteArray): ByteArray {
        val result = ByteArray(0x400 + marker.size)
        result[0] = 'M'.code.toByte()
        result[1] = 'Z'.code.toByte()
        putUInt32(result, 0x3C, 0x80)
        result[0x80] = 'P'.code.toByte()
        result[0x81] = 'E'.code.toByte()
        putUInt16(result, 0x86, 1)
        putUInt16(result, 0x94, 0xE0)
        putUInt16(result, 0x98, 0x10B)
        val section = 0x178
        putUInt32(result, section + 8, 0x200)
        putUInt32(result, section + 12, 0x1000)
        putUInt32(result, section + 16, 0x200)
        putUInt32(result, section + 20, 0x200)
        marker.copyInto(result, 0x400)
        return result
    }

    private fun putUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
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
