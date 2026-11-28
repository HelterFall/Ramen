package io.github.helterfall.ramen.importer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class NscripterGameDetectorTest {
    @Test
    fun detectsPlainTextEntryScript() {
        assertNscripterMarker("0.txt")
    }

    @Test
    fun detectsNscriptDat() {
        assertNscripterMarker("nscript.dat")
    }

    @Test
    fun detectsRootNsaAndSarArchives() {
        assertNscripterMarker("arc.nsa")
        assertNscripterMarker("arc.sar")
        assertNscripterMarker("arc2.nsa")
    }

    @Test
    fun detectsNumberedTextSegment() {
        assertNscripterMarker("17.txt")
        assertNscripterMarker("01.txt")
    }

    @Test
    fun detectsUtf8OnscripterEntryAndSegments() {
        assertNscripterMarker("0.utf")
        assertNscripterMarker("00.utf")
        assertNscripterMarker("27.utf")
    }

    private fun assertNscripterMarker(marker: String) {
        val root = Files.createTempDirectory("nscripter-detection").toFile()
        try {
            File(root, marker).writeBytes(byteArrayOf(1, 2, 3))
            val result = VisualNovelDetector().detect(root)
            assertEquals("nscripter", result.engineId)
            assertEquals("PARSER_AVAILABLE", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun identifiesProtectedScriptFormatsAsDetectedButUnsupported() {
        val root = Files.createTempDirectory("nscripter-protected-detection").toFile()
        try {
            File(root, "nscr_sec.dat").writeBytes(byteArrayOf(1, 2, 3))
            val result = VisualNovelDetector().detect(root)
            assertEquals("nscripter", result.engineId)
            assertEquals("DETECTED_UNSUPPORTED", result.compatibilityStatus)
        } finally {
            root.deleteRecursively()
        }
    }

}
