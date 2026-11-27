package io.github.helterfall.ramen.nscripter

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NscripterStaticScannerTest {
    @Test
    fun auditsCommandsResourcesArchiveEntriesAndUnknownDirectives() {
        val root = Files.createTempDirectory("nscripter-static-scan").toFile()
        try {
            File(root, "0.txt").writeText(
                "; bg is commented out\n" +
                    "*start\n" +
                    "bg \"scene.bmp\",1\n" +
                    "@bg \"scene-two.bmp\",2\n" +
                    "dwave \"voice.ogg\",0\n" +
                    "@customfx 1\n" +
                    ":customfx 2\n" +
                    "n \"a semicolon ; inside quotes\"\n"
            )
            File(root, "scene.bmp").writeBytes(byteArrayOf(1))
            File(root, "scene-two.bmp").writeBytes(byteArrayOf(2))
            File(root, "voice.ogg").writeBytes(byteArrayOf(3))
            File(root, "portrait.png").writeBytes(byteArrayOf(4))
            File(root, "theme.wav").writeBytes(byteArrayOf(5))
            File(root, "arc.sar").writeBytes(createSar("packed/cg.jpg", byteArrayOf(6, 7)))

            val scanner = NscripterStaticScanner()
            val report = scanner.scan(root)

            assertEquals(1, report.scriptFiles.size)
            assertEquals(2, report.commandCounts["bg"])
            assertEquals(1, report.commandCounts["dwave"])
            assertEquals(1, report.commandCounts["n"])
            assertEquals(2, report.graphicsFormats["bmp"])
            assertEquals(1, report.graphicsFormats["png"])
            assertEquals(1, report.graphicsFormats["jpg"])
            assertEquals(1, report.audioFormats["ogg"])
            assertEquals(1, report.audioFormats["wav"])
            assertEquals(1, report.archives.size)
            assertEquals(1, report.archives.single().entryCount)

            val customDirective = report.unsupportedDirectives.single {
                it.directive == "customfx"
            }
            assertEquals("0.txt", customDirective.file)
            assertEquals(2, customDirective.count)
            assertEquals(listOf(6, 7), customDirective.lineNumbers)
            assertTrue(report.parseErrors.isEmpty())

            val json = scanner.toJson(report)
            assertTrue(json.contains("\"commandFrequency\""))
            assertTrue(json.contains("\"bg\": 2"))
            assertTrue(json.contains("\"directive\": \"customfx\""))
            assertFalse(json.contains("\"parseErrors\": [\""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsArchiveAndScriptFailuresVisibleInReport() {
        val root = Files.createTempDirectory("nscripter-errors").toFile()
        try {
            File(root, "0.txt").writeBytes(byteArrayOf(0, 1, 2))
            File(root, "broken.sar").writeBytes(byteArrayOf(0, 1, 0))

            val report = NscripterStaticScanner().scan(root)

            assertEquals(2, report.parseErrors.size)
            assertTrue(report.parseErrors.any { it.contains("broken.sar") })
            assertTrue(report.parseErrors.any { it.contains("0.txt") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writesJsonReportToRequestedFile() {
        val root = Files.createTempDirectory("nscripter-json-game").toFile()
        val outputDirectory = Files.createTempDirectory("nscripter-json-output").toFile()
        try {
            File(root, "0.txt").writeText("bg \"room.jpg\",1\nend\n")
            val output = File(outputDirectory, "scan.json")

            NscripterStaticScanner().writeJson(root, output)

            assertTrue(output.isFile)
            assertTrue(output.readText().contains("\"engine\": \"NScripter/ONScripter\""))
        } finally {
            root.deleteRecursively()
            outputDirectory.deleteRecursively()
        }
    }

    private fun createSar(name: String, payload: ByteArray): ByteArray {
        val encodedName = name.toByteArray(Charset.forName("Shift_JIS"))
        val baseOffset = 6 + encodedName.size + 1 + 8
        return ByteArrayOutputStream().apply {
            writeU16(1)
            writeU32(baseOffset.toLong())
            write(encodedName)
            write(0)
            writeU32(0)
            writeU32(payload.size.toLong())
            write(payload)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write(((value ushr 24) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    @Test
    fun reportsEncryptedArchiveVariantExplicitly() {
        val root = Files.createTempDirectory("nscripter-encrypted-archive").toFile()
        try {
            File(root, "arc.___").writeBytes(byteArrayOf(1, 2, 3))
            val report = NscripterStaticScanner().scan(root)
            assertTrue(report.parseErrors.any { it.contains("arc.___") && it.contains("encrypted NSA") })
        } finally {
            root.deleteRecursively()
        }
    }

}
