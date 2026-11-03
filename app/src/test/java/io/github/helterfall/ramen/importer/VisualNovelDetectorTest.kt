package io.github.helterfall.ramen.importer

import java.io.File
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
    fun detectsBlackCycOpaqueScriptWhenMatchingArchivesExist() {
        val root = Files.createTempDirectory("ramen-blackcyc-detect").toFile()

        try {
            File(root, "scenario.spt").writeBytes(byteArrayOf(0x01))
            File(root, "graphics.gpk").writeBytes(byteArrayOf(0x02))
            File(root, "graphics.gtb").writeBytes(byteArrayOf(0x03))

            val result = VisualNovelDetector().detect(root)

            assertEquals("blackcyc", result.engineId)
            assertEquals("DETECTED_RESOURCE_PREVIEW", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sptWithoutMatchingArchivesDoesNotIdentifyBlackCyc() {
        val root = Files.createTempDirectory("ramen-spt-only-detect").toFile()

        try {
            File(root, "scenario.spt").writeBytes(byteArrayOf(0x01))

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
}
