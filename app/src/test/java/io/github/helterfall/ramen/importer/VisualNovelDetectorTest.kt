package io.github.helterfall.ramen.importer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun detectsValidatedIfPacktFixtureAsTestBinaryEngine() {
        val root = Files.createTempDirectory("ramen-pack-engine-detect").toFile()
        try {
            File(root, "assets.pack").writeBytes(emptyPackArchive())

            val result = VisualNovelDetector().detect(root)

            assertEquals("ramen-binary-signature", result.engineId)
            assertEquals("Обнаружен тестовый движок v1", result.engineLabel)
            assertEquals("DETECTED_UNSUPPORTED", result.compatibilityStatus)
            assertTrue(result.evidence.contains("assets.pack"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun detectsValidatedScriptFixtureAsTestBinaryEngine() {
        val root = Files.createTempDirectory("ramen-script-engine-detect").toFile()
        try {
            File(root, "story.bin").writeBytes(validScriptFixture())

            val result = VisualNovelDetector().detect(root)

            assertEquals("ramen-binary-signature", result.engineId)
            assertEquals("Обнаружен тестовый движок v1", result.engineLabel)
            assertEquals("DETECTED_UNSUPPORTED", result.compatibilityStatus)
            assertTrue(result.evidence.contains("story.bin"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun graphicBlockSignatureAloneDoesNotPromoteDirectoryToDetectedEngine() {
        val root = Files.createTempDirectory("ramen-graphic-only-detect").toFile()
        try {
            File(root, "cover.bin").writeBytes("JPEG    ".toByteArray(Charsets.US_ASCII))

            val result = VisualNovelDetector().detect(root)

            assertEquals("unknown", result.engineId)
            assertEquals("UNKNOWN", result.compatibilityStatus)
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
    private fun validScriptFixture(): ByteArray =
        byteArrayOf(
            0xEB.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xFF.toByte(), 0x99.toByte(), 0x99.toByte(),
            0x01, // Script v1
            0x01, // UTF-8
            0x01, // dialogue
            0x05, 0x00
        ) + "Hello".toByteArray(Charsets.UTF_8)

    private fun emptyPackArchive(): ByteArray =
        ByteBuffer.allocate(20)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("IF PACKT".toByteArray(Charsets.US_ASCII))
            .putInt(1)
            .putInt(0)
            .putInt(0)
            .array()
}
